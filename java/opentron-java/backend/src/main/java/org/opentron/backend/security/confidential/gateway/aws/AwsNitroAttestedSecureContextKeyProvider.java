package org.opentron.backend.security.confidential.gateway.aws;

import java.util.Optional;

import org.opentron.backend.security.confidential.gateway.SecureContextKey;
import org.opentron.backend.security.confidential.gateway.SecureContextKeyProvider;

public final class AwsNitroAttestedSecureContextKeyProvider implements SecureContextKeyProvider {
    private final AwsNitroEnclaveKeyReleaseService keyReleaseService;
    private final String contextKeyId;

    public AwsNitroAttestedSecureContextKeyProvider(
            AwsNitroEnclaveKeyReleaseService keyReleaseService,
            String contextKeyId) {
        if (keyReleaseService == null
                || contextKeyId == null
                || !contextKeyId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("AWS Nitro key release configuration is invalid.");
        }
        if (!keyReleaseService.contextKeyId().equals(contextKeyId)) {
            throw new IllegalArgumentException("Context key identifier does not match key release.");
        }
        this.keyReleaseService = keyReleaseService;
        this.contextKeyId = contextKeyId;
    }

    @Override
    public SecureContextKey currentKey() {
        return keyReleaseService.releaseContextKey();
    }

    @Override
    public Optional<SecureContextKey> findKey(String id) {
        if (!contextKeyId.equals(id)) {
            return Optional.empty();
        }
        return Optional.of(keyReleaseService.releaseContextKey());
    }
}
