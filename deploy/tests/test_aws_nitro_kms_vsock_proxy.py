import base64
import json
import unittest
from unittest.mock import Mock, patch

from deploy import aws_nitro_kms_vsock_proxy as proxy


def encoded(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def request_payload(key_id: str = "test-key") -> bytes:
    return json.dumps(
        {
            "version": 1,
            "operation": "kms:Decrypt",
            "request": {
                "KeyId": key_id,
                "CiphertextBlob": encoded(b"encrypted-dek"),
                "Recipient": {
                    "AttestationDocument": encoded(b"attestation"),
                    "KeyEncryptionAlgorithm": proxy.KEY_ENCRYPTION_ALGORITHM,
                },
            },
        }
    ).encode("utf-8")


class NitroKmsVsockProxyTests(unittest.TestCase):
    def test_accepts_only_configured_kms_key_and_recipient_cipher(self) -> None:
        parsed = proxy._parse_request(request_payload(), "test-key")
        self.assertEqual(parsed["KeyId"], "test-key")
        self.assertEqual(
            base64.b64decode(parsed["CiphertextBlob"], validate=True),
            b"encrypted-dek",
        )

    def test_rejects_unconfigured_key_id(self) -> None:
        with self.assertRaises(proxy.ProxyRequestError):
            proxy._parse_request(request_payload("another-key"), "test-key")

    def test_rejects_unsupported_kms_operation(self) -> None:
        payload = json.loads(request_payload())
        payload["operation"] = "kms:Encrypt"
        with self.assertRaises(proxy.ProxyRequestError):
            proxy._parse_request(json.dumps(payload).encode("utf-8"), "test-key")

    def test_reports_oversized_field_length_without_including_field_content(self) -> None:
        secret_value = b"recipient-ciphertext" * 20
        with self.assertRaises(proxy.ProxyRequestError) as error:
            proxy._decode_base64(encoded(secret_value), "CiphertextForRecipient", 256)

        self.assertIn(f"{len(secret_value)} > 256 bytes", str(error.exception))
        self.assertNotIn(encoded(secret_value), str(error.exception))

    def test_returns_only_ciphertext_for_recipient(self) -> None:
        request = proxy._parse_request(request_payload(), "test-key")
        recipient_ciphertext = bytes(range(256)) + bytes(range(237))
        aws_response = json.dumps(
            {"CiphertextForRecipient": encoded(recipient_ciphertext)}
        )
        with patch.object(
            proxy.subprocess,
            "run",
            return_value=proxy.subprocess.CompletedProcess(
                args=["aws"], returncode=0, stdout=aws_response, stderr=""
            ),
        ):
            result = proxy._decrypt_with_aws_cli(request, "eu-north-1")
        self.assertEqual(
            base64.b64decode(result, validate=True),
            recipient_ciphertext,
        )

    def test_logs_success_without_sensitive_request_data(self) -> None:
        connection = Mock()
        with (
            patch.object(proxy, "_read_frame", return_value=request_payload()),
            patch.object(
                proxy,
                "_decrypt_with_aws_cli",
                return_value=encoded(b"recipient-ciphertext"),
            ),
            self.assertLogs(level="INFO") as captured,
        ):
            proxy._handle_connection(connection, "test-key", "eu-north-1")

        self.assertEqual(
            captured.output,
            ["INFO:root:Completed KMS Recipient Decrypt over vsock."],
        )


if __name__ == "__main__":
    unittest.main()
