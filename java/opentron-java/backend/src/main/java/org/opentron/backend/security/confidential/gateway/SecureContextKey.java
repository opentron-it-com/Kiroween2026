package org.opentron.backend.security.confidential.gateway;

import javax.crypto.SecretKey;

public record SecureContextKey(String id, SecretKey key) {
    public SecureContextKey {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Key identifier is invalid.");
        }
        if (key == null || !"AES".equalsIgnoreCase(key.getAlgorithm())) {
            throw new IllegalArgumentException("An AES key is required.");
        }
    }
}
