# CJAE Spring Boot Starter - Integration Guide

## Add the dependency

```xml
<dependency>
    <groupId>org.opentron</groupId>
    <artifactId>cjae-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Annotate a synchronous service method

```java
import org.opentron.backend.security.confidential.ConfidentialAI;
import org.springframework.stereotype.Service;

@Service
public class PaymentReviewService {
    private final FraudModel fraudModel;

    public PaymentReviewService(FraudModel fraudModel) {
        this.fraudModel = fraudModel;
    }

    @ConfidentialAI
    public ReviewResult review(ReviewRequest request) {
        return fraudModel.review(request);
    }
}
```

The call must go through the Spring-managed proxy. Self-invocation and direct
construction of the service are not intercepted. Methods returning
`Future`, `CompletionStage`, or Reactive Streams `Publisher` are rejected
before the method body runs because a synchronous verification check cannot
protect deferred execution.

## Supply the runtime verifier

The application must register exactly one `ConfidentialRuntimeVerifier`.
Missing or duplicate verifier beans cause annotated invocations to fail closed
before entering the service method. The verifier is responsible for validating
fresh runtime evidence and must not report success when evidence is absent or
invalid.

```java
@Configuration
class ConfidentialRuntimeConfiguration {
    @Bean
    ConfidentialRuntimeVerifier confidentialRuntimeVerifier(
            RuntimeEvidenceValidator evidenceValidator) {
        return evidenceValidator::verifyCurrentRuntime;
    }
}
```

The `RuntimeEvidenceValidator` above is an application-defined example adapter;
implement it using the runtime evidence mechanism appropriate to the
deployment. The starter does not provide that mechanism.

## Security boundary

This PoC verifies the runtime of the JVM already executing the annotated
method, then calls the method in that same process. It does not:

- Move an arbitrary method from one JVM into another isolated runtime.
- Create a remote invocation protocol or serialize arbitrary method arguments.
- Convert all Java heap values into off-heap memory.
- Guarantee confidentiality for data sent to an external AI provider.

For isolated-runtime processing, deploy the application process that owns the
annotated service inside the verified runtime and configure its verifier.
Data crossing that runtime boundary still needs an explicit transport and
trust policy.
