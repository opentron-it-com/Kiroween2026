#!/usr/bin/env python3
"""Expose one bounded KMS Recipient Decrypt operation over Nitro vsock."""

import base64
import json
import logging
import os
import socket
import struct
import subprocess
import tempfile
from typing import Any

MAX_REQUEST_FRAME_BYTES = 384 * 1024
MAX_RESPONSE_FRAME_BYTES = 16 * 1024
MAX_CIPHERTEXT_BYTES = 6 * 1024
MAX_CIPHERTEXT_FOR_RECIPIENT_BYTES = 6 * 1024
MAX_ATTESTATION_DOCUMENT_BYTES = 256 * 1024
MAX_KEY_ID_LENGTH = 2048
KEY_ENCRYPTION_ALGORITHM = "RSAES_OAEP_SHA_256"
SOCKET_TIMEOUT_SECONDS = 35
KMS_TIMEOUT_SECONDS = 30


class ProxyRequestError(ValueError):
    """A malformed or disallowed enclave request."""


def _recv_exact(connection: socket.socket, length: int) -> bytes:
    result = bytearray()
    while len(result) < length:
        chunk = connection.recv(length - len(result))
        if not chunk:
            raise ProxyRequestError("Incomplete vsock frame.")
        result.extend(chunk)
    return bytes(result)


def _decode_base64(value: Any, field: str, max_length: int) -> bytes:
    if not isinstance(value, str) or not value:
        raise ProxyRequestError(f"{field} must be a non-empty base64 string.")
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, base64.binascii.Error) as error:
        raise ProxyRequestError(f"{field} is not valid base64.") from error
    if len(decoded) > max_length:
        raise ProxyRequestError(
            f"{field} exceeds its size bound ({len(decoded)} > {max_length} bytes)."
        )
    return decoded


def _parse_request(payload: bytes, expected_key_id: str) -> dict[str, Any]:
    if not payload or len(payload) > MAX_REQUEST_FRAME_BYTES:
        raise ProxyRequestError("Request frame exceeds its size bound.")
    try:
        envelope = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProxyRequestError("Request frame is not valid JSON.") from error
    if not isinstance(envelope, dict) or set(envelope) != {
        "version",
        "operation",
        "request",
    }:
        raise ProxyRequestError("Request envelope is invalid.")
    if envelope["version"] != 1 or envelope["operation"] != "kms:Decrypt":
        raise ProxyRequestError("Only protocol v1 kms:Decrypt requests are accepted.")

    request = envelope["request"]
    if not isinstance(request, dict) or set(request) != {
        "KeyId",
        "CiphertextBlob",
        "Recipient",
    }:
        raise ProxyRequestError("KMS request fields are invalid.")
    if request["KeyId"] != expected_key_id or len(expected_key_id) > MAX_KEY_ID_LENGTH:
        raise ProxyRequestError("KMS key ID is not allowed.")

    ciphertext = _decode_base64(
        request["CiphertextBlob"], "CiphertextBlob", MAX_CIPHERTEXT_BYTES
    )
    recipient = request["Recipient"]
    if not isinstance(recipient, dict) or set(recipient) != {
        "AttestationDocument",
        "KeyEncryptionAlgorithm",
    }:
        raise ProxyRequestError("KMS Recipient fields are invalid.")
    if recipient["KeyEncryptionAlgorithm"] != KEY_ENCRYPTION_ALGORITHM:
        raise ProxyRequestError("KMS Recipient algorithm is not allowed.")
    attestation_document = _decode_base64(
        recipient["AttestationDocument"],
        "AttestationDocument",
        MAX_ATTESTATION_DOCUMENT_BYTES,
    )
    return {
        "KeyId": expected_key_id,
        "CiphertextBlob": base64.b64encode(ciphertext).decode("ascii"),
        "Recipient": {
            "AttestationDocument": base64.b64encode(attestation_document).decode("ascii"),
            "KeyEncryptionAlgorithm": KEY_ENCRYPTION_ALGORITHM,
        },
    }


