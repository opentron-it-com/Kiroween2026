# Tests Performed

**Evidence date:** 25 October 2026  
**Java:** 21 (`C:\Users\ciorica\Documents\jdk-21.0.11`)  
**Maven:** 3.9.16

## Passing verification

| Module | Command | Result |
|---|---|---|
| Spring Boot starter | `mvn -f java/opentron-java/cjae-spring-boot-starter/pom.xml clean verify` | 3 tests passed; 0 failures/errors |
| Gateway client | `mvn -f java/opentron-java/gateway-client/pom.xml clean test` | 47 tests passed; 0 failures/errors |

The starter tests cover auto-configuration and verified invocation,
fail-closed behavior when the verifier is missing, and rejection of async
method execution.

The gateway-client suite includes 20 approved-keyword and 20
rejected-keyword parameterized cases, plus the existing HTTP contract tests.
The approved inputs are:

`weather`, `recipe`, `gardening`, `travel itinerary`, `book review`,
`public schedule`, `meeting agenda`, `product description`, `general knowledge`,
`tutorial`, `arithmetic`, `sports score`, `movie summary`,
`language translation`, `documentation`, `public announcement`,
`restaurant menu`, `historical fact`, `packing list`, `code example`.

The rejected inputs are:

`password`, `passcode`, `secret key`, `api key`, `access token`, `private key`,
`social security number`, `ssn`, `credit card`, `bank account`,
`medical record`, `diagnosis`, `patient`, `date of birth`, `passport number`,
`driver license`, `insurance policy`, `biometric`, `confidential`, `restricted`.

## Keyword fixture limits

The keyword cases run against a local HTTP server in the test process. The
fixture returns success for the approved set and HTTP 422 with the code
`POLICY_REJECTED` for the rejected set. Tests assert the SDK returns the
successful typed result or throws `GatewayClientException` with status 422
and the safe error code.

The gateway-client is an HTTP SDK, not a content-policy engine. The fixture's
rules are test-only and do not mean the SDK detects or blocks those words in
production. Actual decisions belong to the configured gateway policy.

## Not performed

- Deployment or external runtime verification.
- Load, latency, throughput, CPU, memory, or comparative benchmark.
- Production security certification.

Surefire XML and text results are under `tests/reports/`. The starter and
gateway-client test sources are under `tests/source/`.
