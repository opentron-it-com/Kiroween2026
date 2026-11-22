package org.opentron.backend.security.confidential.gateway.aws;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Values for KMS key-policy StringEquals conditions. PCR/image enforcement
 * occurs only if these conditions are installed in the applicable AWS KMS key
 * policy; this descriptor does not provision or verify that policy.
 */
public final class AwsNitroKmsAttestationPolicy {
    public static final String IMAGE_SHA384_CONDITION =
            "kms:RecipientAttestation:ImageSha384";
    private static final Pattern SHA384_HEX = Pattern.compile("[0-9a-fA-F]{96}");

    private final Map<String, String> stringEqualsConditions;

    public AwsNitroKmsAttestationPolicy(String imageSha384, Map<Integer, String> pcrs) {
        requireSha384(imageSha384, "ImageSha384");
        Objects.requireNonNull(pcrs, "PCR requirements are required.");
        if (pcrs.isEmpty()) {
            throw new IllegalArgumentException("At least one PCR policy requirement is required.");
        }
        for (Map.Entry<Integer, String> entry : pcrs.entrySet()) {
            Integer index = entry.getKey();
            if (index == null || index < 0 || index > 8) {
                throw new IllegalArgumentException("Nitro PCR index must be between 0 and 8.");
            }
            requireSha384(entry.getValue(), "PCR" + index);
        }
        String pcr0 = pcrs.get(0);
        if (pcr0 != null && !pcr0.equalsIgnoreCase(imageSha384)) {
            throw new IllegalArgumentException(
                    "ImageSha384 and PCR0 must express the same AWS image measurement.");
        }

        Map<String, String> conditions = new LinkedHashMap<>();
        conditions.put(IMAGE_SHA384_CONDITION, imageSha384.toLowerCase(Locale.ROOT));
        pcrs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Integer index = entry.getKey();
            conditions.put(
                    "kms:RecipientAttestation:PCR" + index,
                    entry.getValue().toLowerCase(Locale.ROOT));
        });
        this.stringEqualsConditions = Map.copyOf(conditions);
    }

    /**
     * Map to place under {@code Condition.StringEquals} in the KMS key policy
     * statement granting {@code kms:Decrypt}. Applying that policy is external
     * provisioning and is not performed or verified by this adapter.
     */
    public Map<String, String> stringEqualsConditions() {
        return stringEqualsConditions;
    }

    private static void requireSha384(String value, String field) {
        if (value == null || !SHA384_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a 96-character SHA-384 hex value.");
        }
    }
}