def _decrypt_with_aws_cli(request: dict[str, Any], region: str) -> str:
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            prefix="cjae-kms-",
            suffix=".json",
            dir=os.environ.get("TMPDIR") or tempfile.gettempdir(),
            delete=False,
        ) as request_file:
            temporary_path = request_file.name
            os.chmod(temporary_path, 0o600)
            json.dump(request, request_file, separators=(",", ":"))

        result = subprocess.run(
            [
                "aws",
                "kms",
                "decrypt",
                "--region",
                region,
                "--cli-input-json",
                f"file://{temporary_path}",
                "--output",
                "json",
                "--no-cli-pager",
            ],
            capture_output=True,
            check=False,
            text=True,
            timeout=KMS_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as error:
        raise ProxyRequestError("KMS Decrypt request timed out.") from error
    except OSError as error:
        raise ProxyRequestError("Unable to invoke the AWS KMS client.") from error
    finally:
        if temporary_path is not None:
            try:
                os.unlink(temporary_path)
            except FileNotFoundError:
                pass

    if result.returncode != 0:
        raise ProxyRequestError("AWS KMS rejected the Decrypt request.")
    try:
        response = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise ProxyRequestError("AWS KMS returned an invalid response.") from error
    ciphertext_for_recipient = response.get("CiphertextForRecipient")
    decoded_response = _decode_base64(
        ciphertext_for_recipient,
        "CiphertextForRecipient",
        MAX_CIPHERTEXT_FOR_RECIPIENT_BYTES,
    )
    if not decoded_response:
        raise ProxyRequestError("AWS KMS returned an empty Recipient ciphertext.")
    return base64.b64encode(decoded_response).decode("ascii")


def _read_frame(connection: socket.socket) -> bytes:
    header = _recv_exact(connection, 4)
    length = struct.unpack("!I", header)[0]
    if length < 1 or length > MAX_REQUEST_FRAME_BYTES:
        raise ProxyRequestError("Request frame length is invalid.")
    return _recv_exact(connection, length)


def _write_frame(connection: socket.socket, response: dict[str, Any]) -> None:
    payload = json.dumps(response, separators=(",", ":")).encode("utf-8")
    if not payload or len(payload) > MAX_RESPONSE_FRAME_BYTES:
        raise ProxyRequestError("Response frame exceeds its size bound.")
    connection.sendall(struct.pack("!I", len(payload)) + payload)


def _handle_connection(
    connection: socket.socket,
    expected_key_id: str,
    region: str,
) -> None:
    connection.settimeout(SOCKET_TIMEOUT_SECONDS)
    try:
        kms_request = _parse_request(_read_frame(connection), expected_key_id)
        ciphertext_for_recipient = _decrypt_with_aws_cli(kms_request, region)
        _write_frame(
            connection,
            {
                "version": 1,
                "response": {"CiphertextForRecipient": ciphertext_for_recipient},
            },
        )
        logging.info("Completed KMS Recipient Decrypt over vsock.")
    except (ProxyRequestError, OSError, TimeoutError) as error:
        logging.warning("Rejected or failed Nitro KMS proxy request: %s", error)
        try:
            _write_frame(connection, {"version": 1, "response": {}})
        except (OSError, ProxyRequestError):
            return


def serve() -> None:
    key_id = os.environ.get("AWS_NITRO_KMS_KEY_ID", "").strip()
    region = os.environ.get("AWS_DEFAULT_REGION", "").strip()
    port_value = os.environ.get("AWS_NITRO_KMS_VSOCK_PORT", "8000")
    if not key_id or len(key_id) > MAX_KEY_ID_LENGTH:
        raise SystemExit("AWS_NITRO_KMS_KEY_ID must be configured.")
    if not region:
        raise SystemExit("AWS_DEFAULT_REGION must be configured.")
    try:
        port = int(port_value)
    except ValueError as error:
        raise SystemExit("AWS_NITRO_KMS_VSOCK_PORT must be an integer.") from error
    if not 1 <= port <= 65535:
        raise SystemExit("AWS_NITRO_KMS_VSOCK_PORT must be between 1 and 65535.")
    if not hasattr(socket, "AF_VSOCK") or not hasattr(socket, "VMADDR_CID_ANY"):
        raise SystemExit("The host Python runtime does not support AF_VSOCK.")

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM) as server:
        server.bind((socket.VMADDR_CID_ANY, port))
        server.listen(8)
        logging.info("Listening for bounded KMS Recipient requests on vsock port %s.", port)
        while True:
            connection, _ = server.accept()
            with connection:
                _handle_connection(connection, key_id, region)


if __name__ == "__main__":
    serve()
