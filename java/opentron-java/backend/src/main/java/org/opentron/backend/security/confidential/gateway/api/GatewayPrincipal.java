package org.opentron.backend.security.confidential.gateway.api;

import java.util.Set;

public record GatewayPrincipal(String subject, String tenantId, Set<String> scopes) {
    public GatewayPrincipal {
        if (subject == null || subject.isBlank() || subject.length() > 256
                || tenantId == null || tenantId.isBlank() || tenantId.length() > 256
                || scopes == null || scopes.stream().anyMatch(
                        scope -> scope == null || scope.isBlank() || scope.length() > 128)) {
            throw new IllegalArgumentException("Gateway principal is invalid.");
        }
        scopes = Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    public String secureContextBinding() {
        return tenantId.length() + ":" + tenantId + subject;
    }
}
