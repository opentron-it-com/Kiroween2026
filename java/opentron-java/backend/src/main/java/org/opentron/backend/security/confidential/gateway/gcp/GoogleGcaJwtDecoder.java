package org.opentron.backend.security.confidential.gateway.gcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.jwt.Jwt;

/** Verifies RS256 signatures against Google's pinned GCA issuer JWKS URI. */
public final class GoogleGcaJwtDecoder implements GcpAttestationJwtDecoder {
    static final URI GOOGLE_GCA_JWKS_URI = URI.create(
            "https://www.googleapis.com/service_accounts/v1/metadata/jwk/"
                    + "signer@confidentialspace-sign.iam.gserviceaccount.com");
    private static final int MAX_JWT_BYTES = 64 * 1024;
    private static final int MAX_JWKS_BYTES = 256 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final GcpHttpTransport transport;
    private final ObjectMapper mapper;

    public GoogleGcaJwtDecoder(GcpHttpTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    public Jwt decode(String compactJwt) {
        if (compactJwt == null || compactJwt.length() > MAX_JWT_BYTES) {
            throw new GcpAttestationException("GCA JWT is missing or oversized.");
        }
        String[] parts = compactJwt.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new GcpAttestationException("GCA evidence is not a compact JWT.");
        }
        try {
            JsonNode header = parseSegment(parts[0]);
            if (!"RS256".equals(text(header, "alg"))) {
                throw new GcpAttestationException("GCA JWT algorithm is not the pinned RS256 algorithm.");
            }
            if (header.has("crit") || header.has("jku") || header.has("jwk")
                    || header.has("x5u") || header.has("x5c")
                    || hasUnsupportedHeader(header)) {
                throw new GcpAttestationException("GCA JWT contains unsupported JOSE header parameters.");
            }
            String kid = text(header, "kid");
            if (kid == null || !KEY_ID.matcher(kid).matches()) {
                throw new GcpAttestationException("GCA JWT key identifier is invalid.");
            }
            verifySignature(parts, findKey(kid));

            JsonNode payload = parseSegment(parts[1]);
            Map<String, Object> headers = mapper.convertValue(
                    header, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> claims = mapper.convertValue(
                    payload, new TypeReference<Map<String, Object>>() {});
            Instant issuedAt = epoch(claims.get("iat"));
            Instant expiresAt = epoch(claims.get("exp"));
            if (issuedAt == null || expiresAt == null) {
                throw new GcpAttestationException("GCA JWT is missing valid issue or expiration times.");
            }
            return new Jwt(compactJwt, issuedAt, expiresAt, headers, claims);
        } catch (GcpAttestationException e) {
            throw e;
        } catch (Exception e) {
            throw new GcpAttestationException("GCA JWT signature or encoding is invalid.", e);
        }
    }

    private RSAPublicKey findKey(String keyId) throws Exception {
        GcpHttpTransport.HttpResponse response = transport.postOrGet(
                "GET",
                GOOGLE_GCA_JWKS_URI,
                Map.of("Accept", "application/json"),
                null,
                REQUEST_TIMEOUT,
                MAX_JWKS_BYTES);
        if (response.statusCode() != 200) {
            response.clear();
            throw new GcpAttestationException("Google GCA signing keys could not be retrieved.");
        }
        byte[] jwksBytes = response.body();
        JsonNode keys;
        try {
            keys = parseJson(jwksBytes);
        } finally {
            java.util.Arrays.fill(jwksBytes, (byte) 0);
            response.clear();
        }
        JsonNode key = null;
        for (JsonNode candidate : keys.path("keys")) {
            if (keyId.equals(text(candidate, "kid"))) {
                if (key != null) {
                    throw new GcpAttestationException("Google GCA JWKS contains a duplicate key identifier.");
                }
                key = candidate;
            }
        }
        if (key == null || !"RSA".equals(text(key, "kty"))
                || !"RS256".equals(text(key, "alg"))
                || (text(key, "use") != null && !"sig".equals(text(key, "use")))) {
            throw new GcpAttestationException("No compatible Google GCA signing key was found.");
        }
        BigInteger modulus = unsignedBase64Url(text(key, "n"));
        BigInteger exponent = unsignedBase64Url(text(key, "e"));
        if (modulus.bitLength() < 2048 || modulus.bitLength() > 8192
                || exponent.compareTo(BigInteger.valueOf(3)) < 0 || !exponent.testBit(0)) {
            throw new GcpAttestationException("Google GCA RSA signing key parameters are invalid.");
        }
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    private JsonNode parseSegment(String segment) throws Exception {
        return parseJson(Base64.getUrlDecoder().decode(segment));
    }

    private JsonNode parseJson(byte[] json) throws Exception {
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = mapper.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) {
                throw new GcpAttestationException("GCA JWT or JWKS must contain one JSON object.");
            }
            return node;
        }
    }

    private static void verifySignature(String[] parts, RSAPublicKey key) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
            throw new GcpAttestationException("GCA JWT signature is invalid.");
        }
    }

    private static BigInteger unsignedBase64Url(String value) {
        if (value == null || value.isBlank()) {
            throw new GcpAttestationException("Google GCA JWKS RSA key is malformed.");
        }
        return new BigInteger(1, Base64.getUrlDecoder().decode(value));
    }

    private static Instant epoch(Object value) {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger)) {
            return null;
        }
        try {
            long epochSeconds = value instanceof BigInteger integer
                    ? integer.longValueExact() : ((Number) value).longValue();
            return Instant.ofEpochSecond(epochSeconds);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasUnsupportedHeader(JsonNode header) {
        var fields = header.fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            if (!"alg".equals(name) && !"kid".equals(name) && !"typ".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
