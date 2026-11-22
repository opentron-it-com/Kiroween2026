package org.opentron.backend.security.confidential.gateway.api;

import java.time.Instant;
import java.util.List;

public record BatchSanitizeResponse(
        List<String> sanitizedSegments,
        String secureContext,
        String detectorVersion,
        Instant expiresAt) {
}
