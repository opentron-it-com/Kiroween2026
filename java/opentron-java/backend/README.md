Opentron Java backend

This is a minimal Spring Boot application that implements a small subset of
the OpenTron OpenAI-compatible API used by the frontend. It exposes:

- `GET /v1/models` — returns available models
- `POST /v1/chat/completions` — returns a simple completion (echo of last user message)

Build and run:

```bash
cd java/opentron-java
mvn -pl backend -am package
java --enable-preview --enable-native-access=ALL-UNNAMED -jar backend/target/opentron-java-backend-0.1.0-exec.jar
```

The backend uses Java 21's preview Foreign Function & Memory API. Backend tests
are launched with preview enabled by Maven; other Java 21 runtime launches must
also pass `--enable-preview` and `--enable-native-access=ALL-UNNAMED`.

The server listens on port `8000` by default so it is a drop-in replacement for
the existing Python backend used by the frontend. The GCP confidential-worker
profile instead requires TLS and listens on `8443`.

## CJAE Spring Boot Starter PoC

For an application that already runs inside a verified confidential runtime,
add the starter and annotate a synchronous Spring service method:

```xml
<dependency>
    <groupId>org.opentron</groupId>
    <artifactId>cjae-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
@Service
class ClinicalSummaryService {
    private final ChatClient chatClient;

    ClinicalSummaryService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @ConfidentialAI
    String summarize(String clinicalNote) {
        return chatClient.prompt().user(clinicalNote).call().content();
    }
}
```

The starter auto-configures Spring AOP. It requires exactly one
`ConfidentialExecutionPolicy`; otherwise, an annotated call fails before
entering the service method. Invocation must go through a Spring proxy.
Asynchronous and reactive return types are denied because a policy check at
method invocation cannot protect work that runs later.

The backend applies this guard to multi-agent coordination, skill-based
orchestration, screenshot analysis, and queued single-agent execution. Reactive
API handlers delegate to guarded synchronous execution methods.

In ordinary local profiles (including `embedded`), requests to these agent
endpoints use a local encrypted envelope: the client encrypts the body, query
parameters, and credential headers with AES-256-GCM, and wraps the per-request
AES key using the backend's ephemeral RSA-3072 public key. The backend keeps
the private key in memory and rotates it on restart. Requests are bound to
their HTTP method and path; unsupported or malformed envelopes are rejected.
`GET /v1/confidential/execution-mode` reports `local-envelope`,
`runtime-verified` (when exactly one hardware policy is configured), or
`unavailable`; clients do not send confidential requests when the policy is
unavailable.

Local envelope encryption protects request data in transit to the backend; it
is not hardware-backed isolation. The backend process and host can inspect
plaintext after decryption, the model provider receives plaintext, and
responses are not encrypted by this envelope. AWS Nitro and GCP CVM profiles
instead use their configured runtime verifier policies.

This is an in-process guard, not a method-migration mechanism. It verifies the
JVM executing the service and invokes the method in that same JVM. The starter
does not move methods from a host process to Nitro, convert Java heap values
to off-heap memory, or guarantee protection for calls to external AI providers.
For the AWS PoC, deploy the application process that owns the service inside
the verified enclave and configure its AWS Nitro verifier/key-release runtime.
See [the starter README](../cjae-spring-boot-starter/README.md) for setup and
the full boundary.

## GraalVM JVM confidential worker image

The confidential container runs the executable Spring Boot JAR on GraalVM
Java 21. This avoids the ONNX Runtime native-image startup crash observed with
the native executable while preserving regular JVM JNI loading:

```bash
docker build \
  --build-arg CJAE_ONNX_PII_MODEL_SHA256=<reviewed-sha256> \
  -f java/opentron-java/backend/Dockerfile.confidential \
  -t cjae-worker:latest .
```

The Dockerfile builds the standalone `confidential-worker` Maven project, not
the full backend reactor. Its compiler includes only the confidential
attestation, gateway, native-memory, and ONNX packages plus the worker entry
point. The worker has its own dependency list and configuration resources;
backend agents, connectors, persistence, and Ollama model-preloading code are
not packaged. The Docker build installs the small Spring Boot starter before
building the worker. To build it outside Docker:

```bash
mvn -f java/opentron-java/cjae-spring-boot-starter/pom.xml clean install
mvn -f java/opentron-java/confidential-worker/pom.xml clean package
```

The image verifies the ONNX model digest and packages the Linux x86_64 ONNX
Runtime libraries alongside the JAR. It launches Java with Java 21 preview
FFM and native-access options and runs as an unprivileged user. The GCP profile
listens on TLS port `8443`; local/default application configuration uses
`8000`. Provide the approved model and tokenizer files as
`deploy/models/pii_ner_v1.onnx`, `deploy/models/vocab.txt`, and
`deploy/models/special_tokens_map.json`. The shared confidential security
sources and selected tests are compiled directly from the backend source tree
by the worker Maven build; that tree is the canonical source, not a separately
maintained worker copy.

