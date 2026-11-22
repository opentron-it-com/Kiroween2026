package org.opentron.backend.security.confidential.local;

import java.util.function.Supplier;

import org.opentron.backend.security.confidential.ConfidentialExecutionDeniedException;

import jakarta.servlet.http.HttpServletRequest;

public final class LocalConfidentialRequestContext {
    static final String REQUEST_ATTRIBUTE = LocalConfidentialRequestContext.class.getName() + ".permit";
    private static final ThreadLocal<Permit> CURRENT = new ThreadLocal<>();

    private LocalConfidentialRequestContext() {
    }

    public static Permit capture(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        if (value instanceof Permit permit) {
            return permit;
        }
        throw new ConfidentialExecutionDeniedException(
                "Confidential execution requires a validated local encrypted request.");
    }

    public static Permit currentPermit() {
        return CURRENT.get();
    }

    static Permit createPermit(String keyId) {
        return new Permit(keyId);
    }

    public static Scope enter(Permit permit) {
        if (permit == null) {
            throw new ConfidentialExecutionDeniedException(
                    "Confidential execution requires a validated local encrypted request.");
        }
        Permit previous = CURRENT.get();
        CURRENT.set(permit);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static void runWith(Permit permit, Runnable action) {
        try (Scope ignored = enter(permit)) {
            action.run();
        }
    }

    public static <T> T callWith(Permit permit, Supplier<T> action) {
        try (Scope ignored = enter(permit)) {
            return action.get();
        }
    }

    public static final class Permit {
        private final String keyId;

        private Permit(String keyId) {
            this.keyId = keyId;
        }

        public String keyId() {
            return keyId;
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
