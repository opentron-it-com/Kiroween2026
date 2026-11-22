package org.opentron.backend.security.confidential.gateway;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

public final class ConfidentialOperationGovernor {
    private final Semaphore permits;
    private final int maximumConcurrentOperations;

    public ConfidentialOperationGovernor(int maximumConcurrentOperations) {
        if (maximumConcurrentOperations < 1) {
            throw new IllegalArgumentException(
                    "Maximum concurrent confidential operations must be positive.");
        }
        this.maximumConcurrentOperations = maximumConcurrentOperations;
        permits = new Semaphore(maximumConcurrentOperations, true);
    }

    public <T> T execute(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        try (Permit permit = acquire()) {
            return execute(permit, operation);
        }
    }

    public Permit acquire() {
        try {
            permits.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for a confidential operation permit.", exception);
        }
        return new Permit(this);
    }

    <T> T execute(Permit permit, Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (permit == null || permit.governor != this) {
            throw new IllegalArgumentException("A live permit from this governor is required.");
        }
        return permit.execute(operation);
    }

    public static final class Permit implements AutoCloseable {
        private final ConfidentialOperationGovernor governor;
        private boolean closed;

        private Permit(ConfidentialOperationGovernor governor) {
            this.governor = governor;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                governor.permits.release();
            }
        }

        private synchronized <T> T execute(Supplier<T> operation) {
            if (closed) {
                throw new IllegalArgumentException("A live permit from this governor is required.");
            }
            return operation.get();
        }
    }

    public int maximumConcurrentOperations() {
        return maximumConcurrentOperations;
    }
}
