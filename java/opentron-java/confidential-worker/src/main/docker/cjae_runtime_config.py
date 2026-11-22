#!/usr/bin/python3
import argparse
import base64
import binascii
import json
import os
import re
import shutil
import subprocess
import tempfile


CONFIG_KEYS = (
    "SPRING_PROFILES_ACTIVE",
    "CONFIDENTIAL_GATEWAY_ENABLED",
    "GATEWAY_OIDC_ISSUER",
    "GATEWAY_OIDC_JWKS_URI",
    "GATEWAY_OIDC_AUDIENCE",
    "GATEWAY_OIDC_TENANT_CLAIM",
    "GCP_GCA_AUDIENCE",
    "GCP_CVM_HARDWARE_MODEL",
    "GCP_WIF_PROVIDER_AUDIENCE",
    "GCP_KMS_KEY_RESOURCE",
    "GCP_CONTEXT_KEY_ID",
    "GCP_CONTEXT_DEK_CIPHERTEXT_BASE64",
    "GCP_CVM_ALLOWED_PROJECTS",
    "GCP_CVM_ALLOWED_ZONES",
    "GCP_CVM_ALLOWED_INSTANCES",
    "GCP_CONFIDENTIAL_SPACE_IMAGE_DIGEST",
    "CJAE_WORKER_IMAGE_DIGEST",
    "GCP_TLS_KEYSTORE_SECRET_VERSION",
    "GCP_TLS_KEYSTORE_PASSWORD_SECRET_VERSION",
    "GATEWAY_REPLAY_POSTGRES_HOST",
    "GATEWAY_REPLAY_POSTGRES_PORT",
    "GATEWAY_REPLAY_POSTGRES_DATABASE",
    "GATEWAY_REPLAY_POSTGRES_USER_SECRET_VERSION",
    "GATEWAY_REPLAY_POSTGRES_PASSWORD_SECRET_VERSION",
    "GATEWAY_REPLAY_POSTGRES_CA_SECRET_VERSION",
    "GATEWAY_TLS_PORT",
    "CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES",
)
MAX_CONFIG_BYTES = 32768
MAX_SIGNATURE_BYTES = 1024
PUBLIC_KEY_PATH = "/opt/cjae/config-signing-public.pem"
OPENSSL_PATH = shutil.which("openssl") or "/usr/bin/openssl"


class SignedRuntimeConfigError(Exception):
    pass


