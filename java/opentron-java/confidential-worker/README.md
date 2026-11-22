# CJAE Confidential Worker

This module builds the standalone confidential gateway worker. It compiles the
confidential memory, GCP attestation, OIDC gateway, and ONNX inference packages
without compiling or packaging the wider OpenTron backend.

Build from the repository root:

```powershell
mvn -f java/opentron-java/cjae-spring-boot-starter/pom.xml clean install
mvn -f java/opentron-java/confidential-worker/pom.xml clean package
docker build -f java/opentron-java/backend/Dockerfile.confidential `
  -t cjae-worker:worker-only .
```

The image starts with `gcp-cvm,onnx-pii`; the public gateway remains disabled
unless explicitly enabled with the `gateway-oidc,gateway-tls` profiles and
`CONFIDENTIAL_GATEWAY_ENABLED=true`. ONNX Runtime and the pinned model load
independently of Ollama.

## GCP Confidential Space deployment

The production launcher is
[`deploy/gcp/confidential-space/launch.sh`](../../../deploy/gcp/confidential-space/launch.sh).
It creates an AMD SEV Confidential Space VM from Google's production image and
starts the worker by immutable Artifact Registry digest. Defaults match the
provided deployment target (`cjae-confidential-engine`,
`cjae-confidential-worker`, `us-central1-c`, `n2d-standard-4`), but no public
IP is attached unless `PUBLIC_IP` is explicitly set. A public address must be
reserved and detached before use; the matching firewall rule must restrict
8443/tcp to trusted client networks. Configure a hostname and a TLS
certificate whose SAN matches it. Private-only deployments still need
controlled egress to the required GCA, Secret Manager, STS/KMS, and OIDC JWKS
endpoints, for example through a narrowly configured Cloud NAT or private
service connectivity.

The image's Confidential Space `tee.launch_policy.allow_env_override` permits
only `CJAE_RUNTIME_CONFIG_BASE64` and `CJAE_RUNTIME_CONFIG_SIGNATURE_BASE64`.
The launcher signs the complete runtime policy with a local RSA key, verifies
that signature against the public key embedded in the image, and sends only
the signed envelope as `tee-env-*` metadata. The signed policy includes the
OIDC, WIF/KMS, digest, allow-list, TLS secret references, replay-store endpoint,
profiles, and TTL; the bootstrap rejects missing, extra, malformed, or
unsigned fields before using them. The image digest remains a separate trust
anchor and must also be enforced by external WIF and KMS policy.

Generate a dedicated key pair outside the repository and protect the private
key from the VM and image build context:

```bash
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
  -out cjae-runtime-config-private.pem
openssl pkey -in cjae-runtime-config-private.pem -pubout \
  -out cjae-runtime-config-public.pem
CJAE_CONFIG_SIGNING_PUBLIC_KEY_BASE64="$(base64 -w0 cjae-runtime-config-public.pem)"
docker build \
  --build-arg SPRING_PROFILES_ACTIVE=gcp-cvm,gateway-oidc,gateway-tls,onnx-pii \
  --build-arg CONFIDENTIAL_GATEWAY_ENABLED=true \
  --build-arg CJAE_CONFIG_SIGNING_PUBLIC_KEY_BASE64="$CJAE_CONFIG_SIGNING_PUBLIC_KEY_BASE64" \
  -f java/opentron-java/backend/Dockerfile.confidential \
  -t cjae-worker:release .
