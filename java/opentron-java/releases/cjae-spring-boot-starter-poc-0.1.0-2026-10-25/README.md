# CJAE Spring Boot Starter PoC 0.1.0

This release bundle contains the Spring Boot starter binary, integration
documentation, starter and gateway-client test sources and reports, an
executive summary, and a presentation PDF.

## Contents

- `artifacts/cjae-spring-boot-starter-0.1.0.jar` - the starter JAR built from
  this repository.
- `docs/STARTER_GUIDE.md` - setup, annotated service example, verifier setup,
  and security boundary.
- `docs/EXECUTIVE_SUMMARY.md` - executive-level scope, outcomes, and caveats.
- `docs/TESTS_PERFORMED.md` - verification commands and results.
- `presentation/CJAE-Spring-Boot-Starter-PoC.pdf` - presentation-ready summary.
- `tests/source/` - starter and gateway-client test sources.
- `tests/reports/` - Maven Surefire XML and text reports from the passing
  starter and gateway-client test runs.

## Quick start

Add the following dependency to a Spring Boot application:

```xml
<dependency>
    <groupId>org.opentron</groupId>
    <artifactId>cjae-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Mark a synchronous Spring service method with `@ConfidentialAI` and provide
exactly one `ConfidentialRuntimeVerifier` bean. See `docs/STARTER_GUIDE.md`
for an example and required proxy behavior.

## Important deployment boundary

The starter performs an in-process verification guard. It does not move an
ordinary host application's method into a separate isolated runtime,
transparently serialize arbitrary method arguments, or protect every
downstream AI call. The JVM owning the annotated service must itself run
inside the runtime that the configured verifier validates.

The keyword approval/rejection cases use a local test-only policy fixture.
They test client request and typed-error handling; the SDK does not implement
a production keyword classifier. The reports are automated tests, not
deployment or performance benchmark evidence. See `docs/TESTS_PERFORMED.md`.