def _validate_environment(environment):
    if not isinstance(environment, dict) or set(environment) != set(CONFIG_KEYS):
        raise SignedRuntimeConfigError(
            "Signed runtime configuration has missing or unsupported fields."
        )
    for name, value in environment.items():
        if (
            not isinstance(value, str)
            or not value
            or len(value) > 8192
            or any(ord(character) < 32 or ord(character) == 127 for character in value)
            or "~" in value
        ):
            raise SignedRuntimeConfigError(
                "Signed runtime configuration contains an invalid value."
            )

    if (
        environment["CONFIDENTIAL_GATEWAY_ENABLED"] != "true"
        or set(environment["SPRING_PROFILES_ACTIVE"].split(","))
        != {"gcp-cvm", "gateway-oidc", "gateway-tls", "onnx-pii"}
    ):
        raise SignedRuntimeConfigError(
            "Signed runtime configuration does not select the approved gateway profiles."
        )
    for name in ("GATEWAY_OIDC_ISSUER", "GATEWAY_OIDC_JWKS_URI"):
        if not re.fullmatch(r"https://[^\s]+", environment[name]):
            raise SignedRuntimeConfigError("Signed OIDC endpoints must use HTTPS.")
    if (
        re.search(r"\s", environment["GATEWAY_OIDC_AUDIENCE"])
        or not environment["GATEWAY_OIDC_AUDIENCE"]
        or environment["GATEWAY_OIDC_TENANT_CLAIM"]
        not in {"tenant_id", "sub"}
    ):
        raise SignedRuntimeConfigError("Signed OIDC policy is invalid.")
    if environment["GCP_CVM_HARDWARE_MODEL"] != "AMD_SEV":
        raise SignedRuntimeConfigError("Signed GCP hardware policy is unsupported.")
    if not re.fullmatch(
        r"sha256:[A-Fa-f0-9]{64}", environment["GCP_CONFIDENTIAL_SPACE_IMAGE_DIGEST"]
    ) or environment["CJAE_WORKER_IMAGE_DIGEST"].lower() != environment[
        "GCP_CONFIDENTIAL_SPACE_IMAGE_DIGEST"
    ][7:].lower():
        raise SignedRuntimeConfigError("Signed worker image digests do not match.")
    if not re.fullmatch(
        r"projects/[A-Za-z0-9-]+/locations/[A-Za-z0-9-]+/"
        r"keyRings/[A-Za-z0-9_-]+/cryptoKeys/[A-Za-z0-9_-]+",
        environment["GCP_KMS_KEY_RESOURCE"],
    ):
        raise SignedRuntimeConfigError("Signed KMS key resource is invalid.")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", environment["GCP_CONTEXT_KEY_ID"]):
        raise SignedRuntimeConfigError("Signed context key ID is invalid.")
    try:
        dek_ciphertext = base64.b64decode(
            environment["GCP_CONTEXT_DEK_CIPHERTEXT_BASE64"], validate=True
        )
    except (binascii.Error, ValueError):
        raise SignedRuntimeConfigError("Signed encrypted context key is invalid.") from None
    if not dek_ciphertext or len(dek_ciphertext) > 8192:
        raise SignedRuntimeConfigError("Signed encrypted context key has an invalid size.")

    secret_version = re.compile(
        r"projects/[A-Za-z0-9_-]+/secrets/[A-Za-z0-9_-]+/versions/[1-9][0-9]*"
    )
    for name in (
        "GCP_TLS_KEYSTORE_SECRET_VERSION",
        "GCP_TLS_KEYSTORE_PASSWORD_SECRET_VERSION",
        "GATEWAY_REPLAY_POSTGRES_USER_SECRET_VERSION",
        "GATEWAY_REPLAY_POSTGRES_PASSWORD_SECRET_VERSION",
        "GATEWAY_REPLAY_POSTGRES_CA_SECRET_VERSION",
    ):
        if not secret_version.fullmatch(environment[name]):
            raise SignedRuntimeConfigError(
                "Signed configuration requires pinned Secret Manager versions."
            )
    if not re.fullmatch(r"[A-Za-z0-9.-]{1,253}", environment["GATEWAY_REPLAY_POSTGRES_HOST"]):
        raise SignedRuntimeConfigError("Signed replay-store host is invalid.")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,63}", environment["GATEWAY_REPLAY_POSTGRES_DATABASE"]):
        raise SignedRuntimeConfigError("Signed replay-store database is invalid.")
    for name, minimum, maximum in (
        ("GATEWAY_REPLAY_POSTGRES_PORT", 1, 65535),
        ("GATEWAY_TLS_PORT", 1, 65535),
        ("CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES", 1, 15),
    ):
        value = environment[name]
        if not value.isascii() or not value.isdecimal() or not minimum <= int(value) <= maximum:
            raise SignedRuntimeConfigError("Signed numeric runtime configuration is invalid.")


def _canonical_config(environment):
    _validate_environment(environment)
    return json.dumps(
        {"environment": environment, "version": 1},
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
    ).encode("ascii")


def _decode_base64(value, limit, label):
    if not isinstance(value, str) or len(value) > limit * 2:
        raise SignedRuntimeConfigError(label + " is missing or oversized.")
    try:
        decoded = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError):
        raise SignedRuntimeConfigError(label + " is malformed.") from None
    if not decoded or len(decoded) > limit:
        raise SignedRuntimeConfigError(label + " has an invalid size.")
    return decoded


