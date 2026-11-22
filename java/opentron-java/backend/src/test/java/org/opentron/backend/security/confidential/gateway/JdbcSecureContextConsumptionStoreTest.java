package org.opentron.backend.security.confidential.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcSecureContextConsumptionStoreTest {
    @Test
    void sharedDatabaseAllowsOnlyOneConsumptionAndStoresOnlyTheHash() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:context-replay;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var firstInstance = new JdbcSecureContextConsumptionStore(jdbcTemplate, clock);
        var secondInstance = new JdbcSecureContextConsumptionStore(jdbcTemplate, clock);
        String context = "v3.key.random-nonce.encrypted-payload";
        Instant expiry = clock.instant().plusSeconds(300);

        assertTrue(firstInstance.consume(context, expiry));
        assertFalse(secondInstance.consume(context, expiry));
        assertFalse(firstInstance.consume("already-expired", clock.instant()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM confidential_context_consumption",
                Integer.class));
        assertEquals(64, jdbcTemplate.queryForObject(
                "SELECT LENGTH(context_hash) FROM confidential_context_consumption",
                Integer.class));
    }
}
