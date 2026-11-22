package org.opentron.backend.security.confidential.gateway;

import java.util.Optional;

public interface SecureContextKeyProvider {
    SecureContextKey currentKey();

    Optional<SecureContextKey> findKey(String id);
}
