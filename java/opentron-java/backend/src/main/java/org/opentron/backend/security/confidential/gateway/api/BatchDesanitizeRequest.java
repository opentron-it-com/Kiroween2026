package org.opentron.backend.security.confidential.gateway.api;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BatchDesanitizeRequest(
        @NotNull
        @NotEmpty
        @Size(max = 100)
        List<@NotNull @Size(max = 1_000_000) String> modelResponses,
        @NotNull
        @Size(min = 1, max = 2_000_000)
        String secureContext) {
}
