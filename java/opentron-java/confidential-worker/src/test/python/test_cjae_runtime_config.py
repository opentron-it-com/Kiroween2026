import base64
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(
    0,
    str(Path(__file__).resolve().parents[2] / "main" / "docker"),
)

from cjae_runtime_config import (
    CONFIG_KEYS,
    OPENSSL_PATH,
    SignedRuntimeConfigError,
    create_signed_runtime_config,
    verify_signed_runtime_config,
)


class SignedRuntimeConfigTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("openssl") and not Path("/usr/bin/openssl").exists():
            raise unittest.SkipTest("OpenSSL is unavailable on this host.")
        cls.temporary_directory = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temporary_directory.name)
        cls.private_key = cls.directory / "private.pem"
        cls.public_key = cls.directory / "public.pem"
        subprocess.run(
            [
                OPENSSL_PATH,
                "genpkey",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:3072",
                "-out",
                str(cls.private_key),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        with cls.public_key.open("wb") as output:
            subprocess.run(
                [
                    OPENSSL_PATH,
                    "pkey",
                    "-in",
                    str(cls.private_key),
                    "-pubout",
                ],
                check=True,
                stdout=output,
                stderr=subprocess.DEVNULL,
            )

    @classmethod
    def tearDownClass(cls):
        cls.temporary_directory.cleanup()

    def setUp(self):
        digest = "a" * 64
        self.environment = {
            "SPRING_PROFILES_ACTIVE": "gcp-cvm,gateway-oidc,gateway-tls,onnx-pii",
            "CONFIDENTIAL_GATEWAY_ENABLED": "true",
            "GATEWAY_OIDC_ISSUER": "https://issuer.example/",
            "GATEWAY_OIDC_JWKS_URI": "https://issuer.example/jwks",
            "GATEWAY_OIDC_AUDIENCE": "cjae-test",
            "GATEWAY_OIDC_TENANT_CLAIM": "tenant_id",
            "GCP_GCA_AUDIENCE": "https://gca.example/audience",
            "GCP_CVM_HARDWARE_MODEL": "AMD_SEV",
            "GCP_WIF_PROVIDER_AUDIENCE": "//iam.googleapis.com/projects/123/providers/cjae",
            "GCP_KMS_KEY_RESOURCE": (
                "projects/test/locations/global/keyRings/runtime/cryptoKeys/context"
            ),
            "GCP_CONTEXT_KEY_ID": "context-key",
            "GCP_CONTEXT_DEK_CIPHERTEXT_BASE64": base64.b64encode(b"ciphertext").decode(),
            "GCP_CVM_ALLOWED_PROJECTS": "test",
            "GCP_CVM_ALLOWED_ZONES": "us-central1-a",
            "GCP_CVM_ALLOWED_INSTANCES": "cjae-gateway",
            "GCP_CONFIDENTIAL_SPACE_IMAGE_DIGEST": "sha256:" + digest,
            "CJAE_WORKER_IMAGE_DIGEST": digest,
            "GCP_TLS_KEYSTORE_SECRET_VERSION": "projects/test/secrets/tls/versions/1",
            "GCP_TLS_KEYSTORE_PASSWORD_SECRET_VERSION": (
                "projects/test/secrets/tls-password/versions/1"
            ),
            "GATEWAY_REPLAY_POSTGRES_HOST": "postgres.internal.example",
            "GATEWAY_REPLAY_POSTGRES_PORT": "5432",
            "GATEWAY_REPLAY_POSTGRES_DATABASE": "cjae_gateway",
            "GATEWAY_REPLAY_POSTGRES_USER_SECRET_VERSION": (
                "projects/test/secrets/db-user/versions/1"
            ),
            "GATEWAY_REPLAY_POSTGRES_PASSWORD_SECRET_VERSION": (
                "projects/test/secrets/db-password/versions/1"
            ),
            "GATEWAY_REPLAY_POSTGRES_CA_SECRET_VERSION": (
                "projects/test/secrets/db-ca/versions/1"
            ),
            "GATEWAY_TLS_PORT": "8443",
            "CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES": "5",
        }
        self.assertEqual(set(CONFIG_KEYS), set(self.environment))

    def test_signs_and_verifies_canonical_runtime_configuration(self):
        encoded_config, encoded_signature = create_signed_runtime_config(
            self.environment, str(self.private_key)
        )

        verified = verify_signed_runtime_config(
            encoded_config, encoded_signature, str(self.public_key)
        )

        self.assertEqual(self.environment, verified)

    def test_rejects_tampered_or_incomplete_configuration(self):
        encoded_config, encoded_signature = create_signed_runtime_config(
            self.environment, str(self.private_key)
        )
        tampered_config = base64.b64decode(encoded_config).replace(
            b"cjae-test", b"attacker"
        )

        with self.assertRaises(SignedRuntimeConfigError):
            verify_signed_runtime_config(
                base64.b64encode(tampered_config).decode(),
                encoded_signature,
                str(self.public_key),
            )
        with self.assertRaises(SignedRuntimeConfigError):
            create_signed_runtime_config(
                {key: value for key, value in self.environment.items() if key != "GCP_KMS_KEY_RESOURCE"},
                str(self.private_key),
            )


if __name__ == "__main__":
    unittest.main()
