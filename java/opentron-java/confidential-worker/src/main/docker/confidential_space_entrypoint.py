#!/usr/bin/python3
import base64
import binascii
import json
import os
import re
import ssl
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request

from cjae_runtime_config import (
    PUBLIC_KEY_PATH,
    SignedRuntimeConfigError,
    verify_signed_runtime_config,
)


METADATA_TOKEN_URL = (
    "http://metadata.google.internal/computeMetadata/v1/"
    "instance/service-accounts/default/token"
)
SECRET_MANAGER_URL = "https://secretmanager.googleapis.com/v1/"
MAX_HTTP_BYTES = 262144
MAX_SECRET_BYTES = 131072
SECRET_VERSION = re.compile(
    r"projects/[A-Za-z0-9_-]+/secrets/[A-Za-z0-9_-]+/versions/[1-9][0-9]*"
)
DATABASE_HOST_LABEL = re.compile(
    r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
)
DATABASE_NAME = re.compile(r"[A-Za-z0-9_-]{1,63}")
DATABASE_USER = re.compile(r"[A-Za-z0-9_.@-]{1,128}")
TLS_PASSWORD = re.compile(r"[!-~]{1,128}")


class BootstrapError(Exception):
    pass


def read_limited(response, limit):
    body = response.read(limit + 1)
    if len(body) > limit:
        raise BootstrapError("A Secret Manager response exceeded the size limit.")
    return body


def http_json(opener, request, description, required_header=None):
    try:
        with opener.open(request, timeout=5) as response:
            if (
                required_header is not None
                and response.headers.get(required_header[0]) != required_header[1]
            ):
                raise BootstrapError(description)
            body = read_limited(response, MAX_HTTP_BYTES)
    except (urllib.error.URLError, TimeoutError, OSError) as failure:
        raise BootstrapError(description) from None
    try:
        value = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise BootstrapError(description) from None
    if not isinstance(value, dict):
        raise BootstrapError(description)
    return value


def secret_version(name):
    value = os.environ.get(name, "")
    if not SECRET_VERSION.fullmatch(value):
        raise BootstrapError("A pinned Secret Manager version is required.")
    return value


def fetch_secret(opener, token, resource):
    encoded_resource = urllib.parse.quote(resource, safe="/")
    request = urllib.request.Request(
        SECRET_MANAGER_URL + encoded_resource + ":access",
        headers={"Authorization": "Bearer " + token, "Accept": "application/json"},
        method="GET",
    )
    response = http_json(
        opener, request, "A pinned Secret Manager version could not be read."
    )
    if response.get("name") != resource:
        raise BootstrapError("Secret Manager returned a different secret version.")
    payload = response.get("payload")
    encoded_data = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(encoded_data, str):
        raise BootstrapError("A pinned Secret Manager version has no payload.")
    try:
        data = base64.b64decode(encoded_data, validate=True)
    except (binascii.Error, ValueError):
        raise BootstrapError("A Secret Manager payload is malformed.") from None
    if not data or len(data) > MAX_SECRET_BYTES:
        raise BootstrapError("A Secret Manager payload has an invalid size.")
    return bytearray(data)


def metadata_token(opener):
    request = urllib.request.Request(
        METADATA_TOKEN_URL,
        headers={"Metadata-Flavor": "Google", "Accept": "application/json"},
        method="GET",
    )
    response = http_json(
        opener,
        request,
        "The Confidential Space service identity is unavailable.",
        required_header=("Metadata-Flavor", "Google"),
    )
    token = response.get("access_token")
    if not isinstance(token, str) or not token or len(token) > 8192:
        raise BootstrapError("The Confidential Space service identity is invalid.")
    return token


def write_private_file(path, content):
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    flags |= getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
    except OSError:
        raise BootstrapError("A runtime secret could not be placed in ephemeral storage.") from None


def decode_text(data, description):
    try:
        value = bytes(data).decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        raise BootstrapError(description) from None
    if not value or "\0" in value:
        raise BootstrapError(description)
    return value