The Dockerfile-specific ignore file keeps unrelated application source out
of the build context. The GraalVM JVM image uses glibc; Alpine/musl is not a compatible
runtime base. The model digest does not authenticate the tokenizer files, so
supply all three files from the same trusted, immutable model release. The
separate native-image Maven profile remains available, but is not used for
this ONNX-enabled deployment image. ONNX Runtime is directed to the
read-only `/opt/cjae/lib` directory for JNI loading so its shared objects do
not need to be extracted to the container's no-exec `/tmp`. The NER adapter
accepts `input_ids` and `attention_mask`, with optional BERT
`token_type_ids` (zero-filled for single-sequence inputs).

## Confidential Gateway prototype (local and untrusted)

The initial sanitize/desanitize API is an explicitly untrusted local
prototype. It uses Java rules for email, phone, US SSN, Luhn-valid payment
card, and checksum-valid IBAN candidates. The `onnx-pii` profile now combines
those rules with the configured NER model. This improves coverage, but does
not provide comprehensive PII detection: country-specific identifiers,
context-dependent identifiers, misspellings, and model false negatives can
still pass through. Do not send real sensitive data or expose the local
prototype to a network.

The `/sanitize` and `/sanitize/batch` routes parse JSON from the servlet input
stream and decode prompt text directly into bounded, confined Java 21 native
memory. Batch text shares one native allocation through zero-copy slices.
Governor permits are acquired before parsing, and the parser enforces body,
segment-count, and character limits. This avoids creating a whole prompt
`String` at the HTTP binding boundary. `ConfidentialSegment.fromUtf8` also
encodes direct callers' input strings into native memory without an
intermediate heap `byte[]`.

This is application-level copy reduction, not heap elimination or a
confidentiality boundary. Matched PII is retained as native-memory slices;
the secure-context codec serializes and encrypts those slices using direct
buffers and decrypts back into native memory. The `/desanitize` and
`/desanitize/batch` routes also parse model responses into native segments
instead of binding them as heap strings, and stream restored JSON from native
segments through a bounded scratch buffer that is zeroed after writes.
Sanitized HTTP responses are now assembled and JSON-encoded from native
segments without materializing the complete sanitized prompt as a Java
`String`; existing service methods that explicitly return string result
records remain available to internal callers. Opaque replacement tokens and
entity/WordPiece ID and offset metadata still use ordinary Java objects.
ONNX Unicode normalization still creates transient heap strings, although
WordPiece matching no longer creates a candidate string for every vocabulary
probe. Servlet containers, the JVM, and native libraries may also make
internal copies. The legacy service overloads that accept `String` remain
available to internal callers.
The GCP key provider also wraps each decrypted DEK in `SecretKeySpec`, which
copies the key bytes; clearing the original KMS response buffer cannot erase
that copy or provider-internal cipher copies. Its cached `SecretKey` remains
available for up to 60 seconds. This code therefore reduces exposure but does
not provide exclusive off-heap secret storage.

For deployments handling real sensitive data, disable process core dumps and
JVM heap dumps, restrict diagnostic/debug access, and either disable swap or
use encrypted ephemeral swap. For example, configure the service manager with
`LimitCORE=0` and launch the JVM with `-XX:-HeapDumpOnOutOfMemoryError`.
These controls reduce persistence risk; they do not erase JVM/provider copies
or protect against privileged memory inspection.

Secure-context payloads use version `v3` with a binary encoding. Contexts are
cryptographically bound to both the tenant and authenticated OIDC subject in
the HTTP API; changing subject invalidates the context. Contexts created by
the prior `v1`/`v2` formats are not accepted; allow outstanding contexts to
expire or invalidate them during rollout. The production profile defaults to
a five-minute context lifetime and rejects settings above 15 minutes.

The confidential gateway limits active sanitize/desanitize work with a fair
semaphore governor. It defaults to 64 operations per process and can be
changed with `CONFIDENTIAL_GATEWAY_MAX_CONCURRENT_OPERATIONS`; the streaming
sanitize routes hold a permit for parsing and processing, while virtual
threads waiting for permits park rather than consuming a platform thread.
This governor does not itself switch the servlet server to virtual request
threads.

### ONNX PII model inference

The optional `onnx-pii` profile verifies a deployment-mounted model by its
pinned SHA-256, opens an ONNX Runtime session, and supplies a BERT WordPiece
tokenizer for token-classification inference. When this profile is active, its
`PiiDetector` bean replaces the prototype rule detector for the configured
gateway; without it, other profiles continue to use the prototype detector.
Inputs are `input_ids` and `attention_mask` as INT64
`[1, sequence_length]`; output is FLOAT32 logits shaped
`[1, sequence_length, num_labels]`. BIO predictions are mapped back to original
Java-string offsets. Long inputs are evaluated with overlapping windows.

