package org.opentron.backend.security.confidential.gateway.sdk;

import java.util.Objects;

/** Sanitizes a prompt before model execution and restores the model response afterward. */
public final class ConfidentialCompletionClient {
    private final ConfidentialGatewayClient gatewayClient;
    private final SanitizedModelClient modelClient;

    public ConfidentialCompletionClient(
            ConfidentialGatewayClient gatewayClient,
            SanitizedModelClient modelClient) {
        this.gatewayClient = Objects.requireNonNull(gatewayClient, "gatewayClient");
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
    }

    public String complete(String prompt) {
        ConfidentialGatewayClient.SanitizeResult sanitized = gatewayClient.sanitize(prompt);
        String modelResponse = modelClient.complete(sanitized.sanitizedText());
        return gatewayClient.desanitize(modelResponse, sanitized.secureContext());
    }
}
