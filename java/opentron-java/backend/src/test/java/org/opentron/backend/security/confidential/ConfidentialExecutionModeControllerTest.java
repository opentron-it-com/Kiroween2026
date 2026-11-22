package org.opentron.backend.security.confidential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.local.LocalConfidentialEnvelopeService;
import org.opentron.backend.security.confidential.local.LocalEncryptedRequestExecutionPolicy;
import org.springframework.beans.factory.ObjectProvider;

class ConfidentialExecutionModeControllerTest {
    @Test
    void reportsLocalEnvelopeWhenLocalPolicyAndKeyServiceAreAvailable() {
        ObjectProvider<LocalConfidentialEnvelopeService> keyProvider = mock(ObjectProvider.class);
        when(keyProvider.getIfAvailable()).thenReturn(mock(LocalConfidentialEnvelopeService.class));
        ConfidentialExecutionModeController controller = new ConfidentialExecutionModeController(
                List.of(new LocalEncryptedRequestExecutionPolicy()), keyProvider);

        assertEquals("local-envelope", controller.getExecutionMode().getBody().get("mode"));
    }

    @Test
    void reportsRuntimeVerificationWhenARealVerifierIsConfigured() {
        ObjectProvider<LocalConfidentialEnvelopeService> keyProvider = mock(ObjectProvider.class);
        when(keyProvider.getIfAvailable()).thenReturn(null);
        ConfidentialExecutionModeController controller = new ConfidentialExecutionModeController(
                List.of(() -> { }), keyProvider);

        assertEquals("runtime-verified", controller.getExecutionMode().getBody().get("mode"));
    }

    @Test
    void reportsUnavailableForZeroOrMultiplePolicies() {
        ObjectProvider<LocalConfidentialEnvelopeService> keyProvider = mock(ObjectProvider.class);
        ConfidentialExecutionModeController noPolicy = new ConfidentialExecutionModeController(List.of(), keyProvider);
        ConfidentialExecutionModeController multiplePolicies = new ConfidentialExecutionModeController(
                List.of(() -> { }, () -> { }), keyProvider);

        assertEquals("unavailable", noPolicy.getExecutionMode().getBody().get("mode"));
        assertEquals("unavailable", multiplePolicies.getExecutionMode().getBody().get("mode"));
    }
}
