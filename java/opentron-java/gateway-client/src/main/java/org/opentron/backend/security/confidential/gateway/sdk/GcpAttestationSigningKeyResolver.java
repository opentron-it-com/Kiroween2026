package org.opentron.backend.security.confidential.gateway.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@FunctionalInterface
interface GcpAttestationSigningKeyResolver {
    RSAPublicKey resolve(String keyId);
}

final class GoogleGcpAttestationSigningKeyResolver
        implements GcpAttestationSigningKeyResolver {
    static final URI JWKS_URI = URI.create(
            "https://www.googleapis.com/service_accounts/v1/metadata/jwk/"
                    + "signer@confidentialspace-sign.iam.gserviceaccount.com");
    private static final int MAX_JWKS_BYTES = 256 * 1024;
    private static final int MAX_KEY_COUNT = 32;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration KEY_CACHE_LIFETIME = Duration.ofMinutes(5);
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedKey> keys = new HashMap<>();

    GoogleGcpAttestationSigningKeyResolver(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder()
                        .connectTimeout(REQUEST_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                objectMapper);
    }

    GoogleGcpAttestationSigningKeyResolver(
            HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized RSAPublicKey resolve(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new GcpAttestationException("GCA signing key identifier is invalid.");
        }
        CachedKey cached = keys.get(keyId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.key();
        }
        refreshKeys();
        CachedKey refreshed = keys.get(keyId);
        if (refreshed == null) {
            throw new GcpAttestationException(
                    "No matching key exists in Google's GCA signing JWKS.");
        }
        return refreshed.key();
    }

    private void refreshKeys() {
        HttpRequest request = HttpRequest.newBuilder(JWKS_URI)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) {
                body = readBounded(stream);
            }
            try {
                if (response.statusCode() != 200) {
                    throw new GcpAttestationException(
                            "Google GCA signing keys could not be retrieved.");
                }
                Map<String, CachedKey> updated = parseKeys(body);
                if (updated.isEmpty()) {
                    throw new GcpAttestationException(
                            "Google GCA signing JWKS contains no valid RSA signing keys.");
                }
                keys.clear();
                keys.putAll(updated);
            } finally {
                Arrays.fill(body, (byte) 0);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new GcpAttestationException(
                    "Google GCA signing key retrieval was interrupted.", failure);
        } catch (IOException failure) {
            throw new GcpAttestationException(
                    "Google GCA signing keys could not be retrieved.", failure);
        }
    }

    private Map<String, CachedKey> parseKeys(byte[] body) {
        try (JsonParser parser = objectMapper.getFactory().createParser(body)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null
                    || !root.path("keys").isArray()
                    || root.path("keys").isEmpty()
                    || root.path("keys").size() > MAX_KEY_COUNT) {
                throw new GcpAttestationException("Google GCA signing JWKS is malformed.");
            }
            Map<String, CachedKey> updated = new HashMap<>();
            for (JsonNode entry : root.path("keys")) {
                String kid = text(entry, "kid");
                if (kid == null || !KEY_ID.matcher(kid).matches()) {
                    continue;
                }
                if (updated.containsKey(kid)) {
                    throw new GcpAttestationException(
                            "Google GCA signing JWKS contains duplicate key identifiers.");
                }
                if (!"RSA".equals(text(entry, "kty"))
                        || !"RS256".equals(text(entry, "alg"))
                        || (text(entry, "use") != null
                                && !"sig".equals(text(entry, "use")))) {
                    continue;
                }
                BigInteger modulus = unsignedBase64Url(text(entry, "n"));
                BigInteger exponent = unsignedBase64Url(text(entry, "e"));
                if (modulus.bitLength() < 2048 || modulus.bitLength() > 8192
                        || exponent.compareTo(BigInteger.valueOf(3)) < 0
                        || !exponent.testBit(0)) {
                    throw new GcpAttestationException(
                            "Google GCA RSA signing key parameters are invalid.");
                }
                RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(modulus, exponent));
                updated.put(kid,
                        new CachedKey(publicKey, Instant.now().plus(KEY_CACHE_LIFETIME)));
            }
            return updated;
        } catch (GcpAttestationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new GcpAttestationException(
                    "Google GCA signing JWKS is malformed.", failure);
        }
    }

    private static byte[] readBounded(InputStream stream) throws IOException {
        byte[] response = stream.readNBytes(MAX_JWKS_BYTES + 1);
        if (response.length > MAX_JWKS_BYTES || stream.read() != -1) {
            Arrays.fill(response, (byte) 0);
            throw new GcpAttestationException("Google GCA signing JWKS is oversized.");
        }
        return response;
    }

    private static BigInteger unsignedBase64Url(String value) {
        if (value == null || value.isBlank()) {
            throw new GcpAttestationException("Google GCA RSA signing key is malformed.");
        }
        return new BigInteger(1, Base64.getUrlDecoder().decode(value));
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private record CachedKey(RSAPublicKey key, Instant expiresAt) {
    }
}