```

The launcher requires `CJAE_RUNTIME_CONFIG_PRIVATE_KEY_PATH` and
`CJAE_CONFIG_SIGNING_PUBLIC_KEY_PATH` to refer to the matching local key files.
Keep the private key offline or in a controlled signing service; never pass it
to Docker or the VM. Rotating the verification key requires building and
publishing a new image digest. The measured bootstrap retrieves only pinned
Secret Manager versions using the attached workload service account, writes
payloads under `/dev/shm/cjae` with mode 0600, and starts Java only after
retrieval succeeds. PostgreSQL JDBC uses `sslmode=verify-full` and the pinned
CA file. Store the TLS keystore as raw PKCS#12 bytes, its password as 1-128
printable ASCII characters, and the PostgreSQL CA as a PEM certificate bundle
in Secret Manager. The worker listens on TLS 1.3 port `8443`.

Before launching, build and publish the confidential worker image, then set
these environment variables in Cloud Shell or another trusted Linux
environment. Do not put payloads or passwords in these variables:

```text
SERVICE_ACCOUNT=dedicated-worker@cjae-confidential-engine.iam.gserviceaccount.com
WORKER_IMAGE_DIGEST=64_HEX_CHARACTER_IMAGE_SHA256
GATEWAY_OIDC_ISSUER=https://identity-provider.example/
GATEWAY_OIDC_JWKS_URI=https://identity-provider.example/.well-known/jwks.json
GATEWAY_OIDC_AUDIENCE=cjae-confidential-gateway
GATEWAY_OIDC_TENANT_CLAIM=tenant_id
GCP_WIF_PROVIDER_AUDIENCE=//iam.googleapis.com/projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/POOL/providers/PROVIDER
GCP_KMS_KEY_RESOURCE=projects/PROJECT_ID/locations/LOCATION/keyRings/KEY_RING/cryptoKeys/KEY
GCP_CONTEXT_KEY_ID=key-version-id
GCP_CONTEXT_DEK_CIPHERTEXT_BASE64=BASE64_ENCODED_KMS_CIPHERTEXT
GCP_TLS_KEYSTORE_SECRET_VERSION=projects/PROJECT_ID/secrets/SECRET/versions/VERSION
GCP_TLS_KEYSTORE_PASSWORD_SECRET_VERSION=projects/PROJECT_ID/secrets/SECRET/versions/VERSION
GATEWAY_REPLAY_POSTGRES_HOST=postgres.internal.example
GATEWAY_REPLAY_POSTGRES_PORT=5432
GATEWAY_REPLAY_POSTGRES_DATABASE=cjae_gateway
GATEWAY_REPLAY_POSTGRES_USER_SECRET_VERSION=projects/PROJECT_ID/secrets/SECRET/versions/VERSION
GATEWAY_REPLAY_POSTGRES_PASSWORD_SECRET_VERSION=projects/PROJECT_ID/secrets/SECRET/versions/VERSION
GATEWAY_REPLAY_POSTGRES_CA_SECRET_VERSION=projects/PROJECT_ID/secrets/SECRET/versions/VERSION
CJAE_RUNTIME_CONFIG_PRIVATE_KEY_PATH=/secure/path/cjae-runtime-config-private.pem
CJAE_CONFIG_SIGNING_PUBLIC_KEY_PATH=/secure/path/cjae-runtime-config-public.pem
```

`GCP_CVM_ALLOWED_PROJECTS`, `GCP_CVM_ALLOWED_ZONES`, and
`GCP_CVM_ALLOWED_INSTANCES` should match the launch target. The launcher
derives them from `PROJECT_ID`, `ZONE`, and `INSTANCE_NAME`; do not set them
to unrelated values. Run the launcher with:

```bash
bash deploy/gcp/confidential-space/launch.sh
```

Grant the dedicated service account Artifact Registry Reader,
Confidential Computing Workload User, and Secret Manager Secret Accessor on
only the required secrets. The workload service identity also needs access to
the configured KMS key through the approved WIF flow. Restrict who can change
the instance metadata, service account, image reference, and launcher inputs.
These metadata values are mutable configuration, not trust anchors. The
external Workload Identity Federation condition and KMS IAM policy must
independently enforce `swname=CONFIDENTIAL_SPACE`, production
`dbgstat=disabled-since-boot`, and the exact
`submods.container.image_digest` from the client policy.

The signed policy prevents launcher metadata from substituting arbitrary
security settings, but it does not replace independent WIF and KMS controls.
Those policies must bind the workload service account, production
Confidential Space claims, and exact `submods.container.image_digest`; the
launcher does not create or validate those external policies.

The old root [`startup_script.sh`](../../../startup_script.sh) is disabled:
it launched an ordinary Confidential VM and cannot attest the measured worker
image. Use the Confidential Space launcher instead.

The worker's gateway OIDC profiles require a shared PostgreSQL database for
single-use secure contexts. Configure `GATEWAY_REPLAY_POSTGRES_URL`,
`GATEWAY_REPLAY_POSTGRES_USER`, and `GATEWAY_REPLAY_POSTGRES_PASSWORD`. The
JDBC URL must include `sslmode=verify-full` and `sslrootcert`; the service
fails at startup if the database connection or replay table initialization
fails. Only context hashes and expiries are stored. Any database outage fails
closed, so desanitize requests cannot restore sensitive content without
enforcing replay protection.

The gateway requires signed access tokens to carry explicit
`gateway:sanitize` and/or `gateway:desanitize` scope claims; missing scope
claims grant no gateway permissions. Secure contexts
are bound to both tenant and OIDC subject, expire after five minutes by
default (maximum 15 minutes), and are consumed on the first successful
desanitize operation across all worker instances sharing the PostgreSQL
database.

The launcher and measured bootstrap fail closed if required configuration,
attestation policy, the database, or a pinned secret is unavailable. The GCA
endpoint does not bind the TLS private key to the image measurement; use
client-validated TLS and retain the independent WIF/KMS image constraints.

`POST /v1/attestation/gcp` accepts a client-generated 256-bit base64url
challenge and returns a nonce-bound GCA token without requiring a bearer
credential. The Confidential Space SDK policy verifies Google's signature,
nonce, time bounds, VM identity, production status, and measured image digest
before authenticated gateway requests. This does not bind the TLS private key
to the measured workload, and a malicious process inside the VM can also
request GCA evidence; configure trusted TLS and independently enforced
WIF/KMS claims.

The local ONNX smoke test validates packaging, model loading, and the HTTP
health route only. Local static-key mode is an untrusted development profile;
it is not evidence of GCP attestation or production key release. In the
`onnx-pii` profile, ONNX NER results are merged with rule detection for
emails, phones, US SSNs, Luhn-valid payment cards, and checksum-valid IBANs.
This still misses some PII and must not be treated as a complete redaction
boundary.

The GCP adapter obtains evidence with Google's `gotpm` utility rather than a
direct Panama binding to `/dev/sev-guest` or `/dev/tdx-guest`. The AWS Nitro
adapter is included in this worker.

## AWS Nitro Enclave image

The AWS profile requires a KMS key ID, a KMS-encrypted 256-bit context DEK, and
a context key ID when the worker image is built. When enabling the gateway, it
also requires a KMS-encrypted, passwordless PKCS#12 TLS identity. Only KMS
ciphertext is included in the image; never include either plaintext key. Keep
the PKCS#12 plaintext at or below AWS KMS `Encrypt`'s 4 KiB limit, then encrypt
it with the same KMS key used by the enclave:

```powershell
aws kms encrypt --region eu-north-1 --key-id $env:AWS_NITRO_KMS_KEY_ID `
  --plaintext fileb://cjae-gateway.p12 --query CiphertextBlob --output text
```