The model label IDs must be configured as JSON using contiguous integer keys
starting at zero and `O`/`B-TYPE`/`I-TYPE` labels. The configured
`dslim/bert-base-NER` model mapping is
`{"0":"O","1":"B-MISC","2":"I-MISC","3":"B-PER","4":"I-PER","5":"B-ORG","6":"I-ORG","7":"B-LOC","8":"I-LOC"}`.
Mount the model, `vocab.txt`, and `special_tokens_map.json` at the configured
paths. The tokenizer supports BERT's basic token splitting and greedy WordPiece
segmentation; it is not a replacement for model-specific normalization
options, added tokens, or custom preprocessing. Check these details against
the approved model before deployment. The ONNX file is SHA-256 pinned;
vocabulary and special-token files are not independently hash-pinned by this
profile, so mount them from the same trusted, immutable deployment artifact.

The artifact SHA-256 is streamed in 1 MiB chunks directly into
`ConfidentialSegment` native memory. `FileChannel` reads into a direct view of
that segment, `MessageDigest` consumes its `ByteBuffer` view, and each chunk is
zeroed before reuse. The full model is not copied into heap or native memory.
Activate with `onnx-pii` only after mounting an approved model at
`/opt/cjae/models/pii_ner_v1.onnx` and setting
`CJAE_ONNX_PII_MODEL_SHA256` to its reviewed digest.

The configured checkpoint is
`963039b81eec5b33e23d84826ccdf1e8f8ada776f320e692113034cfae384617`, matching
the deployment ONNX file currently staged in `deploy/models`. Empty model
files are rejected, and verification must pass against the actual read-only
deployment artifact before enabling the profile. Do not treat a configured
digest alone as model approval.
Inference uses ONNX Runtime's platform-native library. Direct native input
buffers for token IDs and attention masks are cleared after each run; Java
strings and tokenizer/output metadata are still managed-heap objects, so this
is not end-to-end off-heap processing. The actual approved model/tokenizer
combination still requires separate validation. Deploy the model from an
immutable, read-only mount and use a
self-contained ONNX file; the configured digest currently pins the `.onnx`
file only and does not authenticate external tensor-data sidecars.

#### Deployment validation procedure

Run these checks on the immutable target image, not on a workstation, and keep
the outputs as non-secret deployment evidence. Do not print credentials,
attestation tokens, secure contexts, or released keys.

1. Confirm the mounted model digest and require an exact match before startup:

   ```bash
   MODEL=/opt/cjae/models/pii_ner_v1.onnx
   EXPECTED=963039b81eec5b33e23d84826ccdf1e8f8ada776f320e692113034cfae384617
   test -r "$MODEL"
   ACTUAL=$(sha256sum "$MODEL" | cut -d ' ' -f 1)
   test "$ACTUAL" = "$EXPECTED"
   ```

2. Build and publish the worker image, then launch it through the production
   Confidential Space workflow documented in the worker README. Verify the
   client-side GCA challenge flow and independently enforced WIF/KMS image
   policy before enabling traffic.
4. Send an authenticated sanitize request containing a controlled test email
   and phone number. Require a successful fresh attestation/key-release path,
   masked output for both entities, and successful desanitization back to the
   exact test input. Repeat with an invalid image digest and disallowed
   Confidential Space claims and require startup or the operation to fail
   closed.
5. Record the image/build identifiers, model SHA-256, non-secret policy
   identifiers, test case outcomes, and timestamps. Never include bearer
   tokens, JWTs, ciphertext/plaintext DEKs, user prompts, or secure contexts.

This procedure is a test plan, not a claim of execution. Live model inference
requires the deployment-mounted model and tokenizer package; hardware key
release requires a measured Confidential Space workload and reviewed GCP
WIF/KMS policies. The equivalent AWS Nitro live procedure additionally needs
an enclave exposing `/dev/nsm` and a parent-vsock proxy, as noted in the AWS
adapter status below.

Enable the API only in the `local-untrusted` Spring profile with
`CONFIDENTIAL_GATEWAY_ENABLED=true`. Configure a random bearer token (at least
32 characters) and a Base64-encoded 32-byte AES key. In PowerShell:

```powershell
$tokenBytes = [byte[]]::new(32)
$keyBytes = [byte[]]::new(32)
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($tokenBytes)
$rng.GetBytes($keyBytes)
$rng.Dispose()
$env:CONFIDENTIAL_GATEWAY_API_TOKEN = ([BitConverter]::ToString($tokenBytes)).Replace("-", "").ToLowerInvariant()
$env:CONFIDENTIAL_GATEWAY_CONTEXT_KEY = [Convert]::ToBase64String($keyBytes)
$env:CONFIDENTIAL_GATEWAY_TENANT_ID = "local-development"
$env:CONFIDENTIAL_GATEWAY_ENABLED = "true"
$env:SPRING_PROFILES_ACTIVE = "local-untrusted"

mvn spring-boot:run "-Dspring-boot.run.jvmArguments=--enable-preview --enable-native-access=ALL-UNNAMED" "-Dspring-boot.run.arguments=--server.address=127.0.0.1"
```

The API is bound to loopback in this example. Use the bearer token in the
`Authorization` header:

