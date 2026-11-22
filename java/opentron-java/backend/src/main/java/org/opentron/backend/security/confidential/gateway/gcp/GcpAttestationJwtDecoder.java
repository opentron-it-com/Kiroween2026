package org.opentron.backend.security.confidential.gateway.gcp;

import org.springframework.security.oauth2.jwt.Jwt;

@FunctionalInterface
public interface GcpAttestationJwtDecoder {
    Jwt decode(String compactJwt);
}
