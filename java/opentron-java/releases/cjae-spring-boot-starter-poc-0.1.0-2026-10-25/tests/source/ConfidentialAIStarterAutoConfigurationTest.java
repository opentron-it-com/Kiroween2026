package org.opentron.cjae.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.ConfidentialAI;
import org.opentron.backend.security.confidential.ConfidentialAIAspect;
import org.opentron.backend.security.confidential.ConfidentialExecutionDeniedException;
import org.opentron.backend.security.confidential.ConfidentialRuntimeVerifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ConfidentialAIStarterAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfidentialAIStarterAutoConfiguration.class));

    @Test
    void autoConfiguresAnnotationGuardAndRunsMethodAfterVerification() {
        ConfidentialRuntimeVerifier verifier = mock(ConfidentialRuntimeVerifier.class);
        contextRunner
                .withUserConfiguration(ServiceConfiguration.class)
                .withBean(ConfidentialRuntimeVerifier.class, () -> verifier)
                .run(context -> {
                    assertThat(context).hasSingleBean(ConfidentialAIAspect.class);
                    assertThat(context.getBean(PaymentService.class).approve()).isEqualTo("approved");
                    verify(verifier).verifyCurrentRuntime();
                });
    }

    @Test
    void annotationFailsClosedWhenVerifierIsMissing() {
        contextRunner.withUserConfiguration(ServiceConfiguration.class).run(context -> {
            assertThatThrownBy(() -> context.getBean(PaymentService.class).approve())
                    .isInstanceOf(ConfidentialExecutionDeniedException.class);
            assertThat(context.getBean(PaymentService.class).invocations()).isZero();
        });
    }

    @Test
    void rejectsAsyncMethodBeforeExecutingItsBody() {
        contextRunner
                .withUserConfiguration(ServiceConfiguration.class, VerifierConfiguration.class)
                .run(context -> {
                    assertThatThrownBy(() -> context.getBean(PaymentService.class).approveAsync())
                            .isInstanceOf(ConfidentialExecutionDeniedException.class)
                            .hasMessageContaining("synchronous");
                    assertThat(context.getBean(PaymentService.class).invocations()).isZero();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ServiceConfiguration {
        @Bean
        PaymentService paymentService() {
            return new PaymentService();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class VerifierConfiguration {
        @Bean
        ConfidentialRuntimeVerifier confidentialRuntimeVerifier() {
            return () -> {};
        }
    }

    static class PaymentService {
        private final AtomicInteger invocations = new AtomicInteger();

        @ConfidentialAI
        public String approve() {
            invocations.incrementAndGet();
            return "approved";
        }

        @ConfidentialAI
        public CompletableFuture<String> approveAsync() {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture("approved");
        }

        int invocations() {
            return invocations.get();
        }
    }
}