- `POST /v1/confidential/sanitize` with `{"text":"Contact ada@example.com"}`
- `POST /v1/confidential/desanitize` with
  `{"modelResponse":"...","secureContext":"..."}`
- `POST /v1/confidential/sanitize/batch` with
  `{"segments":["Contact ada@example.com","Call 212-555-0199"]}`
- `POST /v1/confidential/desanitize/batch` with
  `{"modelResponses":["...","..."],"secureContext":"..."}`

Responses use `Cache-Control: no-store`. Contexts are tenant- and subject-bound
in the OIDC API, expire after five minutes by default, and can only be opened while the configured key
is available. With `gateway-oidc` enabled, PostgreSQL atomically consumes a
secure context on its first successful desanitize operation. Only a
SHA-256 hash and expiry are stored, and the row is removed after expiry.
Configure a shared PostgreSQL endpoint with TLS certificate and hostname
verification; startup fails if the replay store is unavailable. A batch uses
one context and consumes it once for the whole batch. This prototype supports
a single configured tenant; the tenant identifier is not a substitute for
production identity or authorization.

The API authenticates the bearer credential through a pluggable
`GatewayCredentialAuthenticator` and derives tenant identity and operation
scopes from the resulting `GatewayPrincipal`; tenant IDs in request bodies
are ignored. The local profile uses a static credential and grants both
gateway scopes to its one configured development tenant. It also applies
process-local fixed-window request and character quotas (60 requests and
5,000,000 characters per minute by default, with a 10,000-tenant state cap).
These quotas and the unauthenticated GCP challenge endpoint rate limit remain
per process; they are not distributed enforcement.

An OIDC/JWT authentication adapter is available under the `gateway-oidc`
Spring profile. Configure `confidential.gateway.oidc.issuer`,
`confidential.gateway.oidc.jwks-uri`, and
`confidential.gateway.oidc.audience`; optionally configure
`confidential.gateway.oidc.tenant-claim` (default `tenant_id`) and
`confidential.gateway.oidc.scopes-claim` (default `scope`). Issuer and JWKS
URLs must be HTTPS. The decoder validates the issuer, token time claims, and
signature from the configured JWKS; the authenticator additionally requires
the configured audience and a tenant claim, and only grants the recognized
`gateway:sanitize` and `gateway:desanitize` scopes from the validated scope
claim. The tenant claim mapping must be agreed with the identity provider;
never accept tenant or scopes from request data.

The `gateway-oidc` profile alone only creates the OIDC decoder and
authenticator. The GCP and AWS provider profiles wire OIDC authorization to
the Gateway endpoints and their respective attested key providers; the local
static bearer credential is not used on those profile combinations. This is
an integration path for target-environment validation, not a production-ready
service. The `onnx-pii` profile combines NER with the
structured-data rules; without it, only the rule detector runs. Quotas remain
process-local, and credential provisioning/rotation, horizontal enforcement,
and complete application-to-attestation provenance are not implemented.

The Gateway API remains disabled unless a supported profile and explicit
enable flag are active. Production support requires target-environment
validation of the selected provider integration and a verified
application-measurement provenance chain before any confidential-runtime
claim is made.

### Current status and external setup required

The code and offline tests provide implementation foundations, not a
production-ready confidential service. The local endpoints require
`local-untrusted`; the OIDC-protected GCP or AWS API requires the matching
provider profile and `gateway-oidc`, plus the explicit
`confidential.gateway.enabled=true` switch. Activating `gateway-oidc` alone
does not expose the Gateway API. Do not combine the OIDC profile with
`local-untrusted` and assume that the API is using OIDC; that profile uses the
local static-token authenticator.

The separate [confidential gateway architecture document](../CONFIDENTIAL_AI_GATEWAY_ARCHITECTURE.md)
is aspirational and does not describe several current implementation details.
This README and the executable source are the status reference: the GCP path
uses `gotpm` from the JVM worker, not direct Panama access to a SEV guest
device; the image runs a JVM rather than a GraalVM native executable; GCA does
not bind the container digest; and there is no real-TEE conformance suite.
The architecture document now lists only the GCP and AWS provider adapters
currently in scope.

