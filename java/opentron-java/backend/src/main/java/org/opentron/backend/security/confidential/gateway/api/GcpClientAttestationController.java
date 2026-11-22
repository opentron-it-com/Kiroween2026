package org.opentron.backend.security.confidential.gateway.api;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.opentron.backend.security.confidential.gateway.ConfidentialOperationGovernor;
import org.opentron.backend.security.confidential.gateway.gcp.GcpGcaAttestationVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/attestation")
@Profile("gcp-cvm & gateway-oidc")
@ConditionalOnProperty(name = "confidential.gateway.enabled", havingValue = "true")
public class GcpClientAttestationController {
    private static final int MAX_REQUEST_BYTES = 256;
    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private static final long WINDOW_NANOS = java.time.Duration.ofMinutes(1).toNanos();

    private final GcpGcaAttestationVerifier attestationVerifier;
    private final ConfidentialOperationGovernor operationGovernor;
    private final ObjectMapper objectMapper;
    private final AtomicReference<RateWindow> rateWindow =
            new AtomicReference<>(new RateWindow(System.nanoTime(), 0));

    public GcpClientAttestationController(
            GcpGcaAttestationVerifier attestationVerifier,
            ConfidentialOperationGovernor operationGovernor,
            ObjectMapper objectMapper) {
        this.attestationVerifier = attestationVerifier;
        this.operationGovernor = operationGovernor;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/gcp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void attestGcpRuntime(
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!allowRequest()) {
            response.setStatus(429);
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Attestation challenge request is too large.");
        }
        byte[] requestBody = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        try {
            if (requestBody.length > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException(
                        "Attestation challenge request is too large.");
            }
            String challenge = readChallenge(requestBody);
            final String token;
            try (ConfidentialOperationGovernor.Permit permit = operationGovernor.acquire()) {
                token = attestationVerifier.attestCurrentRuntime(challenge).getTokenValue();
            }
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            if (request.isSecure()) {
                response.setHeader("Strict-Transport-Security", "max-age=31536000");
            }
            objectMapper.writeValue(response.getOutputStream(),
                    new AttestationResponse(token));
        } finally {
            java.util.Arrays.fill(requestBody, (byte) 0);
        }
    }

    private boolean allowRequest() {
        while (true) {
            long now = System.nanoTime();
            RateWindow current = rateWindow.get();
            RateWindow next = now - current.startedAtNanos() >= WINDOW_NANOS
                    ? new RateWindow(now, 1)
                    : current.requests() >= MAX_REQUESTS_PER_MINUTE
                            ? null
                            : new RateWindow(current.startedAtNanos(), current.requests() + 1);
            if (next == null) {
                return false;
            }
            if (rateWindow.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    private String readChallenge(byte[] requestBody) throws IOException {
        try {
            try (JsonParser parser = objectMapper.getFactory().createParser(requestBody)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode body = objectMapper.readTree(parser);
                if (body == null || !body.isObject() || body.size() != 1
                        || parser.nextToken() != null) {
                    throw new IllegalArgumentException(
                            "Attestation request must contain exactly one challenge field.");
                }
                Iterator<String> fields = body.fieldNames();
                if (!fields.hasNext() || !"challenge".equals(fields.next())) {
                    throw new IllegalArgumentException(
                            "Attestation request must contain a challenge field.");
                }
                JsonNode challenge = body.get("challenge");
                if (challenge == null || !challenge.isTextual()) {
                    throw new IllegalArgumentException(
                            "Attestation challenge must be a string.");
                }
                return challenge.textValue();
            }
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Attestation request JSON is invalid.", failure);
        }
    }

    private record AttestationResponse(String attestationToken) {
    }

    private record RateWindow(long startedAtNanos, int requests) {
    }
}