Pass the resulting Base64 value as `AWS_NITRO_TLS_KEYSTORE_CIPHERTEXT_BASE64`.
Use a publicly trusted certificate (or a client-trusted private CA); the parent
host only relays TLS ciphertext and does not terminate TLS. The certificate
private key is KMS-released into the enclave using the NSM Recipient flow.
PCR and image measurements are produced by `nitro-cli build-enclave` and must
be enforced by the KMS key policy, not embedded in the EIF that they measure.

Provide these values as Docker build arguments when building an AWS image:

```powershell
docker build -f java/opentron-java/backend/Dockerfile.confidential `
  -t cjae-worker:aws-nitro `
  --build-arg SPRING_PROFILES_ACTIVE=aws-nitro,onnx-pii,gateway-oidc `
  --build-arg CONFIDENTIAL_GATEWAY_ENABLED=true `
  --build-arg AWS_NITRO_KMS_KEY_ID=$env:AWS_NITRO_KMS_KEY_ID `
  --build-arg AWS_NITRO_CONTEXT_DEK_CIPHERTEXT_BASE64=$env:AWS_NITRO_CONTEXT_DEK_CIPHERTEXT_BASE64 `
  --build-arg AWS_NITRO_CONTEXT_KEY_ID=$env:AWS_NITRO_CONTEXT_KEY_ID `
  --build-arg AWS_NITRO_TLS_KEYSTORE_CIPHERTEXT_BASE64=$env:AWS_NITRO_TLS_KEYSTORE_CIPHERTEXT_BASE64 `
  --build-arg AWS_NITRO_VERIFY_ON_STARTUP=true `
  --build-arg GATEWAY_OIDC_ISSUER=$env:GATEWAY_OIDC_ISSUER `
  --build-arg GATEWAY_OIDC_AUDIENCE=$env:GATEWAY_OIDC_AUDIENCE `
  --build-arg GATEWAY_OIDC_JWKS_JSON=$env:GATEWAY_OIDC_JWKS_JSON .
