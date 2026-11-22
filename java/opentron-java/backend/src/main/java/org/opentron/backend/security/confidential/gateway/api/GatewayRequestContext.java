package org.opentron.backend.security.confidential.gateway.api;

import jakarta.servlet.http.HttpServletRequest;

public final class GatewayRequestContext {
    public static final String PRINCIPAL_ATTRIBUTE =
            GatewayRequestContext.class.getName() + ".principal";

    private GatewayRequestContext() {
    }

    public static GatewayPrincipal requirePrincipal(HttpServletRequest request) {
        Object principal = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (principal instanceof GatewayPrincipal gatewayPrincipal) {
            return gatewayPrincipal;
        }
        throw new IllegalStateException("Authenticated Gateway principal is missing.");
    }
}
