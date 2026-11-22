package org.opentron.cjae.worker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class AwsNitroStartupStatus {
    private final boolean required;
    private final boolean diagnosticsEnabled;
    private State state;
    private String failureType;

    @Autowired
    public AwsNitroStartupStatus(
            @Value("${confidential.aws.nitro.verify-on-startup:false}") boolean required,
            @Value("${confidential.aws.nitro.diagnostics.enabled:false}")
                    boolean diagnosticsEnabled) {
        this.required = required;
        this.diagnosticsEnabled = diagnosticsEnabled;
        this.state = required ? State.PENDING : State.NOT_REQUIRED;
    }

    public AwsNitroStartupStatus(boolean required) {
        this(required, false);
    }

    public boolean diagnosticsEnabled() {
        return diagnosticsEnabled;
    }

    public synchronized void verificationStarted() {
        if (required) {
            state = State.RUNNING;
        }
    }

    public synchronized void verificationPassed() {
        if (required) {
            state = State.VERIFIED;
            failureType = null;
        }
    }

    public synchronized void verificationFailed(String failureType) {
        if (required) {
            state = State.FAILED;
            this.failureType = sanitizeFailureType(failureType);
        }
    }

    private static String sanitizeFailureType(String failureType) {
        if (failureType == null || !failureType.matches("[A-Za-z][A-Za-z0-9]{0,79}")) {
            return "VerificationFailure";
        }
        return failureType;
    }

    public synchronized Snapshot snapshot() {
        boolean verified = state == State.VERIFIED;
        return new Snapshot(
                required,
                state.value,
                verified,
                verified,
                verified,
                verified,
                failureType);
    }

    public record Snapshot(
            boolean required,
            String status,
            boolean freshNsmAttestation,
            boolean kmsRecipientResponseReceived,
            boolean aes256ContextKeyUnwrapped,
            boolean startupRunnerPassed,
            String failureType) {

        public boolean ready() {
            return !required || "verified".equals(status);
        }
    }

    private enum State {
        NOT_REQUIRED("not-required"),
        PENDING("pending"),
        RUNNING("running"),
        VERIFIED("verified"),
        FAILED("failed");

        private final String value;

        State(String value) {
            this.value = value;
        }
    }
}
