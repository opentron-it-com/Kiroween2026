package org.opentron.backend.security.confidential.gateway.gcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import javax.crypto.spec.SecretKeySpec;
import org.opentron.backend.security.confidential.gateway.SecureContextKey;
import org.opentron.backend.security.confidential.gateway.SecureContextKeyProvider;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Releases a configured encrypted context DEK only after fresh local GCA
 * validation, then exchanges that exact token through WIF before Cloud KMS decrypt.
 */
public final class GcpWifKmsSecureContextKeyProvider implements SecureContextKeyProvider {
    private static final URI STS_URI = URI.create("https://sts.googleapis.com/v1/token");
    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String SUBJECT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:jwt";
    private static final String REQUESTED_TOKEN_TYPE =
            "urn:ietf:params:oauth:token-type:access_token";
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final long KEY_CACHE_TTL_NANOS = Duration.ofSeconds(60).toNanos();
    private static final Pattern KMS_RESOURCE = Pattern.compile(
            "projects/[A-Za-z0-9-]+/locations/[A-Za-z0-9-]+/keyRings/[A-Za-z0-9_-]+/cryptoKeys/[A-Za-z0-9_-]+");
    private static final Pattern WIF_AUDIENCE = Pattern.compile(
            "//iam\\.googleapis\\.com/projects/[0-9]+/locations/global/"
                    + "workloadIdentityPools/[A-Za-z0-9_-]+/providers/[A-Za-z0-9_-]+");

    private final GcpGcaAttestationVerifier attestationVerifier;
    private final GcpHttpTransport transport;
    private final ObjectMapper mapper;
    private final String wifAudience;
    private final String kmsKeyResource;
    private final String contextKeyId;
    private final String ciphertext;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile CachedKey cachedKey;

