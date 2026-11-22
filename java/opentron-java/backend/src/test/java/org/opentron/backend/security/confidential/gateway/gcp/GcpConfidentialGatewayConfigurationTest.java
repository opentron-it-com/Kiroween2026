package org.opentron.backend.security.confidential.gateway.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GcpConfidentialGatewayConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GcpConfidentialGatewayConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void wiresGoogleTpmAttestationTokenSourceForSelectedHardware() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=gcp-cvm",
                        "confidential.gcp.attestation.audience=gateway-resource",
                        "confidential.gcp.attestation.hardware-model=AMD_SEV",
                        "confidential.gcp.attestation.allowed-projects=project-a",
                        "confidential.gcp.attestation.allowed-zones=us-central1-a",
                        "confidential.gcp.attestation.allowed-instances=gateway-vm",
                        "confidential.gcp.attestation.gotpm-path=/opt/cjae/bin/gotpm",
                        "confidential.gcp.attestation.event-log-path="
                                + Path.of(System.getProperty("java.io.tmpdir"), "cjae", "tpm", "event.log")
                                        .toAbsolutePath(),
                        "confidential.gcp.wif.audience=//iam.googleapis.com/projects/123456789/locations/global/workloadIdentityPools/pool/providers/provider",
                        "confidential.gcp.kms.key-resource=projects/project-a/locations/global/keyRings/runtime/cryptoKeys/context",
                        "confidential.gcp.kms.context-key-id=context-dek",
                        "confidential.gcp.kms.ciphertext-base64=AQID")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GcpAttestationTokenSource.class);
                    assertThat(context).hasSingleBean(GoTpmGcpAttestationTokenSource.class);
                });
    }
}