```

Set `AWS_NITRO_VERIFY_ON_STARTUP=true` to require one fresh NSM attestation
and KMS Recipient decrypt before the Spring worker finishes startup. It defaults
to `false`. When enabled, `/health` returns HTTP 503 until verification passes,
then returns HTTP 200 with boolean evidence for fresh NSM attestation, receipt
of the KMS Recipient response, successful unwrapping of a 256-bit AES context
key, and completion of the startup runner. The endpoint and success log do not
include keys, ciphertext, attestation documents, nonces, or PCR values. A failed
verification fails application startup and never reports readiness.
AWS KMS returns `CiphertextForRecipient` as a bounded CMS envelope; the worker
unwraps it with the ephemeral RSA private key inside the enclave.

For opt-in startup diagnosis, `AWS_NITRO_DIAGNOSTICS_ENABLED=true` keeps the
worker available only for its parent-CID VSOCK status route after verification
fails. It returns HTTP 503 with a sanitized exception class name; it does not
report readiness, run the benchmark, or expose exception messages, keys,
ciphertext, or attestation material. Leave this disabled outside controlled
diagnostic deployments.

For an AWS startup proof while keeping the gateway disabled, set
`AWS_NITRO_HEALTH_VSOCK_ENABLED=true` together with
`AWS_NITRO_VERIFY_ON_STARTUP=true` and
`CONFIDENTIAL_GATEWAY_ENABLED=false`. After the startup runner passes, the
worker listens on enclave vsock port `8002` for the parent CID and accepts
`GET /health`; it returns the same readiness payload as the worker health
controller. This listener binds no host TCP port and cannot reach gateway
operations. From the parent instance (for example through SSM), probe it with:

```bash
sudo python3 - <<'PY'
import json
import socket
import subprocess

enclaves = json.loads(subprocess.check_output(
    ["nitro-cli", "describe-enclaves"], text=True
))
running = [
    enclave for enclave in enclaves
    if enclave.get("EnclaveName") == "cjae-worker-health-vsock-direct"
    and enclave.get("State") == "RUNNING"
]
if len(running) != 1:
    raise SystemExit("Expected exactly one running startup-health enclave.")

with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM) as probe:
    probe.settimeout(5)
    probe.connect((running[0]["EnclaveCID"], 8002))
    probe.sendall(
        b"GET /health HTTP/1.1\r\n"
        b"Host: localhost\r\n"
        b"Connection: close\r\n\r\n"
    )
    response = bytearray()
    while True:
        chunk = probe.recv(1024)
        if not chunk:
            break
        response.extend(chunk)

print(response.decode("utf-8"))
PY
```

The expected HTTP 200 response contains the startup booleans and no key,
ciphertext, attestation document, nonce, or PCR values. The health-only
listener starts only after required startup verification and remains
unavailable whenever the gateway is enabled.

#### Confidential annotation and encryption benchmark

For a private AWS benchmark, keep
`CONFIDENTIAL_GATEWAY_ENABLED=false` and build a separately measured image with
`AWS_NITRO_CONFIDENTIAL_BENCHMARK_ENABLED=true`. The worker runs 10 warmups,
then 100 synthetic payload calls by default. Each measured call invokes a
Spring-proxied `@ConfidentialAI` method, which performs fresh NSM/KMS runtime
verification, AES-256-GCM secure-context sealing, authenticated opening, and
exact synthetic-payload recovery. A failed verifier or encryption round trip
fails worker startup; the code does not report a successful measurement.

The optional results route is reachable only through the parent-CID VSOCK
listener on port `8002` and accepts exactly `GET /benchmark/results`. It
returns timing and success metadata only; input payload values and ciphertext
are not exported. It is absent unless benchmark mode is enabled, and the
gateway stays disabled. The existing health route remains `GET /health`.

After adding the new EIF's exact PCR0/PCR1/PCR2 values to the narrowly scoped
KMS and worker-role policies and launching it in normal mode, run the exporter
on the EC2 parent:

```bash
sudo python3 deploy/aws_nitro_confidential_benchmark_export.py \
  --enclave-name cjae-worker-confidential-benchmark \
  --region eu-north-1 \
  --image-digest sha256:IMAGE_DIGEST \
  --pcr0 PCR0_SHA384 --pcr1 PCR1_SHA384 --pcr2 PCR2_SHA384 \
  --output-dir /opt/cjae/benchmark-export
