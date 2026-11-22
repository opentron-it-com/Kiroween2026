package org.opentron.cjae.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.ConfidentialRuntimeVerifier;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.HttpStatus;

class AwsNitroStartupAttestationRunnerTest {
    @Test
    void recordsSuccessfulVerificationWithoutExposingSensitiveData() {
        AtomicInteger verifications = new AtomicInteger();
        ConfidentialRuntimeVerifier verifier = verifications::incrementAndGet;
        var status = new AwsNitroStartupStatus(true);
        var runner = new AwsNitroStartupAttestationRunner(verifier, status);

        runner.run(new DefaultApplicationArguments());

        assertThat(verifications).hasValue(1);
        assertThat(status.snapshot())
                .isEqualTo(new AwsNitroStartupStatus.Snapshot(
                        true, "verified", true, true, true, true, null));

        var health = worker(status).health();
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().awsNitroStartup().startupRunnerPassed()).isTrue();
    }

    @Test
    void keepsReadinessFailedWhenVerificationFails() {
        ConfidentialRuntimeVerifier verifier = () -> {
            throw new IllegalStateException("verification failed");
        };
        var status = new AwsNitroStartupStatus(true);
        var runner = new AwsNitroStartupAttestationRunner(verifier, status);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(status.snapshot().status()).isEqualTo("failed");
        assertThat(status.snapshot().failureType()).isEqualTo("IllegalStateException");
        assertThat(status.snapshot().ready()).isFalse();
        assertThat(status.snapshot().freshNsmAttestation()).isFalse();

        var health = worker(status).health();
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(health.getBody().awsNitroStartup().kmsRecipientResponseReceived()).isFalse();
        assertThat(health.getBody().awsNitroStartup().failureType())
                .isEqualTo("IllegalStateException");
    }

    @Test
    void diagnosticModeKeepsFailureStatusObservableWithoutExposingExceptionMessage() {
        ConfidentialRuntimeVerifier verifier = () -> {
            throw new IllegalStateException("sensitive message must not be exposed");
        };
        var status = new AwsNitroStartupStatus(true, true);
        var runner = new AwsNitroStartupAttestationRunner(verifier, status);

        runner.run(new DefaultApplicationArguments());

        assertThat(status.snapshot().status()).isEqualTo("failed");
        assertThat(status.snapshot().failureType()).isEqualTo("IllegalStateException");
        assertThat(status.snapshot().ready()).isFalse();
        assertThat(worker(status).health().getBody().toString())
                .doesNotContain("sensitive message");
    }

    @Test
    void startupVerificationIsNotRequiredWhenDisabled() {
        var status = new AwsNitroStartupStatus(false);

        assertThat(status.snapshot().status()).isEqualTo("not-required");
        assertThat(status.snapshot().ready()).isTrue();
        assertThat(worker(status).health().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static ConfidentialWorkerApplication worker(AwsNitroStartupStatus status) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("awsNitroStartupStatus", status);
        return new ConfidentialWorkerApplication(
                beanFactory.getBeanProvider(AwsNitroStartupStatus.class));
    }
}
