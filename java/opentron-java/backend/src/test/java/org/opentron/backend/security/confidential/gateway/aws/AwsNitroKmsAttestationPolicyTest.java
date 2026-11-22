package org.opentron.backend.security.confidential.gateway.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class AwsNitroKmsAttestationPolicyTest {
    private static final String IMAGE = "a".repeat(96);
    private static final String PCR0 = IMAGE;
    private static final String PCR8 = "c".repeat(96);

    @Test
    void exposesImageSha384AndExplicitPcrConditionsForKmsKeyPolicy() {
        AwsNitroKmsAttestationPolicy policy = new AwsNitroKmsAttestationPolicy(
                IMAGE, Map.of(0, PCR0, 8, PCR8));

        assertEquals(
                Map.of(
                        "kms:RecipientAttestation:ImageSha384", IMAGE,
                        "kms:RecipientAttestation:PCR0", PCR0,
                        "kms:RecipientAttestation:PCR8", PCR8),
                policy.stringEqualsConditions());
        assertThrows(UnsupportedOperationException.class,
                () -> policy.stringEqualsConditions().put("kms:Decrypt", "anything"));
    }

    @Test
    void rejectsMissingOrInvalidImageAndPcrConditions() {
        assertThrows(IllegalArgumentException.class,
                () -> new AwsNitroKmsAttestationPolicy(null, Map.of(0, PCR0)));
        assertThrows(IllegalArgumentException.class,
                () -> new AwsNitroKmsAttestationPolicy(IMAGE, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AwsNitroKmsAttestationPolicy(IMAGE, Map.of(9, PCR0)));
        assertThrows(IllegalArgumentException.class,
                () -> new AwsNitroKmsAttestationPolicy(IMAGE, Map.of(0, "b".repeat(96))));
        assertThrows(IllegalArgumentException.class,
                () -> new AwsNitroKmsAttestationPolicy(IMAGE, Map.of(0, "not-a-digest")));
    }
}