```

The exporter writes raw, chart-ready `samples.csv`, full environment and
sample data in `summary.json`, and a presentation-ready `report.md`. Annotated
call latency includes runtime verification and both encrypted-context
operations; separate seal/open timings are reported alongside it. Treat these
measurements as serialized microbenchmark results, not concurrent gateway
throughput. The data uses synthetic values only.

#### Million-record confidential RAG comparison

Set `AWS_NITRO_RAG_BENCHMARK_ENABLED=true` to run the separate one-million
synthetic-record workload. Its enclosing Spring-proxied `@ConfidentialAI`
method runs both algorithms sequentially inside the same enclave. Java
tokenizes generated records, builds a 16-dimensional hash embedding index in
zeroizing Panama native memory, performs cosine top-k retrieval, and packages
a prompt. It creates temporary Java strings/vectors during tokenization; do
not describe the complete pipeline as allocation-free. The Python 3
standard-library baseline performs the same deterministic retrieval while
pickling and unpickling batches of 10,000 rows. It is a local serialization
proxy, not an Opaque or Apache Spark implementation.

Each mode has one excluded warm-up and three measured samples. The report
includes per-sample wall latency, process CPU time, process peak RSS, throughput,
off-heap index size, Python serialized bytes, and matching output digests.
Pipeline latency excludes the single enclosing `@ConfidentialAI` attestation
check. Each measured Java/Python prompt pair is AES-256-GCM sealed and reopened;
failed startup, mismatched retrieval, missing metrics, or failed encryption
cannot produce a successful report. Java peak RSS includes the full worker JVM;
Python peak RSS covers its child process.

The RAG result endpoint is the same parent-CID-only `GET /benchmark/results`
route. On the parent, run
`deploy/aws_nitro_rag_benchmark_report.py` to validate the fresh startup
attestation, collect the active EIF PCRs and enclave allocation, and write CSV,
JSON, Markdown executive summary, and PDF presentation files. The 3x latency
and 5x throughput figures are evaluated as hypotheses against captured data;
they are not baked into the results.

#### Live AWS Nitro proof-of-concept validation

Validated on 2026-10-23 with the bulk-read health image
(`sha256:4f0f2e4827a38c9d340955f995025c30f83d4ce2bd7b12bfdafbac10b0ce7d2c`).
The enclave ran in normal mode (`Flags=NONE`) with 2 vCPUs and 4096 MiB.
The measured SHA-384 values were:

```text
PCR0 9d291e55c68408915df690c759704eb97b23b242d7ee097c6b50155409c90cb6d5b007630b73c41d979e91fdc6dca35a
PCR1 4b4d5b3661b3efc12920900c80e126e4ce783c522de6c02a2a5bf7af3a2b9327b86776f188e4be1c1c404a129dbda493
PCR2 de74633e7f04d1fbcabd56634b2ea70b5dfed2b5a2614cd3254030404bf198f369a6226a5640dbaae1c389b0db6c3fa3
```

The KMS key policy retains account-root administration and permits the worker
role only for this image measurement; the worker role policy is narrowed to
the same PCR set. IAM simulation allowed the exact measurements and implicitly
denied a mismatched PCR2. CloudTrail recorded a successful KMS `Decrypt` by the
worker role after launch. The parent-side VSOCK probe returned HTTP 200 with
`freshNsmAttestation`, `kmsRecipientResponseReceived`,
`aes256ContextKeyUnwrapped`, and `startupRunnerPassed` all true. `POST /health`
and `GET /gateway` both returned HTTP 400.

Twenty sequential health probes measured parent-to-enclave VSOCK round-trip
latency: min 0.584 ms, p50 0.615 ms, p95 1.993 ms, max 3.819 ms. This is
health-channel RTT, not model inference latency or gateway throughput. The
gateway remained disabled throughout validation, and the parent exposed no
TCP listeners on ports 8001, 8002, or 8443. The confidential-worker Maven suite
passed 53 tests, and the AWS deployment workflow suite passed 12 tests.

The gateway is disabled unless `CONFIDENTIAL_GATEWAY_ENABLED=true` and the
`gateway-oidc` profile is active with issuer, pinned JWKS JSON, and audience configured.
Because the enclave has no direct internet access, AWS uses a pinned public
JWKS JSON (`GATEWAY_OIDC_JWKS_JSON`) embedded in the image instead of fetching
`GATEWAY_OIDC_JWKS_URI`. Rotate issuer signing keys by rebuilding, re-attesting,
and updating the EIF/KMS measurements before deployment.
When enabled, the Spring TLS listener binds only to enclave loopback on port
8443. The worker's Panama/FFM vsock listener (default port 8001) relays TLS
records to it. On the parent EC2 host, install
`deploy/aws-nitro-gateway-vsock-proxy.service` and
`deploy/aws_nitro_gateway_vsock_proxy.py`; configure the enclave CID, TCP bind
address/port, and vsock port in
`/etc/cjae/aws-nitro-gateway-vsock-proxy.env`. The TCP proxy defaults to
`127.0.0.1:8443`; bind it to a private interface only behind appropriately
restricted network controls. It does not parse HTTP, terminate TLS, or log
request contents. Configure security groups and a trusted load balancer to
preserve end-to-end TLS if external access is required.

The standalone proxy is separate from the host-side KMS proxy and has no KMS
permissions. Do not publish or enable it until the KMS policy covers the final
EIF measurements, the TLS certificate chain is trusted by clients, and the
gateway's live authenticated sanitize/desanitize flow has been tested.

### Building, measuring, and launching the EIF

Build the AWS-profile Docker image with the approved KMS ciphertexts and pinned
OIDC settings, publish or transfer it to the Nitro-enabled EC2 parent, and
make sure the image is available locally to Docker there. On that host, install
the AWS Nitro Enclaves CLI and Python 3, then run the repository workflow tool:

```bash
sudo python3 deploy/aws_nitro_eif_workflow.py build-eif \
  --docker-uri cjae-worker:aws-nitro \
  --output-file /opt/cjae/worker.eif \
  --measurements-file /opt/cjae/worker.measurements.json

