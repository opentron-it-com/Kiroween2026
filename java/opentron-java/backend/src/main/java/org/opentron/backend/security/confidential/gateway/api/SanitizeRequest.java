package org.opentron.backend.security.confidential.gateway.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SanitizeRequest(
        @NotNull
        @Size(max = 1_000_000)
        String text) {
}