| Area | Implemented now | Still required |
| --- | --- | --- |
| Local Gateway | Single-process sanitize/desanitize API, tenant-bound contexts, static local bearer, request/character quotas | Comprehensive PII detection, production auth wiring, distributed quotas, production request limits and worker boundary |
| OIDC/JWT | HTTPS JWKS decoder, claim-validation adapter, and opt-in wiring to GCP/AWS Gateway APIs with offline tests | Identity-provider registration/configuration, agreed tenant/scope claims, rotation/provisioning, distributed quotas and end-to-end issuer tests |
| Deployment and integrations | Standalone Java client JAR for sanitize/desanitize, non-streaming completion, and role-preserving conversation history; `@ConfidentialAI` verification aspect | Isolated worker deployment, framework-specific adapters for retrieval/tool boundaries and streaming, and operational/security review |
| GCP Confidential Space | Digest-addressed launcher; signed runtime configuration verified before secret retrieval; GCA JWT verifier and nonce-challenge endpoint; server and SDK require `STABLE`, reject unexpected `env_override`/`cmd_override` claims, and verify image/VM policy | Independently review WIF/KMS bindings for service account and image digest; live GCP validation; generate TLS identity inside the TEE and bind it to attestation; TLS hostname/certificate validation; production detector evaluation and distributed quotas |
| AWS Nitro Enclaves | Enclave-side nonce/public-key recipient key-release service, direct Panama FFM access to `/dev/nsm`, parent-vsock transport, PCR-policy descriptor and profile-based wiring with offline tests | Enclave worker image/deployment, IAM/KMS key-policy provisioning and verification, and live AWS enclave tests |

#### Integrating the Java client JAR

`org.opentron:opentron-confidential-gateway-client:0.1.0` is a standalone
Java 21 client artifact. It depends on Jackson Databind and the JDK HTTP client,
not the Gateway's Spring, database, or cloud-provider runtime dependencies.
The Gateway server and this client are separate deployment components: client
applications need a running Gateway endpoint and a bearer credential with the
required scopes.

Build and install the artifact into the local Maven repository from the
repository root:

```powershell
mvn -f java/opentron-java/pom.xml -pl gateway-client -am install
```

Then add the dependency to the integrating application's `pom.xml`:

```xml
<dependency>
  <groupId>org.opentron</groupId>
  <artifactId>opentron-confidential-gateway-client</artifactId>
  <version>0.1.0</version>
</dependency>
```

Use a TLS Gateway endpoint outside loopback development. The conversation
helper sanitizes the content of every role together under one secure context,
passes only sanitized messages to the supplied model callback, then restores
the model response before returning it to the application:

```java
var gateway = new ConfidentialGatewayClient(
    URI.create("https://gateway.example.com"),
    System.getenv("CONFIDENTIAL_GATEWAY_API_TOKEN"),
    HttpClient.newHttpClient(),
    new ObjectMapper(),
    Duration.ofSeconds(30));
var conversation = new ConfidentialConversationClient(gateway);
var answer = conversation.complete(
    List.of(
        new ConfidentialConversationClient.Message("system", systemPrompt),
        new ConfidentialConversationClient.Message("user", userPrompt)),
    sanitizedMessages -> model.complete(sanitizedMessages));
```

For a GCP Confidential Space deployment, construct the SDK using
`ConfidentialGatewayClient.forGcpConfidentialSpace(...)` and an explicit
`GcpConfidentialSpacePolicy` containing the expected GCA audience, exact VM
subject, hardware model, and SHA-256 workload-image digest. The SDK obtains
and verifies a fresh signed GCA token before its first authenticated gateway
operation and refreshes evidence before expiry. Verification uses Google's
pinned GCA JWKS endpoint and checks issuer, audience, time bounds, nonce,
Secure Boot, production Confidential Space status, VM identity, and the
measured container digest. The legacy constructor remains for non-GCP
deployments and does not provide Confidential Space attestation.

The Confidential Space image permits metadata overrides only for the signed
runtime-config envelope. Its measured bootstrap verifies the detached RSA
signature against a public key embedded in the image, enforces a strict
configuration schema, then applies the configuration before retrieving pinned
Secret Manager versions. The launcher signs configuration locally and never
sends the private signing key to the VM. This does not replace external
WIF/KMS policy or image-digest enforcement.

