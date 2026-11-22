package org.opentron.backend.security.confidential.gateway.api;

import java.time.Instant;

public record SanitizeResponse(
        String sanitizedText,
        String secureContext,
        String detectorVersion,
        Instant expiresAt) {
}