    public GcpWifKmsSecureContextKeyProvider(
            GcpGcaAttestationVerifier attestationVerifier,
            GcpHttpTransport transport,
            ObjectMapper mapper,
            String wifAudience,
            String kmsKeyResource,
            String contextKeyId,
            String ciphertext) {
        if (wifAudience == null || !WIF_AUDIENCE.matcher(wifAudience).matches()) {
            throw new IllegalArgumentException("A canonical Workload Identity Provider audience is required.");
        }
        if (kmsKeyResource == null || !KMS_RESOURCE.matcher(kmsKeyResource).matches()) {
            throw new IllegalArgumentException("A canonical Cloud KMS CryptoKey resource is required.");
        }
        if (contextKeyId == null || !contextKeyId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Context key identifier is invalid.");
        }
        if (ciphertext == null || ciphertext.isBlank() || ciphertext.length() > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("A bounded pre-encrypted context DEK is required.");
        }
        try {
            if (Base64.getDecoder().decode(ciphertext).length == 0) {
                throw new IllegalArgumentException("Encrypted context DEK is empty.");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Encrypted context DEK must be Base64 encoded.", e);
        }
        this.attestationVerifier = attestationVerifier;
        this.transport = transport;
        this.mapper = mapper;
        this.wifAudience = wifAudience;
        this.kmsKeyResource = kmsKeyResource;
        this.contextKeyId = contextKeyId;
        this.ciphertext = ciphertext;
    }

    @Override
    public SecureContextKey currentKey() {
        long now = System.nanoTime();
        CachedKey cached = cachedKey;
        if (cached != null && now - cached.expiresAtNanos() < 0) {
            return cached.key();
        }
        refreshLock.lock();
        try {
            now = System.nanoTime();
            cached = cachedKey;
            if (cached != null && now - cached.expiresAtNanos() < 0) {
                return cached.key();
            }
            SecureContextKey key = releaseCurrentKey();
            cachedKey = new CachedKey(key, System.nanoTime() + KEY_CACHE_TTL_NANOS);
            return key;
        } finally {
            refreshLock.unlock();
        }
    }

    private SecureContextKey releaseCurrentKey() {
        Jwt attestation = attestationVerifier.attestCurrentRuntime();
        String accessToken = exchangeForAccessToken(attestation.getTokenValue());
        byte[] dek = decrypt(accessToken);
        try {
            if (dek.length != 32) {
                throw new GcpAttestationException("Cloud KMS did not return a 256-bit context DEK.");
            }
            return new SecureContextKey(contextKeyId, new SecretKeySpec(dek, "AES"));
        } finally {
            java.util.Arrays.fill(dek, (byte) 0);
        }
    }

    @Override
    public Optional<SecureContextKey> findKey(String id) {
        return contextKeyId.equals(id) ? Optional.of(currentKey()) : Optional.empty();
    }

    private record CachedKey(SecureContextKey key, long expiresAtNanos) {
    }

    private String exchangeForAccessToken(String attestationJwt) {
        String form = "grant_type=" + encode(GRANT_TYPE)
                + "&audience=" + encode(wifAudience)
                + "&requested_token_type=" + encode(REQUESTED_TOKEN_TYPE)
                + "&subject_token_type=" + encode(SUBJECT_TOKEN_TYPE)
                + "&subject_token=" + encode(attestationJwt)
                + "&scope=" + encode(CLOUD_PLATFORM_SCOPE);
        byte[] requestBody = form.getBytes(StandardCharsets.UTF_8);
        try {
            GcpHttpTransport.HttpResponse response = transport.postOrGet(
                    "POST",
                    STS_URI,
                    Map.of(
                            "Content-Type", "application/x-www-form-urlencoded",
                            "Accept", "application/json"),
                    requestBody,
                    REQUEST_TIMEOUT,
                    MAX_RESPONSE_BYTES);
            JsonNode json = parseSuccess(response, "WIF STS token exchange");
            String token = text(json, "access_token");
            String tokenType = text(json, "token_type");
            JsonNode expiresIn = json.get("expires_in");
            if (token == null || token.length() > 16_384 || !"Bearer".equalsIgnoreCase(tokenType)
                    || expiresIn == null || !expiresIn.canConvertToInt()
                    || expiresIn.intValue() < 1 || expiresIn.intValue() > 3600) {
                throw new GcpAttestationException("WIF STS returned an invalid access token.");
            }
            return token;
        } finally {
            java.util.Arrays.fill(requestBody, (byte) 0);
        }
    }

    private byte[] decrypt(String accessToken) {
        URI uri = URI.create("https://cloudkms.googleapis.com/v1/"
                + kmsKeyResource + ":decrypt");
        byte[] requestBody = ("{\"ciphertext\":\"" + ciphertext + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        try {
            GcpHttpTransport.HttpResponse response = transport.postOrGet(
                    "POST",
                    uri,
                    Map.of(
                            "Authorization", "Bearer " + accessToken,
                            "Content-Type", "application/json",
                            "Accept", "application/json"),
                    requestBody,
                    REQUEST_TIMEOUT,
                    MAX_RESPONSE_BYTES);
            JsonNode json = parseSuccess(response, "Cloud KMS decrypt");
            String encodedPlaintext = text(json, "plaintext");
            if (encodedPlaintext == null || encodedPlaintext.length() > MAX_RESPONSE_BYTES) {
                throw new GcpAttestationException("Cloud KMS returned no bounded plaintext.");
            }
            try {
                return Base64.getDecoder().decode(encodedPlaintext);
            } catch (IllegalArgumentException e) {
                throw new GcpAttestationException("Cloud KMS plaintext is not valid Base64.", e);
            }
        } finally {
            java.util.Arrays.fill(requestBody, (byte) 0);
        }
    }

    private JsonNode parseSuccess(GcpHttpTransport.HttpResponse response, String operation) {
        byte[] body = response.body();
        try {
            if (response.statusCode() != 200) {
                throw new GcpAttestationException(operation + " was rejected.");
            }
            try (JsonParser parser = mapper.getFactory().createParser(body)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode node = mapper.readTree(parser);
                if (node == null || !node.isObject() || parser.nextToken() != null) {
                    throw new GcpAttestationException(operation + " returned invalid JSON.");
                }
                return node;
            } catch (GcpAttestationException e) {
                throw e;
            } catch (Exception e) {
                throw new GcpAttestationException(operation + " returned invalid JSON.", e);
            }
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
            response.clear();
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