sudo python3 deploy/aws_nitro_eif_workflow.py write-policy \
  --measurements-file /opt/cjae/worker.measurements.json \
  --role-arn arn:aws:iam::123456789012:role/cjae-parent \
  --key-arn arn:aws:kms:eu-north-1:123456789012:key/KEY_ID \
  --pcr-indices 0,1,2 \
  --output-file /opt/cjae/kms-recipient-policy-statement.json

sudo python3 deploy/aws_nitro_eif_workflow.py write-role-policy \
  --measurements-file /opt/cjae/worker.measurements.json \
  --key-arn arn:aws:kms:eu-north-1:123456789012:key/KEY_ID \
  --pcr-indices 0,1,2 \
  --output-file /opt/cjae/kms-recipient-role-policy.json
```

The build command verifies Nitro CLI's SHA-384 measurements, requires PCR0,
and writes a mode-0600 measurement file only after confirming the EIF exists.
Use `measure-eif --eif-path /opt/cjae/worker.eif --measurements-file
/opt/cjae/worker.measurements.json` to obtain and validate measurements from
an EIF that has already been built.
`write-policy` writes a reviewable KMS key-policy statement restricted to the
parent instance role, the EIF ImageSha384/PCR0 value, and the selected PCRs.
`write-role-policy` writes the matching IAM identity-policy document scoped to
the KMS key. Install both only after review. The role's IAM permissions must
also require the same attestation conditions: an unconditional IAM
`kms:Decrypt` allow can authorize requests through an account-delegating KMS
key policy, bypassing the measurement-bound allow statements. Do not leave any
broader Decrypt permission or grant for the worker role.

These commands only write reviewable JSON; they do **not** call AWS APIs or
alter policies. Have the key administrator incorporate the key-policy
statement without replacing other required key-administration statements, and
update the worker role's IAM policy to the generated condition-bound document.
Verify both effective policies, then test approved and unapproved EIFs before
releasing keys.

After the KMS policy is independently reviewed and installed, launch without
debug mode and inspect the enclave state:

```bash
sudo python3 deploy/aws_nitro_eif_workflow.py run-enclave \
  --eif-path /opt/cjae/worker.eif \
  --cpu-count 2 \
  --memory-mib 4096
sudo python3 deploy/aws_nitro_eif_workflow.py status
```

The workflow script does not create EC2 instances, upload images, modify IAM or
KMS policies, install services, or send gateway traffic. Capture the returned
enclave CID for the parent vsock services. Configure the bounded KMS proxy in
`deploy/aws_nitro_kms_vsock_proxy.py` with the parent instance role and
`deploy/aws-nitro-kms-vsock-proxy.service`; configure the separate opaque TLS
relay only after attestation/key release succeeds. The parent KMS proxy must
not expose KMS access over host network interfaces.

With the enclave running and the KMS policy installed, enable
`AWS_NITRO_VERIFY_ON_STARTUP=true` and require startup to complete through a
fresh NSM Recipient decrypt. Then test gateway authorization and sanitize/
desanitize traffic through the TLS relay, plus rejected credentials and an
unapproved EIF. These live checks are not performed by the local test suite.
