package org.opentron.cjae.worker;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.opentron.backend.security.confidential.ConfidentialRuntimeVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile("aws-nitro")
@ConditionalOnProperty(
        name = "confidential.aws.nitro.verify-on-startup",
        havingValue = "true")
@Order(0)
public final class AwsNitroStartupAttestationRunner implements ApplicationRunner {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(AwsNitroStartupAttestationRunner.class);

    private final ConfidentialRuntimeVerifier runtimeVerifier;
    private final AwsNitroStartupStatus startupStatus;
    private final boolean diagnosticsEnabled;

    @Autowired
    public AwsNitroStartupAttestationRunner(
            ConfidentialRuntimeVerifier runtimeVerifier,
            AwsNitroStartupStatus startupStatus,
            @Value("${confidential.aws.nitro.diagnostics.enabled:false}")
                    boolean diagnosticsEnabled) {
        this.runtimeVerifier = Objects.requireNonNull(runtimeVerifier, "runtimeVerifier");
        this.startupStatus = Objects.requireNonNull(startupStatus, "startupStatus");
        this.diagnosticsEnabled = diagnosticsEnabled;
    }

    AwsNitroStartupAttestationRunner(
            ConfidentialRuntimeVerifier runtimeVerifier,
            AwsNitroStartupStatus startupStatus) {
        this(runtimeVerifier, startupStatus, startupStatus.diagnosticsEnabled());
    }

    @Override
    public void run(ApplicationArguments arguments) {
        startupStatus.verificationStarted();
        try {
            runtimeVerifier.verifyCurrentRuntime();
            startupStatus.verificationPassed();
            LOGGER.info(
                    "AWS Nitro startup verification passed: freshNsmAttestation=true, "
                            + "kmsRecipientResponseReceived=true, "
                            + "aes256ContextKeyUnwrapped=true, startupRunnerPassed=true");
        } catch (RuntimeException exception) {
            String failureType = exception.getClass().getSimpleName();
            startupStatus.verificationFailed(failureType);
            if (diagnosticsEnabled) {
                LOGGER.warn(
                        "AWS Nitro startup verification failed; sanitized failureType={}",
                        failureType);
                return;
            }
            throw exception;
        }
    }
}