def _verify_signature(config_bytes, signature, public_key_path):
    if not os.path.isfile(public_key_path):
        raise SignedRuntimeConfigError("The image has no runtime-config verification key.")
    try:
        subprocess.run(
            [OPENSSL_PATH, "rsa", "-pubin", "-in", public_key_path, "-noout"],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=5,
        )
        key_details = subprocess.run(
            [OPENSSL_PATH, "pkey", "-pubin", "-in", public_key_path, "-text", "-noout"],
            check=True,
            capture_output=True,
            timeout=5,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        raise SignedRuntimeConfigError(
            "The runtime-config verification key is invalid."
        ) from None
    key_size = re.search(rb"Public-Key:\s+\((\d+) bit\)", key_details)
    if key_size is None or int(key_size.group(1)) < 3072:
        raise SignedRuntimeConfigError(
            "The runtime-config verification key must be RSA 3072-bit or stronger."
        )
    temporary_root = "/dev/shm" if os.path.isdir("/dev/shm") else None
    with tempfile.TemporaryDirectory(
        prefix="cjae-config-", dir=temporary_root
    ) as directory:
        config_path = os.path.join(directory, "runtime-config.json")
        signature_path = os.path.join(directory, "runtime-config.sig")
        for path, content in ((config_path, config_bytes), (signature_path, signature)):
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
        try:
            subprocess.run(
                [
                    OPENSSL_PATH,
                    "dgst",
                    "-sha256",
                    "-verify",
                    public_key_path,
                    "-signature",
                    signature_path,
                    config_path,
                ],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=5,
            )
        except (OSError, subprocess.SubprocessError):
            raise SignedRuntimeConfigError(
                "Signed runtime configuration could not be verified."
            ) from None


def verify_signed_runtime_config(encoded_config, encoded_signature, public_key_path):
    config_bytes = _decode_base64(encoded_config, MAX_CONFIG_BYTES, "Runtime configuration")
    signature = _decode_base64(encoded_signature, MAX_SIGNATURE_BYTES, "Runtime signature")
    _verify_signature(config_bytes, signature, public_key_path)
    try:
        config = json.loads(config_bytes.decode("ascii"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise SignedRuntimeConfigError("Signed runtime configuration is invalid JSON.") from None
    if (
        not isinstance(config, dict)
        or set(config) != {"environment", "version"}
        or config["version"] != 1
        or _canonical_config(config["environment"]) != config_bytes
    ):
        raise SignedRuntimeConfigError("Signed runtime configuration is not canonical.")
    return config["environment"]


def create_signed_runtime_config(environment, private_key_path):
    config_bytes = _canonical_config(
        {name: environment.get(name) for name in CONFIG_KEYS}
    )
    with tempfile.TemporaryDirectory(prefix="cjae-config-sign-") as directory:
        config_path = os.path.join(directory, "runtime-config.json")
        signature_path = os.path.join(directory, "runtime-config.sig")
        with open(config_path, "wb") as stream:
            stream.write(config_bytes)
        try:
            subprocess.run(
                [
                    OPENSSL_PATH,
                    "dgst",
                    "-sha256",
                    "-sign",
                    private_key_path,
                    "-out",
                    signature_path,
                    config_path,
                ],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=10,
            )
        except (OSError, subprocess.SubprocessError):
            raise SignedRuntimeConfigError(
                "Runtime configuration could not be signed."
            ) from None
        with open(signature_path, "rb") as stream:
            signature = stream.read(MAX_SIGNATURE_BYTES + 1)
    if not signature or len(signature) > MAX_SIGNATURE_BYTES:
        raise SignedRuntimeConfigError("Runtime configuration signature has an invalid size.")
    return (
        base64.b64encode(config_bytes).decode("ascii"),
        base64.b64encode(signature).decode("ascii"),
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sign-with")
    parser.add_argument("--verify-with")
    arguments = parser.parse_args()
    if not arguments.sign_with or not arguments.verify_with:
        parser.error("--sign-with and --verify-with are required.")
    try:
        config, signature = create_signed_runtime_config(os.environ, arguments.sign_with)
        verify_signed_runtime_config(config, signature, arguments.verify_with)
    except SignedRuntimeConfigError as failure:
        parser.error(str(failure))
    print(config + "\t" + signature)


if __name__ == "__main__":
    main()
