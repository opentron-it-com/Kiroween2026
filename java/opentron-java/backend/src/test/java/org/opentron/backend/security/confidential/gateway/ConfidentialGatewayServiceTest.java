package org.opentron.backend.security.confidential.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class ConfidentialGatewayServiceTest {
    private static final String TENANT = "tenant-a";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void sanitizesAndRestoresDetectedEntities() throws Exception {
        Fixture fixture = new Fixture(NOW);
        ConfidentialGatewayService service = fixture.service();

        ConfidentialGatewayService.SanitizeResult result = service.sanitize(
                TENANT, "Contact Ada at ada@example.com or 212-555-0199.");

        assertFalse(result.sanitizedText().contains("ada@example.com"));
        assertFalse(result.sanitizedText().contains("212-555-0199"));
        assertTrue(result.secureContext().startsWith("v3."));
        assertFalse(result.secureContext().contains("ada@example.com"));
        assertEquals("Contact Ada at ada@example.com or 212-555-0199.",
                service.desanitize(TENANT, result.sanitizedText(), result.secureContext()));
        assertEquals("prototype-rules-2", result.detectorVersion());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), result.expiresAt());
    }

    @Test
    void sanitizesAndRestoresPiiFollowingSupplementaryUnicode() throws Exception {
        ConfidentialGatewayService service = new Fixture(NOW).service();
        String original = "😀 Contact ada@example.com";

        var result = service.sanitize(TENANT, original);

        assertTrue(result.sanitizedText().startsWith("😀 Contact "));
        assertFalse(result.sanitizedText().contains("ada@example.com"));
        assertEquals(original,
                service.desanitize(TENANT, result.sanitizedText(), result.secureContext()));
    }

    @Test
    void sanitizesAndRestoresMultipleSegmentsWithOneContext() throws Exception {
        ConfidentialGatewayService service = new Fixture(NOW).service();

        var result = service.sanitizeSegments(
                TENANT,
                List.of("Email ada@example.com", "Phone 212-555-0199"));

        assertEquals(2, result.sanitizedSegments().size());
        assertFalse(result.sanitizedSegments().get(0).contains("ada@example.com"));
        assertFalse(result.sanitizedSegments().get(1).contains("212-555-0199"));
        assertEquals(
                List.of("Email ada@example.com", "Phone 212-555-0199"),
                service.desanitizeSegments(TENANT, result.sanitizedSegments(), result.secureContext()));
    }

    @Test
    void rejectsEmptyAndOversizedBatches() throws Exception {
        ConfidentialGatewayService service = new Fixture(NOW).service();

        assertThrows(IllegalArgumentException.class,
                () -> service.sanitizeSegments(TENANT, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.sanitizeSegments(TENANT, java.util.Collections.nCopies(101, "x")));
    }

    @Test
    void leavesUnmatchedTextUnchangedWithValidEmptyContext() throws Exception {
        Fixture fixture = new Fixture(NOW);
        ConfidentialGatewayService service = fixture.service();

        ConfidentialGatewayService.SanitizeResult result =
                service.sanitize(TENANT, "No supported entities here.");

        assertEquals("No supported entities here.", result.sanitizedText());
        assertEquals(result.sanitizedText(),
                service.desanitize(TENANT, result.sanitizedText(), result.secureContext()));
    }

    @Test
    void secureContextCannotBeUsedByAnotherTenant() throws Exception {
        Fixture fixture = new Fixture(NOW);
        ConfidentialGatewayService service = fixture.service();
        var result = service.sanitize(TENANT, "ada@example.com");

        assertThrows(SecureContextException.class,
                () -> service.desanitize("tenant-b", result.sanitizedText(), result.secureContext()));
    }

    @Test
    void secureContextIsBoundToAuthenticatedSubject() throws Exception {
        ConfidentialGatewayService service = new Fixture(NOW).service();
        String subjectA = "tenant-a\u0000subject-a";
        String subjectB = "tenant-a\u0000subject-b";
        var result = service.sanitize(subjectA, "ada@example.com");

        assertThrows(SecureContextException.class,
                () -> service.desanitize(subjectB, result.sanitizedText(), result.secureContext()));
    }

    @Test
    void secureContextCanOnlyBeDesanitizedOnceAcrossServiceInstances() throws Exception {
        Fixture fixture = new Fixture(NOW);
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:service-context-replay;DB_CLOSE_DELAY=-1");
        var store = new JdbcSecureContextConsumptionStore(
                new JdbcTemplate(dataSource), fixture.clock);
        ConfidentialGatewayService firstInstance = fixture.service(store);
        ConfidentialGatewayService secondInstance = fixture.service(store);
        var result = firstInstance.sanitize(TENANT, "Contact ada@example.com");

        assertEquals("Contact ada@example.com",
                firstInstance.desanitize(TENANT, result.sanitizedText(), result.secureContext()));
        assertThrows(SecureContextException.class,
                () -> secondInstance.desanitize(
                        TENANT, result.sanitizedText(), result.secureContext()));
    }

    @Test
    void tamperedSecureContextIsRejected() throws Exception {
        Fixture fixture = new Fixture(NOW);
        ConfidentialGatewayService service = fixture.service();
        var result = service.sanitize(TENANT, "ada@example.com");
        String[] contextParts = result.secureContext().split("\\.", -1);
        contextParts[3] = (contextParts[3].startsWith("A") ? "B" : "A")
                + contextParts[3].substring(1);
        String tampered = String.join(".", contextParts);

        assertThrows(SecureContextException.class,
                () -> service.desanitize(TENANT, result.sanitizedText(), tampered));
    }

    @Test
    void expiredSecureContextIsRejected() throws Exception {
        Fixture fixture = new Fixture(NOW);
        ConfidentialGatewayService service = fixture.service();
        var result = service.sanitize(TENANT, "ada@example.com");
        fixture.clock.advance(Duration.ofMinutes(10));

        assertThrows(SecureContextException.class,
                () -> service.desanitize(TENANT, result.sanitizedText(), result.secureContext()));
    }

    @Test
    void secureContextCanBeOpenedAfterKeyRotation() throws Exception {
        Fixture fixture = new Fixture(NOW);
        ConfidentialGatewayService service = fixture.service();
        var result = service.sanitize(TENANT, "ada@example.com");
        fixture.keyProvider.rotate("key-2", generateKey());

        assertEquals("ada@example.com",
                service.desanitize(TENANT, result.sanitizedText(), result.secureContext()));
        assertTrue(fixture.keyProvider.findKey("key-1").isPresent());
    }

    @Test
    void secureContextAuthenticatesItsKeyIdentifier() throws Exception {
        Fixture fixture = new Fixture(NOW);
        ConfidentialGatewayService service = fixture.service();
        var result = service.sanitize(TENANT, "ada@example.com");
        fixture.keyProvider.rotate("key-alias", fixture.keyProvider.currentKey().key());
        String[] parts = result.secureContext().split("\\.", -1);
        parts[1] = "key-alias";
        String tampered = String.join(".", parts);

        assertThrows(SecureContextException.class,
                () -> service.desanitize(TENANT, result.sanitizedText(), tampered));
    }

    @Test
    void prototypeDetectorFindsOnlyItsDocumentedEntityTypes() {
        PrototypeRuleBasedPiiDetector detector = new PrototypeRuleBasedPiiDetector();

        assertEquals(2, detector.detect("ada@example.com 212-555-0199").size());
        assertTrue(detector.detect("Ada Lovelace, 123 Main Street").isEmpty());
    }

    @Test
    void ruleDetectorFindsCommonStructuredIdentifiers() {
        PrototypeRuleBasedPiiDetector detector = new PrototypeRuleBasedPiiDetector();
        String text = "Email ada@example.com, phone +44 20 7946 0958, "
                + "SSN 123-45-6789, card 4111 1111 1111 1111, "
                + "IBAN DE89370400440532013000.";

        assertEquals(
                List.of("EMAIL", "PHONE_NUMBER", "SSN", "CREDIT_CARD", "IBAN"),
                detector.detect(text).stream().map(DetectedEntity::type).toList());
        assertTrue(detector.detect("card 4111 1111 1111 1112").isEmpty());
    }

    @Test
    void compositeDetectorKeepsRuleMatchesAndAddsNonOverlappingModelMatches() {
        PiiDetector rules = new PrototypeRuleBasedPiiDetector();
        PiiDetector model = new PiiDetector() {
            @Override
            public String version() {
                return "test-model";
            }

            @Override
            public List<DetectedEntity> detect(CharSequence text) {
                return List.of(
                        new DetectedEntity("PERSON", 0, 3),
                        new DetectedEntity("PERSON", 4, 7),
                        new DetectedEntity("PERSON", 20, 24));
            }
        };
        CompositePiiDetector detector = new CompositePiiDetector(rules, model);

        List<DetectedEntity> entities =
                detector.detect("Ada ada@example.com John");

        assertEquals(List.of(
                        new DetectedEntity("PERSON", 0, 3),
                        new DetectedEntity("EMAIL", 4, 19),
                        new DetectedEntity("PERSON", 20, 24)),
                entities);
        assertEquals("prototype-rules-2+test-model", detector.version());
    }

    private static SecretKey generateKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }

    private static final class Fixture {
        private final TestKeyProvider keyProvider;
        private final MutableClock clock;

        private Fixture(Instant now) throws Exception {
            keyProvider = new TestKeyProvider(new SecureContextKey("key-1", generateKey()));
            clock = new MutableClock(now);
        }

        private ConfidentialGatewayService service() {
            return service((context, expiresAt) -> true);
        }

        private ConfidentialGatewayService service(
                SecureContextConsumptionStore contextConsumptionStore) {
            AesGcmSecureContextCodec codec = new AesGcmSecureContextCodec(
                    keyProvider, new ObjectMapper(), new SecureRandom(), clock);
            return new ConfidentialGatewayService(
                    new PrototypeRuleBasedPiiDetector(),
                    codec,
                    Duration.ofMinutes(10),
                    new SecureRandom(),
                    clock,
                    new ConfidentialOperationGovernor(64),
                    contextConsumptionStore);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Test clock only supports UTC.");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class TestKeyProvider implements SecureContextKeyProvider {
        private final Map<String, SecureContextKey> keys = new HashMap<>();
        private SecureContextKey currentKey;

        private TestKeyProvider(SecureContextKey currentKey) {
            rotate(currentKey.id(), currentKey.key());
        }

        private void rotate(String id, SecretKey key) {
            currentKey = new SecureContextKey(id, key);
            keys.put(id, currentKey);
        }

        @Override
        public SecureContextKey currentKey() {
            return currentKey;
        }

        @Override
        public Optional<SecureContextKey> findKey(String id) {
            return Optional.ofNullable(keys.get(id));
        }
    }
}