def fetch_gateway_secrets():
    profiles = {
        profile.strip()
        for profile in os.environ.get("SPRING_PROFILES_ACTIVE", "").split(",")
    }
    if (
        "gcp-cvm" not in profiles
        or "gateway-oidc" not in profiles
        or os.environ.get("CONFIDENTIAL_GATEWAY_ENABLED") != "true"
    ):
        return

    expected_digest = os.environ.get("GCP_CONFIDENTIAL_SPACE_IMAGE_DIGEST", "")
    image_digest = os.environ.get("CJAE_WORKER_IMAGE_DIGEST", "")
    if (
        not re.fullmatch(r"sha256:[A-Fa-f0-9]{64}", expected_digest)
        or image_digest.lower() != expected_digest.removeprefix("sha256:").lower()
    ):
        raise BootstrapError("The configured Confidential Space image digest is invalid.")

    try:
        try:
            os.mkdir("/dev/shm/cjae", 0o700)
        except FileExistsError:
            pass
        directory = os.lstat("/dev/shm/cjae")
        if not stat.S_ISDIR(directory.st_mode) or directory.st_uid != os.geteuid():
            raise OSError("invalid ephemeral directory")
        os.chmod("/dev/shm/cjae", 0o700)
    except OSError:
        raise BootstrapError("The ephemeral secret directory is unavailable.") from None

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = metadata_token(opener)
    secret_names = []
    if "gateway-tls" in profiles:
        secret_names.extend(
            [
                "GCP_TLS_KEYSTORE_SECRET_VERSION",
                "GCP_TLS_KEYSTORE_PASSWORD_SECRET_VERSION",
            ]
        )
    secret_names.extend(
        [
            "GATEWAY_REPLAY_POSTGRES_USER_SECRET_VERSION",
            "GATEWAY_REPLAY_POSTGRES_PASSWORD_SECRET_VERSION",
            "GATEWAY_REPLAY_POSTGRES_CA_SECRET_VERSION",
        ]
    )

    secrets = {}
    try:
        for name in secret_names:
            secrets[name] = fetch_secret(
                opener, token, secret_version(name)
            )
        token = ""
        if "gateway-tls" in profiles:
            keystore = secrets["GCP_TLS_KEYSTORE_SECRET_VERSION"]
            password = decode_text(
                secrets["GCP_TLS_KEYSTORE_PASSWORD_SECRET_VERSION"],
                "The TLS keystore password is invalid.",
            )
            if not TLS_PASSWORD.fullmatch(password):
                raise BootstrapError("The TLS keystore password is invalid.")
            keystore_path = "/dev/shm/cjae/gateway.p12"
            write_private_file(keystore_path, keystore)
            os.environ["GATEWAY_TLS_KEYSTORE"] = "file:" + keystore_path
            os.environ["GATEWAY_TLS_KEYSTORE_PASSWORD"] = password
            password = ""

        username = decode_text(
            secrets["GATEWAY_REPLAY_POSTGRES_USER_SECRET_VERSION"],
            "The PostgreSQL replay-store username is invalid.",
        )
        password = decode_text(
            secrets["GATEWAY_REPLAY_POSTGRES_PASSWORD_SECRET_VERSION"],
            "The PostgreSQL replay-store password is invalid.",
        )
        if not DATABASE_USER.fullmatch(username) or len(password) > 1024:
            raise BootstrapError("The PostgreSQL replay-store credentials are invalid.")

        host = os.environ.get("GATEWAY_REPLAY_POSTGRES_HOST", "")
        database = os.environ.get("GATEWAY_REPLAY_POSTGRES_DATABASE", "")
        port = os.environ.get("GATEWAY_REPLAY_POSTGRES_PORT", "5432")
        if (
            len(host) > 253
            or not host
            or any(not DATABASE_HOST_LABEL.fullmatch(label) for label in host.split("."))
            or not DATABASE_NAME.fullmatch(database)
            or not port.isascii()
            or not port.isdecimal()
            or not 1 <= int(port) <= 65535
        ):
            raise BootstrapError("The PostgreSQL replay-store endpoint is invalid.")

        ca_certificate = secrets["GATEWAY_REPLAY_POSTGRES_CA_SECRET_VERSION"]
        ca_text = decode_text(
            ca_certificate, "The PostgreSQL replay-store CA certificate is invalid."
        )
        certificate_blocks = re.findall(
            r"-----BEGIN CERTIFICATE-----\s+.*?\s+-----END CERTIFICATE-----",
            ca_text,
            re.DOTALL,
        )
        remainder = re.sub(
            r"-----BEGIN CERTIFICATE-----\s+.*?\s+-----END CERTIFICATE-----",
            "",
            ca_text,
            flags=re.DOTALL,
        )
        try:
            for certificate in certificate_blocks:
                ssl.PEM_cert_to_DER_cert(certificate)
        except (ValueError, binascii.Error):
            raise BootstrapError(
                "The PostgreSQL replay-store CA certificate is invalid."
            ) from None
        if not certificate_blocks or remainder.strip():
            raise BootstrapError("The PostgreSQL replay-store CA certificate is invalid.")
        ca_path = "/dev/shm/cjae/replay-store-ca.pem"
        write_private_file(ca_path, ca_certificate)

        os.environ["GATEWAY_REPLAY_POSTGRES_URL"] = (
            f"jdbc:postgresql://{host}:{port}/{database}"
            f"?sslmode=verify-full&sslrootcert={ca_path}"
        )
        os.environ["GATEWAY_REPLAY_POSTGRES_USER"] = username
        os.environ["GATEWAY_REPLAY_POSTGRES_PASSWORD"] = password
        password = ""
    finally:
        token = ""
        for value in secrets.values():
            value[:] = b"\0" * len(value)


def apply_signed_runtime_config():
    profiles = {
        profile.strip()
        for profile in os.environ.get("SPRING_PROFILES_ACTIVE", "").split(",")
    }
    if (
        "gcp-cvm" not in profiles
        or os.environ.get("CONFIDENTIAL_GATEWAY_ENABLED") != "true"
    ):
        return
    try:
        environment = verify_signed_runtime_config(
            os.environ.get("CJAE_RUNTIME_CONFIG_BASE64", ""),
            os.environ.get("CJAE_RUNTIME_CONFIG_SIGNATURE_BASE64", ""),
            PUBLIC_KEY_PATH,
        )
    except SignedRuntimeConfigError:
        raise BootstrapError(
            "Signed Confidential Space runtime configuration is invalid."
        ) from None
    os.environ.update(environment)


def main():
    try:
        apply_signed_runtime_config()
        fetch_gateway_secrets()
    except BootstrapError as failure:
        print(
            "Confidential Space runtime bootstrap failed: " + str(failure),
            file=sys.stderr,
        )
        return 1

    java = "/opt/java/openjdk/bin/java"
    os.execv(java, [java, "-jar", "/opt/cjae/app.jar", *sys.argv[1:]])
    return 127


if __name__ == "__main__":
    sys.exit(main())
