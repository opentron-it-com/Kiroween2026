# CJAE Spring Boot Starter

The starter adds `@ConfidentialAI` and automatically applies the fail-closed
execution-policy aspect to Spring-proxied service methods.

## Add the dependency

```xml
<dependency>
    <groupId>org.opentron</groupId>
    <artifactId>cjae-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Mark an existing service operation

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

The annotation works on ordinary synchronous Spring beans, including a service
that calls Spring AI or LangChain4j. The starter does not depend on either AI
framework. The method must be called through its Spring proxy; self-invocation
and non-Spring objects are not intercepted. `Future`, `CompletionStage`, and
Reactive Streams return types are rejected before method execution because a
runtime check at method invocation cannot protect later asynchronous work.

## Configure an execution policy

The application must provide exactly one `ConfidentialExecutionPolicy` bean.
Hardware-backed deployments should provide a `ConfidentialRuntimeVerifier`
that validates fresh attestation evidence and fails closed. A local encrypted
request deployment may provide a policy that validates an authenticated
request context instead. With zero or multiple policies, an annotated
invocation is denied before the business method executes.

```java
@Configuration
class ConfidentialRuntimeConfiguration {
    @Bean
    ConfidentialRuntimeVerifier confidentialRuntimeVerifier(
            AwsNitroEnclaveKeyReleaseService keyReleaseService) {
        return new AwsNitroConfidentialRuntimeVerifier(keyReleaseService);
    }
}
```

Use the AWS Nitro verifier/key-release implementation supplied by the
deployment's confidential runtime. The starter itself is provider-neutral: it
does not provision a Nitro Enclave, create a KMS policy, or start a remote
worker.

## Security boundary

The guard checks the runtime of the JVM executing the annotated method and then
invokes that method in the same process. It does **not** move a method from an
ordinary host JVM into an enclave, transparently proxy arbitrary method
arguments to a worker, or convert Java heap objects into off-heap memory.
Deploy the application process that owns the annotated service inside the
verified enclave. In that deployment, ordinary method arguments and local
processing are within the enclave boundary; use `ConfidentialSegment` or
another explicit protected-memory API when off-heap handling is required.

This is a PoC integration boundary, not a claim that every downstream AI
provider or network call is confidential. Calls leaving the enclave require
their own explicit trust, transport, and data-handling policy.
