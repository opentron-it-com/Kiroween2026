package org.opentron.backend.security.confidential.gateway;

public record DetectedEntity(String type, int start, int end) {
    public DetectedEntity {
        if (type == null || !type.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Entity type is invalid.");
        }
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Entity offsets are invalid.");
        }
    }
}
