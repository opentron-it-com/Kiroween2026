package org.opentron.backend.security.confidential.gateway.sdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Dependency-light HTTP client for the remote Confidential Gateway API. */
public final class ConfidentialGatewayClient {
    private static final int MAX_TEXT_LENGTH = 1_000_000;
    private static final int MAX_CONTEXT_LENGTH = 2_000_000;
    private static final int MAX_RESPONSE_BYTES = 8_000_000;
    private static final int MAX_ATTESTATION_TOKEN_LENGTH = 64 * 1024;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final URI baseUri;
    private final String bearerToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final GcpConfidentialSpacePolicy gcpAttestationPolicy;
    private final GcpAttestationTokenVerifier gcpAttestationVerifier;
    private volatile GcpAttestationTokenVerifier.VerifiedGcpAttestation verifiedAttestation;
    private volatile Instant reattestAfter;

    public ConfidentialGatewayClient(
            URI baseUri,
            String bearerToken,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout) {
        this(baseUri, bearerToken, httpClient, objectMapper, requestTimeout, null);
    }

    public ConfidentialGatewayClient(
            URI baseUri,
            String bearerToken,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout,
            GcpConfidentialSpacePolicy gcpAttestationPolicy) {
        this.baseUri = validateBaseUri(baseUri);
        if (bearerToken == null || bearerToken.length() < 32
                || !bearerToken.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("Gateway bearer token must contain at least 32 characters.");
        }
        this.bearerToken = bearerToken;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Request timeout must be positive.");
        }
        this.requestTimeout = requestTimeout;
        this.gcpAttestationPolicy = gcpAttestationPolicy;
        this.gcpAttestationVerifier = gcpAttestationPolicy == null
                ? null
                : new GcpAttestationTokenVerifier(
                        new GoogleGcpAttestationSigningKeyResolver(objectMapper),
                        objectMapper,
                        Clock.systemUTC());
    }

    public static ConfidentialGatewayClient forGcpConfidentialSpace(
            URI baseUri,
            String bearerToken,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout,
            GcpConfidentialSpacePolicy policy) {
        return new ConfidentialGatewayClient(
                baseUri, bearerToken, httpClient, objectMapper, requestTimeout,
                Objects.requireNonNull(policy, "policy"));
    }

    public SanitizeResult sanitize(String text) {
        if (text == null || text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Text is missing or exceeds the supported size.");
        }
        SanitizeResponse response = post(
                "/v1/confidential/sanitize",
                new SanitizeRequest(text),
                SanitizeResponse.class);
        if (response.sanitizedText() == null || response.secureContext() == null
                || response.detectorVersion() == null || response.expiresAt() == null
                || response.secureContext().length() > MAX_CONTEXT_LENGTH) {
            throw new GatewayClientException(502, "INVALID_GATEWAY_RESPONSE");
        }
        try {
            return new SanitizeResult(
                    response.sanitizedText(),
                    response.secureContext(),
                    response.detectorVersion(),
                    Instant.parse(response.expiresAt()));
        } catch (DateTimeParseException e) {
            throw new GatewayClientException(502, "INVALID_GATEWAY_RESPONSE");
        }
    }

    public GcpAttestationEvidence attestGcpRuntime() {
        if (!"https".equalsIgnoreCase(baseUri.getScheme())) {
            throw new IllegalStateException(
                    "Client attestation requires a TLS-protected HTTPS endpoint.");
        }
        if (gcpAttestationPolicy == null) {
            throw new IllegalStateException(
                    "GCP attestation requires an explicit Confidential Space policy.");
        }
        byte[] nonce = new byte[32];
        SECURE_RANDOM.nextBytes(nonce);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        Arrays.fill(nonce, (byte) 0);

        AttestationResponse response = post(
                "/v1/attestation/gcp",
                new AttestationChallenge(challenge),
                AttestationResponse.class,
                false);
        if (response.attestationToken() == null
                || response.attestationToken().length() > MAX_ATTESTATION_TOKEN_LENGTH
                || !response.attestationToken().matches(
                        "[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            throw new GatewayClientException(502, "INVALID_GATEWAY_RESPONSE");
        }
        GcpAttestationTokenVerifier.VerifiedGcpAttestation verified =
                gcpAttestationVerifier.verify(
                        response.attestationToken(), challenge, gcpAttestationPolicy);
        verifiedAttestation = verified;
        reattestAfter = verified.expiresAt().minusSeconds(30);
        return new GcpAttestationEvidence(
                challenge,
                response.attestationToken(),
                verified.subject(),
                verified.imageDigest(),
                verified.expiresAt());
    }

    public String desanitize(String modelResponse, String secureContext) {
        if (modelResponse == null || modelResponse.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "Model response is missing or exceeds the supported size.");
        }
        if (secureContext == null || secureContext.isBlank()
                || secureContext.length() > MAX_CONTEXT_LENGTH) {
            throw new IllegalArgumentException("Secure context is missing or exceeds the supported size.");
        }

        DesanitizeResponse response = post(
                "/v1/confidential/desanitize",
                new DesanitizeRequest(modelResponse, secureContext),
                DesanitizeResponse.class);
        if (response.desanitizedText() == null) {
            throw new GatewayClientException(502, "INVALID_GATEWAY_RESPONSE");
        }
        return response.desanitizedText();
    }

    public BatchSanitizeResult sanitizeSegments(List<String> segments) {
        validateSegments(segments);
        BatchSanitizeResponse response = post(
                "/v1/confidential/sanitize/batch",
                new BatchSanitizeRequest(segments),
                BatchSanitizeResponse.class);
        if (response.sanitizedSegments() == null
                || response.sanitizedSegments().size() != segments.size()
                || response.sanitizedSegments().stream().anyMatch(Objects::isNull)
                || response.secureContext() == null
                || response.secureContext().length() > MAX_CONTEXT_LENGTH
                || response.detectorVersion() == null
                || response.expiresAt() == null) {
            throw new GatewayClientException(502, "INVALID_GATEWAY_RESPONSE");
        }
        try {
            return new BatchSanitizeResult(
                    List.copyOf(response.sanitizedSegments()),
                    response.secureContext(),
                    response.detectorVersion(),
                    Instant.parse(response.expiresAt()));
        } catch (DateTimeParseException e) {
            throw new GatewayClientException(502, "INVALID_GATEWAY_RESPONSE");
        }
    }

    public List<String> desanitizeSegments(List<String> modelResponses, String secureContext) {
        validateSegments(modelResponses);
        if (secureContext == null || secureContext.isBlank()
                || secureContext.length() > MAX_CONTEXT_LENGTH) {
            throw new IllegalArgumentException("Secure context is missing or exceeds the supported size.");
        }

        BatchDesanitizeResponse response = post(
                "/v1/confidential/desanitize/batch",
                new BatchDesanitizeRequest(modelResponses, secureContext),
                BatchDesanitizeResponse.class);
        if (response.desanitizedResponses() == null
                || response.desanitizedResponses().size() != modelResponses.size()
                || response.desanitizedResponses().stream().anyMatch(Objects::isNull)) {
            throw new GatewayClientException(502, "INVALID_GATEWAY_RESPONSE");
        }
        return List.copyOf(response.desanitizedResponses());
    }

    private <T> T post(String path, Object payload, Class<T> responseType) {
        return post(path, payload, responseType, true);
    }

    private <T> T post(
            String path, Object payload, Class<T> responseType, boolean authenticated) {
        if (authenticated) {
            ensureGcpRuntimeAttestation();
        }
        byte[] requestBody;
        try {
            requestBody = objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new GatewayClientException(0, "REQUEST_ENCODING_FAILED", e);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store");
        if (authenticated) {
            requestBuilder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream responseStream = response.body()) {
                byte[] responseBody = readBounded(responseStream);
                try {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new GatewayClientException(
                                response.statusCode(), errorCode(responseBody));
                    }
                    return objectMapper.readValue(responseBody, responseType);
                } catch (IOException e) {
                    throw new GatewayClientException(502, "INVALID_GATEWAY_RESPONSE");
                } finally {
                    Arrays.fill(responseBody, (byte) 0);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayClientException(0, "REQUEST_INTERRUPTED", e);
        } catch (IOException e) {
            throw new GatewayClientException(0, "GATEWAY_UNAVAILABLE", e);
        } finally {
            Arrays.fill(requestBody, (byte) 0);
        }
    }

    private void ensureGcpRuntimeAttestation() {
        if (gcpAttestationPolicy == null) {
            return;
        }
        Instant now = Instant.now();
        if (verifiedAttestation != null && reattestAfter != null
                && now.isBefore(reattestAfter)) {
            return;
        }
        synchronized (this) {
            now = Instant.now();
            if (verifiedAttestation == null || reattestAfter == null
                    || !now.isBefore(reattestAfter)) {
                attestGcpRuntime();
            }
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_RESPONSE_BYTES) {
                throw new GatewayClientException(502, "GATEWAY_RESPONSE_TOO_LARGE");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private String errorCode(byte[] responseBody) {
        try {
            JsonNode code = objectMapper.readTree(responseBody).get("code");
            if (code != null && code.isTextual()
                    && code.asText().matches("[A-Z][A-Z0-9_]{0,63}")) {
                return code.asText();
            }
        } catch (IOException ignored) {
            return "GATEWAY_ERROR";
        }
        return "GATEWAY_ERROR";
    }

    private static URI validateBaseUri(URI uri) {
        Objects.requireNonNull(uri, "baseUri");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);

        if (!uri.isAbsolute() || host == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(scheme.equals("https") || (scheme.equals("http") && loopback))
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException(
                    "Gateway URI must use HTTPS, except for loopback HTTP development, and contain no path or credentials.");
        }
        return URI.create(scheme + "://" + uri.getRawAuthority() + "/");
    }

    private static void validateSegments(List<String> segments) {
        if (segments == null || segments.isEmpty() || segments.size() > 100) {
            throw new IllegalArgumentException("Between 1 and 100 text segments are required.");
        }
        long totalLength = 0;
        for (String segment : segments) {
            if (segment == null || segment.length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("Text segment is missing or exceeds the supported size.");
            }
            totalLength += segment.length();
            if (totalLength > 5_000_000) {
                throw new IllegalArgumentException("Text segments exceed the supported total size.");
            }
        }
    }

    public record SanitizeResult(
            String sanitizedText,
            String secureContext,
            String detectorVersion,
            Instant expiresAt) {
    }

    public record BatchSanitizeResult(
            List<String> sanitizedSegments,
            String secureContext,
            String detectorVersion,
            Instant expiresAt) {
    }

    public record GcpAttestationEvidence(
            String challenge,
            String compactToken,
            String verifiedSubject,
            String verifiedImageDigest,
            Instant expiresAt) {
    }

    private record AttestationChallenge(String challenge) {
    }

    private record AttestationResponse(String attestationToken) {
    }

    private record SanitizeRequest(String text) {
    }

    private record SanitizeResponse(
            String sanitizedText,
            String secureContext,
            String detectorVersion,
            String expiresAt) {
    }

    private record DesanitizeRequest(String modelResponse, String secureContext) {
    }

    private record DesanitizeResponse(String desanitizedText) {
    }

    private record BatchSanitizeRequest(List<String> segments) {
    }

    private record BatchSanitizeResponse(
            List<String> sanitizedSegments,
            String secureContext,
            String detectorVersion,
            String expiresAt) {
    }

    private record BatchDesanitizeRequest(
            List<String> modelResponses,
            String secureContext) {
    }

    private record BatchDesanitizeResponse(List<String> desanitizedResponses) {
    }
}
