package org.opentron.backend.security.confidential.gateway.aws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.ConfidentialRuntimeVerifier;
import org.opentron.backend.security.confidential.gateway.SecureContextKeyProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AwsNitroConfidentialGatewayConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AwsNitroConfidentialGatewayConfiguration.class)
            .withBean(
                    AwsNitroEnclaveAttestationSource.class,
                    () -> (nonce, publicKey) -> new byte[] { 1 })
            .withBean(
                    ParentVsockKmsTransport.class,
                    () -> request -> new AwsNitroKmsDecryptResponse(new byte[] { 1 }));

    @Test
    void buildsNitroKeyProviderVerifierAndPolicyFromExternalProperties() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=aws-nitro",
                        "confidential.aws.nitro.kms.key-id=arn:aws:kms:us-east-1:123456789012:key/example",
                        "confidential.aws.nitro.kms.ciphertext-blob-base64=AQID",
                        "confidential.aws.nitro.kms.context-key-id=context-dek",
                        "confidential.aws.nitro.kms.policy-export-enabled=true",
                        "confidential.aws.nitro.kms.image-sha384=" + "a".repeat(96),
                        "confidential.aws.nitro.kms.required-pcrs=0=" + "a".repeat(96)
                                + ",1=" + "b".repeat(96))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SecureContextKeyProvider.class);
                    assertThat(context).hasSingleBean(ConfidentialRuntimeVerifier.class);
                    assertThat(context).hasSingleBean(AwsNitroKmsAttestationPolicy.class);
                    assertThat(context.getBean(AwsNitroKmsAttestationPolicy.class)
                                    .stringEqualsConditions())
                            .containsEntry(AwsNitroKmsAttestationPolicy.IMAGE_SHA384_CONDITION,
                                    "a".repeat(96))
                            .containsEntry("kms:RecipientAttestation:PCR1", "b".repeat(96));
                });
    }

    @Test
    void wiresPanamaNsmSourceWhenNoDeploymentOverrideIsProvided() {
        new ApplicationContextRunner()
                .withUserConfiguration(AwsNitroConfidentialGatewayConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=aws-nitro",
                        "confidential.aws.nitro.kms.key-id=key",
                        "confidential.aws.nitro.kms.ciphertext-blob-base64=AQID",
                        "confidential.aws.nitro.kms.context-key-id=context-dek",
                        "confidential.aws.nitro.kms.policy-export-enabled=true",
                        "confidential.aws.nitro.kms.image-sha384=" + "a".repeat(96),
                        "confidential.aws.nitro.kms.required-pcrs=0=" + "a".repeat(96))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AwsNitroEnclaveAttestationSource.class);
                    assertThat(context.getBean(AwsNitroEnclaveAttestationSource.class))
                            .isInstanceOf(PanamaNitroEnclaveAttestationSource.class);
                });
    }

    @Test
    void rejectsInvalidPcrPropertySyntax() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=aws-nitro",
                        "confidential.aws.nitro.kms.key-id=key",
                        "confidential.aws.nitro.kms.ciphertext-blob-base64=AQID",
                        "confidential.aws.nitro.kms.context-key-id=context-dek",
                        "confidential.aws.nitro.kms.policy-export-enabled=true",
                        "confidential.aws.nitro.kms.image-sha384=" + "a".repeat(96),
                        "confidential.aws.nitro.kms.required-pcrs=one")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining(
                            "index=sha384hex");
                });
    }

    @Test
    void startsWithoutEmbeddingSelfReferentialImageMeasurements() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=aws-nitro",
                        "confidential.aws.nitro.kms.key-id=key",
                        "confidential.aws.nitro.kms.ciphertext-blob-base64=AQID",
                        "confidential.aws.nitro.kms.context-key-id=context-dek")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AwsNitroKmsAttestationPolicy.class);
                });
    }
}
