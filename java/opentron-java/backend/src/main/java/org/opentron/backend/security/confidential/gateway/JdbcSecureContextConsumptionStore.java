package org.opentron.backend.security.confidential.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcSecureContextConsumptionStore
        implements SecureContextConsumptionStore {
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public JdbcSecureContextConsumptionStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS confidential_context_consumption (
                    context_hash CHAR(64) PRIMARY KEY,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS confidential_context_consumption_expires_at_idx
                ON confidential_context_consumption (expires_at)
                """);
    }

    @Override
    public boolean consume(String secureContext, Instant expiresAt) {
        if (secureContext == null || secureContext.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("Secure context consumption parameters are invalid.");
        }
        if (!expiresAt.isAfter(clock.instant())) {
            return false;
        }
        String contextHash = sha256(secureContext);
        Timestamp expiration = Timestamp.from(expiresAt);
        jdbcTemplate.update(
                "DELETE FROM confidential_context_consumption WHERE expires_at <= ?",
                Timestamp.from(clock.instant()));
        try {
            jdbcTemplate.update(
                    "INSERT INTO confidential_context_consumption (context_hash, expires_at) "
                            + "VALUES (?, ?)",
                    contextHash,
                    expiration);
            return true;
        } catch (DuplicateKeyException alreadyConsumed) {
            return false;
        }
    }

    private static String sha256(String secureContext) {
        byte[] encoded = secureContext.getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available.", impossible);
        } finally {
            java.util.Arrays.fill(encoded, (byte) 0);
        }
    }
}
