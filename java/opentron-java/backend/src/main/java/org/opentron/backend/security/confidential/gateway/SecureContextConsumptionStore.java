package org.opentron.backend.security.confidential.gateway;

import java.time.Instant;

@FunctionalInterface
public interface SecureContextConsumptionStore {
    boolean consume(String secureContext, Instant expiresAt);
}
