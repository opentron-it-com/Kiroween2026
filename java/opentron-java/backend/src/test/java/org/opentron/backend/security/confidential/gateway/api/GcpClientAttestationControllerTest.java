package org.opentron.backend.security.confidential.gateway.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.gateway.ConfidentialOperationGovernor;
import org.opentron.backend.security.confidential.gateway.gcp.GcpAttestationPolicy;
import org.opentron.backend.security.confidential.gateway.gcp.GcpGcaAttestationVerifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GcpClientAttestationControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-26T07:00:00Z");
    private static final String AUDIENCE = "https://cjae-worker.example";
    private static final String CHALLENGE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String TOKEN = "header.payload.signature";

    @Test
    void returnsChallengeBoundVerifiedGcaTokenWithoutBearerAuthentication() throws Exception {
        var policy = new GcpAttestationPolicy(
                AUDIENCE,
                GcpAttestationPolicy.HardwareModel.AMD_SEV,
                java.util.Set.of("trusted-project"),
                java.util.Set.of("us-central1-a"),
                java.util.Set.of("trusted-instance"));
        var verifier = new GcpGcaAttestationVerifier(
                (audience, nonce) -> TOKEN,
                token -> verifiedJwt(token),
                policy,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new java.security.SecureRandom());
        var controller = new GcpClientAttestationController(
                verifier, new ConfidentialOperationGovernor(2), new ObjectMapper());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/v1/attestation/gcp")
                        .contentType("application/json")
                        .content("{\"challenge\":\"" + CHALLENGE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.attestationToken").value(TOKEN));
    }

    private static org.springframework.security.oauth2.jwt.Jwt verifiedJwt(String token) {
        return org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .issuer("https://confidentialcomputing.googleapis.com")
                .subject("https://www.googleapis.com/compute/v1/projects/trusted-project/"
                        + "zones/us-central1-a/instances/trusted-instance")
                .audience(List.of(AUDIENCE))
                .issuedAt(NOW.minusSeconds(30))
                .expiresAt(NOW.plusSeconds(300))
                .claim("nbf", NOW.minusSeconds(30))
                .claim("eat_profile",
                        "https://cloud.google.com/confidential-computing/"
                                + "confidential-vm/docs/token-claims")
                .claim("eat_nonce", CHALLENGE)
                .claim("hwmodel", "GCP_AMD_SEV")
                .claim("secboot", true)
                .claim("support_attributes", List.of("STABLE"))
                .claim("submods", Map.of("gce", Map.of(
                        "zone", "us-central1-a",
                        "project_id", "trusted-project",
                        "instance_name", "trusted-instance")))
                .build();
    }
}
