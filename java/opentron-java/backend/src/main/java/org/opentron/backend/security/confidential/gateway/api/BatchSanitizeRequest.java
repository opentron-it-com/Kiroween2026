package org.opentron.backend.security.confidential.gateway.api;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BatchSanitizeRequest(
        @NotNull
        @NotEmpty
        @Size(max = 100)
        List<@NotNull @Size(max = 1_000_000) String> segments) {
}
