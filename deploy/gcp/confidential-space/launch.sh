#!/usr/bin/env bash
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-cjae-confidential-engine}"
INSTANCE_NAME="${INSTANCE_NAME:-cjae-confidential-worker}"
ZONE="${ZONE:-us-central1-c}"
MACHINE_TYPE="${MACHINE_TYPE:-n2d-standard-4}"
IMAGE_URI="${IMAGE_URI:-us-central1-docker.pkg.dev/cjae-confidential-engine/cjae-repo/cjae-worker}"
NETWORK="${NETWORK:-default}"
SUBNET="${SUBNET:-default}"
DISK_SIZE="${DISK_SIZE:-100GB}"
NETWORK_TAG="${NETWORK_TAG:-cjae-confidential-gateway}"
PUBLIC_IP="${PUBLIC_IP:-}"
WORKER_IMAGE_DIGEST="${WORKER_IMAGE_DIGEST:-}"
SERVICE_ACCOUNT="${SERVICE_ACCOUNT:-}"
GATEWAY_REPLAY_POSTGRES_PORT="${GATEWAY_REPLAY_POSTGRES_PORT:-5432}"

required_variables=(
  GATEWAY_OIDC_ISSUER
  GATEWAY_OIDC_JWKS_URI
  GATEWAY_OIDC_AUDIENCE
  GATEWAY_OIDC_TENANT_CLAIM
  GCP_GCA_AUDIENCE
  GCP_WIF_PROVIDER_AUDIENCE
  GCP_KMS_KEY_RESOURCE
  GCP_CONTEXT_KEY_ID
  GCP_CONTEXT_DEK_CIPHERTEXT_BASE64
  GATEWAY_REPLAY_POSTGRES_HOST
  GATEWAY_REPLAY_POSTGRES_DATABASE
  GCP_TLS_KEYSTORE_SECRET_VERSION
  GCP_TLS_KEYSTORE_PASSWORD_SECRET_VERSION
  GATEWAY_REPLAY_POSTGRES_USER_SECRET_VERSION
  GATEWAY_REPLAY_POSTGRES_PASSWORD_SECRET_VERSION
  GATEWAY_REPLAY_POSTGRES_CA_SECRET_VERSION
  CJAE_RUNTIME_CONFIG_PRIVATE_KEY_PATH
  CJAE_CONFIG_SIGNING_PUBLIC_KEY_PATH
)

for name in "${required_variables[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    printf 'Required environment variable %s is missing.\n' "$name" >&2
    exit 2
  fi
  if [[ "${!name}" == *'~'* || "${!name}" == *$'\n'* || "${!name}" == *$'\r'* ]]; then
    printf 'Environment variable %s contains a reserved metadata delimiter.\n' "$name" >&2
    exit 2
  fi
done

if [[ ! -f "${CJAE_RUNTIME_CONFIG_PRIVATE_KEY_PATH}" \
    || ! -f "${CJAE_CONFIG_SIGNING_PUBLIC_KEY_PATH}" ]]; then
  echo "Runtime-config signing and verification key files must exist on the launcher host." >&2
  exit 2
fi

if [[ -z "${SERVICE_ACCOUNT}" ]]; then
  echo "Set SERVICE_ACCOUNT to the dedicated Confidential Space workload identity." >&2
  exit 2
fi
if [[ ! "${GATEWAY_OIDC_ISSUER}" =~ ^https://[^[:space:]]+$ \
    || ! "${GATEWAY_OIDC_JWKS_URI}" =~ ^https://[^[:space:]]+$ \
    || "${GATEWAY_OIDC_AUDIENCE}" =~ [[:space:]] ]]; then
  echo "OIDC issuer and JWKS URI must use HTTPS and the audience must not contain whitespace." >&2
  exit 2
fi
if [[ ! "${WORKER_IMAGE_DIGEST}" =~ ^[A-Fa-f0-9]{64}$ ]]; then
  echo "WORKER_IMAGE_DIGEST must be the exact 64-character SHA-256 image digest." >&2
  exit 2
fi
if [[ ! "${IMAGE_URI}" =~ ^[A-Za-z0-9-]+-docker\.pkg\.dev/[A-Za-z0-9._/-]+$ \
    || "${IMAGE_URI}" == *'..'* || "${IMAGE_URI}" == *'//'*
    || "${IMAGE_URI}" == */. || "${IMAGE_URI}" == */.. ]]; then
  echo "IMAGE_URI must be an Artifact Registry repository path without a tag or digest." >&2
  exit 2
fi
if [[ ! "${PROJECT_ID}" =~ ^[a-z][a-z0-9-]{4,28}[a-z0-9]$ ]]; then
  echo "PROJECT_ID is invalid." >&2
  exit 2