This is not TLS key binding: use a client-validated TLS certificate and
configure WIF/KMS policy to release keys only for the expected Confidential
Space image digest. The service process can request GCA tokens itself, so
attestation is not proof that the HTTPS certificate's private key belongs to
the measured workload. See Google's
[Confidential Space token claims](https://cloud.google.com/confidential-computing/confidential-space/docs/reference/token-claims).

The model adapter must use only the supplied sanitized content when constructing
its provider request; do not close over or forward the original prompt,
retrieval chunks, or tool arguments. The helper handles non-streaming message
content only; tool-call metadata and streaming restoration require a
framework-specific integration and are not implemented. Treat secure contexts
as sensitive bearer data, do not log or persist them, and ensure all sensitive
prompt components are included in the same sanitize call. The local Gateway
prototype is not suitable for real sensitive data.

#### Identity-provider setup (when an OIDC account is available)

An identity-provider tenant/account is needed to validate the adapter against
real signed tokens. Configure an API/resource audience for the Gateway and
issue access tokens whose `iss`, `aud`, expiry and optional `nbf` claims match
the configured values. Define a trusted tenant claim (default `tenant_id`)
and a scopes claim (default `scope`) that emits `gateway:sanitize` and/or
`gateway:desanitize`. The application registering/obtaining tokens must be
authorized for that resource and scopes. To enable the GCP-backed API,
activate `gcp-cvm`, `gateway-oidc`, and `gateway-tls`, set
`confidential.gateway.enabled=true`, and configure:

```text
SPRING_PROFILES_ACTIVE=gcp-cvm,gateway-oidc
confidential.gateway.enabled=true
confidential.gateway.oidc.issuer=https://<identity-provider>/<tenant-or-realm>
confidential.gateway.oidc.jwks-uri=https://<identity-provider>/<jwks-endpoint>
confidential.gateway.oidc.audience=<registered-gateway-api-audience>
confidential.gateway.oidc.tenant-claim=tenant_id
confidential.gateway.oidc.scopes-claim=scope
```

Use the issuer and JWKS URL published by the chosen provider, not guessed
endpoints. Do not put a client secret in the Gateway: this adapter validates
bearer access tokens and does not acquire tokens. The GCP and AWS API
configurations wire this authenticator into the HTTP API; verify key rotation,
invalid signatures, wrong issuer/audience, expiry, tenant mapping, and scope
denial with the selected provider before deployment. For GCP, use the provider
configuration below. The image includes a checksum-pinned
Google `gotpm` utility as its nonce-bound GCA token source; the VM must expose
its vTPM device and have the documented GCA API permissions and OAuth scope.
The GCP worker's `onnx-pii` profile composes the ONNX NER model with the
structured-data rules. Detection remains incomplete and quotas remain
process-local; do not treat either as a production guarantee.

#### Where to configure external account/resource values

Spring automatically loads the profile property files
`application-gateway-oidc.properties`, `application-gcp-cvm.properties`, and
`application-aws-nitro.properties`.
They read environment variables rather than containing account-specific
values or credentials. Set the variables in the cloud workload environment,
deployment platform, or secret/configuration manager. For local testing,
PowerShell uses `$env:NAME="value"`; do not commit populated property files,
tokens, private keys, or secret values.

The property prefixes for provider values and their sources are:

| Provider/profile | Account/resource settings | Credential source |
| --- | --- | --- |
| OIDC (`gateway-oidc`) | Issuer, JWKS URI, audience, tenant claim and scopes claim | Public identity-provider metadata/JWKS; clients send short-lived access tokens; Gateway has no client secret |
| GCP (`gcp-cvm`) | GCA audience, hardware model, allowed project/zone/instance, expected Confidential Space image digest; canonical WIF provider audience; KMS CryptoKey resource, context key ID and encrypted DEK ciphertext | The packaged `gotpm` utility obtains nonce-bound GCA evidence through the vTPM; WIF exchanges verified GCA JWT for short-lived Google credentials. WIF/KMS policy must independently enforce the image-digest claim. |
| AWS (`aws-nitro`) | KMS key ARN/ID, encrypted DEK ciphertext, context key ID, EIF ImageSha384 and required PCR values | Parent-instance role/credential provider in the vsock proxy; the enclave runtime must expose `/dev/nsm` and allow the worker to access it |

The actual account values should be set as the corresponding environment
variables referenced in the profile files. For example, `GCP_KMS_KEY_RESOURCE` is the
fully-qualified CryptoKey resource, and `AWS_NITRO_KMS_KEY_ID` is the KMS key
ARN/ID. All measurements and PCR values must be collected from the exact
approved images and enforced by the relevant cloud-side policy; never copy
example measurements into production.

### Google Cloud Confidential VM adapter

The `gcp-cvm` profile provides a Java adapter that validates a fresh
audience/nonce-bound token against Google's pinned GCA signing-key endpoint,
then checks issuer, audience, EAT profile, token times, nonce, Secure Boot,
selected AMD SEV or Intel TDX hardware model, and allowlisted GCE
project/zone/instance identity. The worker uses the packaged `gotpm` utility and measured-boot event log as
its nonce-bound GCA token source. The verifier checks the signed GCA token,
including its nonce, audience, Secure Boot, hardware model, and configured
GCE identity. `POST /v1/attestation/gcp` accepts a client-generated 256-bit
base64url challenge and returns the verified compact token for independent
client verification. The token proves GCA claims about the VM; GCA does not
assert that those claims measure the Gateway JAR or container image.

For key access, the adapter exchanges the verified GCA JWT through Google's
Security Token Service using a Workload Identity Federation provider, then
calls Cloud KMS `decrypt` for a previously encrypted 256-bit context key. The
key is not embedded in configuration as plaintext. To use the Gateway API,
activate `gcp-cvm,gateway-oidc` with
`confidential.gateway.enabled=true`; do not activate multiple hardware
provider profiles together. The GCP API configuration combines the rule
detector and ONNX NER model under
`onnx-pii`. Its `/v1/attestation/gcp` endpoint returns a GCA-signed,
client-challenge-bound token so clients can verify the GCP VM claims before
sending prompts. This proves the VM claims accepted by this verifier, not the
container image, application code, TLS key, or absence of a malicious process
inside the guest. TLS is terminated by the worker's TLS 1.3 listener using a
PKCS#12 certificate and password fetched from explicit Secret Manager
versions. Restrict ingress with a GCP firewall, provision a certificate whose
SAN matches the client endpoint, and grant the VM service account access only
to those secret versions.

The startup script requires an explicit SHA-256 image digest instead of
pulling `:latest`, and takes allowed project/zone/instance values from explicit
deployment configuration rather than deriving them from live instance
metadata. These settings are still mutable by principals with metadata or
startup-script permissions. A digest-addressed pull prevents tag drift; it
does not prove image provenance to GCA. The external Workload Identity
Federation attribute condition and KMS IAM policy must independently constrain
key release. Review and test those policies outside this repository before
deploying sensitive data. GCP context-key attestation/WIF/KMS calls are cached
for at most 60 seconds to reduce repeated subprocess and service calls; a
revocation can therefore take up to that interval to affect a running worker.

Required application properties:

```text
confidential.gcp.attestation.audience
confidential.gcp.attestation.hardware-model=AMD_SEV|INTEL_TDX
confidential.gcp.attestation.allowed-projects
confidential.gcp.attestation.allowed-zones
confidential.gcp.attestation.allowed-instances
confidential.gcp.attestation.gotpm-path=/opt/cjae/bin/gotpm
confidential.gcp.attestation.event-log-path=/opt/cjae/tpm/event.log
confidential.gcp.wif.audience
confidential.gcp.kms.key-resource
confidential.gcp.kms.context-key-id
confidential.gcp.kms.ciphertext-base64
```

External setup requires a GCP project and supported Confidential VM, enabled
Cloud APIs, Confidential Computing, Security Token Service and Cloud KMS APIs,
the `cloud-platform` OAuth scope, a service account with
`confidentialcomputing.challenges.create`,
`confidentialcomputing.challenges.verify`,
`confidentialcomputing.locations.get`, and
`confidentialcomputing.locations.list` permissions, an
IAM Workload Identity Pool/provider configured to trust the Google GCA issuer
and the exact attestation audience/claims, and a principal binding that grants
only the required KMS decrypt permission on the selected CryptoKey. The key
must contain a previously encrypted 32-byte DEK; supply its ciphertext in
Base64 form and protect it as configuration. Restrict the WIF provider with
attribute conditions for the expected hardware model, Secure Boot, project,
zone and instance. The exact GCA audience and claims must be confirmed on the
target VM/provider setup.
For classic AMD SEV, `gotpm` is invoked with the explicitly mounted vTPM event
log and without a `--tee-technology` hardware quote selector. Do not treat this
vTPM evidence as SEV-SNP attestation; the configured GCA verifier must accept
the resulting `GCP_AMD_SEV` hardware claims.

The deployed worker combines `gcp-cvm`, `gateway-oidc`, `onnx-pii`, and
`embedded`; its H2
database is in-memory and is lost whenever the container restarts. Configure
Cloud SQL or another supported durable database before relying on persisted
application state. The runtime can start without WIF/KMS values while the
confidential Gateway API is disabled. Enabling
`confidential.gateway.enabled` requires the WIF/KMS properties and the
separate `gateway-oidc` issuer, JWKS URI, and audience. The code locally
validates the attestation JWT before token exchange; external WIF and KMS
configuration must independently enforce the intended trust policy. No GCP
enclave/worker boundary or application-to-measurement provenance mechanism is
included.

### AWS Nitro Enclaves adapter boundary

The `gateway.aws` package contains an enclave-side key-release service. It
creates a fresh nonce and RSA recipient key pair, asks the NSM source
for an attestation document bound to both, sends that document as AWS KMS
`Decrypt.Recipient`, and unwraps only KMS's `CiphertextForRecipient` response
inside the enclave-side service. It requires a 256-bit AES context key. The
PCR/image policy helper describes AWS KMS key-policy condition values for
`kms:RecipientAttestation:ImageSha384` (PCR0) and selected PCRs.

This is deliberately **not** wired as a normal Gateway HTTP provider:
Nitro Enclaves have no external networking and require an isolated enclave
worker plus a parent-instance vsock proxy to AWS KMS. The `aws-nitro` profile
now provides an AF_VSOCK transport to parent CID 3, port 8000 by default;
override with `AWS_NITRO_PARENT_VSOCK_CID` and
`AWS_NITRO_KMS_PROXY_VSOCK_PORT`. It sends a four-byte network-order frame
length followed by UTF-8 JSON:

```json
{"version":1,"operation":"kms:Decrypt","request":{"KeyId":"...","CiphertextBlob":"base64","Recipient":{"AttestationDocument":"base64","KeyEncryptionAlgorithm":"RSAES_OAEP_SHA_256"}}}
```

The proxy must return the same four-byte length framing and UTF-8 JSON of the
form `{"version":1,"response":{"CiphertextForRecipient":"base64"}}`, execute
the AWS KMS request using parent-instance credentials, and never return
`Plaintext`. Socket read/write timeouts and frame-size bounds are enforced.
The Java adapter uses Panama FFM downcalls to Linux libc's `open`, `ioctl`, and
`close` functions. It encodes the NSM CBOR request in Java and sends it directly
to `/dev/nsm`; no separate NSM shared library or Rust bridge is packaged. The
runtime must enable Java 21 preview FFM/native access and provide the NSM device
to the enclave process. The Nitro enclave still requires an authenticated,
least-privilege parent proxy; this transport does not
authenticate the proxy protocol by itself. The KMS condition helper does not
install or verify the remote key policy; KMS enforces only conditions that
administrators actually configure.

External setup, before this can run on AWS, includes an enclave-capable EC2
parent, an EIF image and measured PCR values, a Nitro Enclave with appropriate
CPU/memory allocation, a parent vsock proxy with AWS KMS credentials, an IAM
role with least-privilege decrypt access, and a KMS key policy restricting
`Recipient` requests to the approved image/PCR values. Test both allowed and
denied PCRs and prove that plaintext is returned only to the enclave. The
bridge and offline source/configuration tests build successfully, but live AWS
enclave/KMS conformance has not yet been verified. To build an AWS worker image,
set `SPRING_PROFILES_ACTIVE=aws-nitro,onnx-pii` as a Docker build argument and
configure the required `AWS_NITRO_*` variables at runtime. GCP uses the
`gotpm`/vTPM and event-log route, not a direct SEV-SNP quote ioctl.

### Application-measurement provenance plan (not implemented)

Treat the SNP `launchmeasurement` as a guest-launch assertion only. To make an
application identity claim, the deployment needs an independently verifiable
chain from an approved build to the running application:

1. Produce a reproducible, signed build manifest identifying the Gateway
   artifact digest, dependency/SBOM digests, source revision, build workflow,
   and builder identity. Verify the signature against a pinned release key.
2. Package the runtime into an immutable, signed boot image with Secure Boot
   enforcing the firmware, bootloader, kernel, and initramfs chain. Do not
   fetch or modify executable artifacts after the measured boot boundary.
3. Have a trusted, measured launcher verify the manifest and artifact
   digests before starting the Gateway. Extend an approved vTPM PCR with the
   manifest/artifact digest and create a TPM quote bound to the fresh
   attestation challenge.
4. Verify the quote, event log, PCR policy, and signed build provenance
   outside the guest. Release secrets only if the attestation path cryptographically
   binds that verified application identity to the guest evidence and nonce.

The current GCP verifier and client SDK validate the GCA-signed VM identity
and Confidential Space image-digest claims, but do not establish a complete
signed build-to-runtime provenance chain. The AWS enclave path similarly
depends on KMS enforcement of the configured PCR policy. These controls must
be validated against the actual cloud policies and deployment artifacts; do
not describe either integration as fully verified from offline tests alone.

### PII detector baseline evaluation

Run the synthetic detector evaluation with:

```bash
mvn -Dtest=PiiDetectorEvaluationTest test
```

The report includes exact-span micro-precision/recall/F1, recall by labeled
entity category and language, p50/p95 per-document latency, and approximate
per-thread allocated bytes per detection. Allocation uses the JVM's
`ThreadMXBean` allocation counter where supported; it is not retained-memory
usage. Latency/allocation values are environment-specific and should be
collected on a controlled machine for comparisons.

This small, hand-labeled synthetic corpus is a smoke-test baseline, not a
representative evaluation dataset. It intentionally includes unsupported
entities and languages to show coverage gaps. Do not use these scores to
claim production detection quality. Before considering replacement detectors,
expand and independently review the corpus, benchmark each candidate against
the same spans, measure language/entity coverage and operational costs, and
review each model/library license and distribution obligations. No external
PII detector has been selected or licensed into this prototype.

The Java client and composition helper are under
`org.opentron.backend.security.confidential.gateway.sdk`. The composition
helper only passes sanitized prompt text to the supplied model client:

```java
var gateway = new ConfidentialGatewayClient(
        URI.create("http://127.0.0.1:8000"),
        System.getenv("CONFIDENTIAL_GATEWAY_API_TOKEN"),
        HttpClient.newHttpClient(),
        new ObjectMapper(),
        Duration.ofSeconds(20));

var completion = new ConfidentialCompletionClient(gateway, modelClient::complete);
String answer = completion.complete("Contact ada@example.com");
```

The model adapter must not log prompts or responses and should use its own
provider authentication and timeout policy. This prototype helper is
non-streaming; it does not automatically protect separate chat history,
retrieval context, or tool arguments unless the caller includes them in the
prompt passed through the Gateway. Use the SDK's `sanitizeSegments` and
`desanitizeSegments` methods when protecting distinct chat messages or other
separately structured text.

Windows packaging (optional)

You can create a native Windows installer or executable using `jpackage` (JDK
17+). Example:

```bash
cd java/opentron-java/backend/target
jpackage --type exe --input . --name OpentronBackend \
	--main-jar opentron-java-backend-0.1.0.jar --app-version 0.1.0 \
	--icon path/to/icon.ico
```

Alternatively use Launch4j or Inno Setup for more advanced installers.
