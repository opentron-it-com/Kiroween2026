package org.opentron.backend.security.confidential.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.opentron.backend.security.confidential.ConfidentialSegment;

final class NativeSecretMappings implements AutoCloseable {
    private final Map<String, ConfidentialSegment> values = new LinkedHashMap<>();
    private boolean closed;

    void put(String token, ConfidentialSegment value) {
        if (closed) {
            throw new IllegalStateException("Secret mappings are closed.");
        }
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(value, "value");
        if (values.putIfAbsent(token, value) != null) {
            value.close();
            throw new IllegalArgumentException("Token mapping already exists.");
        }
    }

    int size() {
        if (closed) {
            throw new IllegalStateException("Secret mappings are closed.");
        }
        return values.size();
    }

    boolean containsToken(String token) {
        if (closed) {
            throw new IllegalStateException("Secret mappings are closed.");
        }
        return values.containsKey(token);
    }

    Map<String, ConfidentialSegment> values() {
        if (closed) {
            throw new IllegalStateException("Secret mappings are closed.");
        }
        return Map.copyOf(values);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (ConfidentialSegment value : values.values()) {
            try {
                value.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        values.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
