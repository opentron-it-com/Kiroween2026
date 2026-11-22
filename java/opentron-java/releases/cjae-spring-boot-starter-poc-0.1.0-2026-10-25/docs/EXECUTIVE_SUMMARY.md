# Executive Summary

## CJAE Spring Boot Starter PoC 0.1.0

**Prepared:** 25 October 2026  
**Purpose:** Demonstrate a low-friction Spring integration point for
confidential-runtime verification and exercise the companion gateway client.

### What was delivered

The release packages a Spring Boot starter that supplies the `@ConfidentialAI`
annotation and automatically configures a Spring AOP guard. A synchronous,
Spring-proxied annotated method is invoked only after exactly one
`ConfidentialRuntimeVerifier` reports success. Missing or duplicate verifier
beans fail closed. Async and reactive method return types are rejected before
business logic executes.

The companion client provides typed calls for sanitizing and restoring text
and conversation segments. Its tests now include a 20-case approval matrix and
a 20-case rejection matrix.

### Verification evidence

- Starter auto-configuration tests: **3 passed**.
- Gateway-client tests: **47 passed**, including **20 approved** and
  **20 rejected** keyword scenarios.
- Total focused PoC tests: **50 passed, 0 failures, 0 errors**.

The keyword scenarios use a local, test-only HTTP policy fixture. The fixture
returns success for the approved set and HTTP 422 for the rejected set. The
client passes requests through and maps the rejection into a typed exception;
it does not implement keyword classification itself. Raw test reports and
both test sources are included in this bundle.

### What this proves - and does not prove

The PoC demonstrates Spring-proxy runtime gating, fail-closed verifier
configuration, successful typed client responses, and typed propagation of
server-side policy rejection.

It does **not** prove transparent cross-runtime execution, universal off-heap
protection, external model-provider confidentiality, or a throughput/latency
advantage. The keyword fixture is not a production policy, and no deployment
or performance benchmark was run for this release.

### Recommended next steps

1. Run the same approval/rejection examples against the application's actual
   policy service and verify its policy version and decision audit records.
2. Define a typed remote-execution API if a host application must submit work
   to a separately isolated runtime.
3. Run reproducible performance tests with documented hardware,
   configuration, input corpus, and raw measurements before making
   performance claims.

### Executive conclusion

This is a focused software PoC with passing starter and client tests. The
client keyword cases verify the SDK contract against a test fixture; they do
not establish a production content policy. Treat the archive as an
integration deliverable, not as production certification.