fi
if [[ ! "${ZONE}" =~ ^[a-z]+-[a-z]+[0-9]-[a-z]$ ]]; then
  echo "ZONE is invalid." >&2
  exit 2
fi
if [[ ! "${INSTANCE_NAME}" =~ ^[a-z]([-a-z0-9]{0,61}[a-z0-9])?$ ]]; then
  echo "INSTANCE_NAME is invalid." >&2
  exit 2
fi
if [[ ! "${MACHINE_TYPE}" =~ ^n2d-[a-z0-9-]+$ ]]; then
  echo "MACHINE_TYPE must be an AMD N2D type compatible with this SEV launcher." >&2
  exit 2
fi
if [[ ! "${SERVICE_ACCOUNT}" =~ ^[a-z][a-z0-9-]*@[A-Za-z0-9.-]+\.iam\.gserviceaccount\.com$ ]]; then
  echo "SERVICE_ACCOUNT must be a service account email." >&2
  exit 2
fi
if [[ ! "${GATEWAY_REPLAY_POSTGRES_PORT}" =~ ^[0-9]{1,5}$ ]] \
    || (( 10#${GATEWAY_REPLAY_POSTGRES_PORT} < 1 \
        || 10#${GATEWAY_REPLAY_POSTGRES_PORT} > 65535 )); then
  echo "GATEWAY_REPLAY_POSTGRES_PORT must be between 1 and 65535." >&2
  exit 2
fi

if [[ ! "${GATEWAY_REPLAY_POSTGRES_HOST}" =~ ^[A-Za-z0-9.-]+$ \
    || "${GATEWAY_REPLAY_POSTGRES_HOST}" == *'..'* \
    || "${GATEWAY_REPLAY_POSTGRES_HOST}" == .* \
    || "${GATEWAY_REPLAY_POSTGRES_HOST}" == *. \
    || "${GATEWAY_REPLAY_POSTGRES_HOST}" == *.-* \
    || "${GATEWAY_REPLAY_POSTGRES_HOST}" == -* ]]; then
  echo "GATEWAY_REPLAY_POSTGRES_HOST must be a DNS name or IPv4 address." >&2
  exit 2
fi
if [[ ! "${GATEWAY_REPLAY_POSTGRES_DATABASE}" =~ ^[A-Za-z0-9_-]{1,63}$ ]]; then
  echo "GATEWAY_REPLAY_POSTGRES_DATABASE is invalid." >&2
  exit 2
fi
if [[ ! "${GCP_KMS_KEY_RESOURCE}" =~ ^projects/[A-Za-z0-9-]+/locations/[A-Za-z0-9-]+/keyRings/[A-Za-z0-9_-]+/cryptoKeys/[A-Za-z0-9_-]+$ \
    || ! "${GCP_CONTEXT_KEY_ID}" =~ ^[A-Za-z0-9_-]{1,128}$ \
    || ! "${GCP_CONTEXT_DEK_CIPHERTEXT_BASE64}" =~ ^[A-Za-z0-9+/=_-]+$ ]]; then
  echo "The GCP KMS resource, context key ID, or encrypted DEK is invalid." >&2
  exit 2
fi

for name in \
  GCP_TLS_KEYSTORE_SECRET_VERSION \
  GCP_TLS_KEYSTORE_PASSWORD_SECRET_VERSION \
  GATEWAY_REPLAY_POSTGRES_USER_SECRET_VERSION \
  GATEWAY_REPLAY_POSTGRES_PASSWORD_SECRET_VERSION \
  GATEWAY_REPLAY_POSTGRES_CA_SECRET_VERSION; do
  if [[ ! "${!name}" =~ ^projects/[A-Za-z0-9_-]+/secrets/[A-Za-z0-9_-]+/versions/[1-9][0-9]*$ ]]; then
    printf '%s must identify a numeric, pinned Secret Manager version.\n' "$name" >&2
    exit 2
  fi
done

if [[ -n "${PUBLIC_IP}" && ! "${PUBLIC_IP}" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
  echo "PUBLIC_IP must be an IPv4 address or empty for a private-only VM." >&2
  exit 2
fi

IMAGE_DIGEST="sha256:${WORKER_IMAGE_DIGEST,,}"
IMAGE_REFERENCE="${IMAGE_URI}@${IMAGE_DIGEST}"
GCP_CVM_ALLOWED_PROJECTS="${PROJECT_ID}"
GCP_CVM_ALLOWED_ZONES="${ZONE}"
GCP_CVM_ALLOWED_INSTANCES="${INSTANCE_NAME}"
GCP_CVM_HARDWARE_MODEL=AMD_SEV
SPRING_PROFILES_ACTIVE=gcp-cvm,gateway-oidc,gateway-tls,onnx-pii
CONFIDENTIAL_GATEWAY_ENABLED=true
GATEWAY_TLS_PORT=8443
CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES="${CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES:-5}"
if [[ ! "${CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES}" =~ ^[0-9]{1,2}$ ]] \
    || (( 10#${CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES} < 1 \
        || 10#${CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES} > 15 )); then
  echo "CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES must be between 1 and 15." >&2
  exit 2
fi
GCP_CONFIDENTIAL_SPACE_IMAGE_DIGEST="${IMAGE_DIGEST}"
CJAE_WORKER_IMAGE_DIGEST="${WORKER_IMAGE_DIGEST,,}"

config_variables=(
  SPRING_PROFILES_ACTIVE
  CONFIDENTIAL_GATEWAY_ENABLED
  GATEWAY_OIDC_ISSUER
  GATEWAY_OIDC_JWKS_URI
  GATEWAY_OIDC_AUDIENCE
  GATEWAY_OIDC_TENANT_CLAIM
  GCP_GCA_AUDIENCE
  GCP_CVM_HARDWARE_MODEL
  GCP_WIF_PROVIDER_AUDIENCE
  GCP_KMS_KEY_RESOURCE
  GCP_CONTEXT_KEY_ID
  GCP_CONTEXT_DEK_CIPHERTEXT_BASE64
  GCP_CVM_ALLOWED_PROJECTS
  GCP_CVM_ALLOWED_ZONES
  GCP_CVM_ALLOWED_INSTANCES
  GCP_CONFIDENTIAL_SPACE_IMAGE_DIGEST
  CJAE_WORKER_IMAGE_DIGEST
  GCP_TLS_KEYSTORE_SECRET_VERSION
  GCP_TLS_KEYSTORE_PASSWORD_SECRET_VERSION
  GATEWAY_REPLAY_POSTGRES_HOST
  GATEWAY_REPLAY_POSTGRES_PORT
  GATEWAY_REPLAY_POSTGRES_DATABASE
  GATEWAY_REPLAY_POSTGRES_USER_SECRET_VERSION
  GATEWAY_REPLAY_POSTGRES_PASSWORD_SECRET_VERSION
  GATEWAY_REPLAY_POSTGRES_CA_SECRET_VERSION
  GATEWAY_TLS_PORT
  CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES
)
for name in "${config_variables[@]}"; do
  export "${name}"
done
signed_runtime_config="$(
  python3 java/opentron-java/confidential-worker/src/main/docker/cjae_runtime_config.py \
    --sign-with "${CJAE_RUNTIME_CONFIG_PRIVATE_KEY_PATH}" \
    --verify-with "${CJAE_CONFIG_SIGNING_PUBLIC_KEY_PATH}"
)"
IFS=$'\t' read -r CJAE_RUNTIME_CONFIG_BASE64 CJAE_RUNTIME_CONFIG_SIGNATURE_BASE64 \
  <<< "${signed_runtime_config}"
if [[ -z "${CJAE_RUNTIME_CONFIG_BASE64}" \
    || -z "${CJAE_RUNTIME_CONFIG_SIGNATURE_BASE64}" ]]; then
  echo "Unable to create the signed Confidential Space runtime configuration." >&2
  exit 2
fi

metadata="^~^tee-image-reference=${IMAGE_REFERENCE}"
metadata+="~tee-restart-policy=Always"
metadata+="~tee-container-log-redirect=false"

for name in CJAE_RUNTIME_CONFIG_BASE64 CJAE_RUNTIME_CONFIG_SIGNATURE_BASE64; do
  value="${!name}"
  if [[ "${value}" == *'~'* || "${value}" == *$'\n'* || "${value}" == *$'\r'* ]]; then
    printf 'Environment variable %s contains a reserved metadata delimiter.\n' "$name" >&2
    exit 2
  fi
  metadata+="~tee-env-${name}=${value}"
done

network_interface="network=${NETWORK},subnet=${SUBNET}"
if [[ -n "${PUBLIC_IP}" ]]; then
  network_interface+=",address=${PUBLIC_IP}"
else
  network_interface+=",no-address"
fi

gcloud compute instances create "${INSTANCE_NAME}" \
  --project="${PROJECT_ID}" \
  --zone="${ZONE}" \
  --machine-type="${MACHINE_TYPE}" \
  --confidential-compute-type=SEV \
  --maintenance-policy=MIGRATE \
  --shielded-secure-boot \
  --image-project=confidential-space-images \
  --image-family=confidential-space \
  --boot-disk-size="${DISK_SIZE}" \
  --network-interface="${network_interface}" \
  --tags="${NETWORK_TAG}" \
  --metadata="${metadata}" \
  --service-account="${SERVICE_ACCOUNT}" \
  --scopes=cloud-platform
