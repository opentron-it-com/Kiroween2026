package org.opentron.backend.security.confidential.gateway.api;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class FixedWindowGatewayQuotaServiceTest {
    private static final GatewayPrincipal TENANT_ONE =
            new GatewayPrincipal("user-one", "tenant-one", Set.of("gateway:sanitize"));
    private static final GatewayPrincipal TENANT_TWO =
            new GatewayPrincipal("user-two", "tenant-two", Set.of("gateway:sanitize"));
    private static final Instant START = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void enforcesRequestAndTextBudgetsIndependentlyPerTenant() {
        var service = new FixedWindowGatewayQuotaService(
                2, 10, 10, Duration.ofMinutes(1), Clock.fixed(START, ZoneOffset.UTC));

        service.checkAndConsume(TENANT_ONE, 6);
        assertThrows(
                GatewayQuotaExceededException.class,
                () -> service.checkAndConsume(TENANT_ONE, 5));
        service.checkAndConsume(TENANT_ONE, 4);
        assertThrows(
                GatewayQuotaExceededException.class,
                () -> service.checkAndConsume(TENANT_ONE, 0));

        service.checkAndConsume(TENANT_TWO, 10);
    }

    @Test
    void resetsUsageAtWindowBoundaryAndRejectsOldWindowCalls() {
        var clock = new AdjustableClock(START);
        var service = new FixedWindowGatewayQuotaService(
                1, 10, 10, Duration.ofMinutes(1), clock);
        service.checkAndConsume(TENANT_ONE, 10);

        clock.set(START.plusSeconds(60));
        service.checkAndConsume(TENANT_ONE, 10);

        clock.set(START.plusSeconds(30));
        assertThrows(
                GatewayQuotaExceededException.class,
                () -> service.checkAndConsume(TENANT_ONE, 0));
    }

    @Test
    void boundsTenantStateAndRejectsInvalidSettings() {
        var service = new FixedWindowGatewayQuotaService(
                10, 100, 1, Duration.ofMinutes(1), Clock.fixed(START, ZoneOffset.UTC));
        service.checkAndConsume(TENANT_ONE, 0);
        assertThrows(
                GatewayQuotaExceededException.class,
                () -> service.checkAndConsume(TENANT_TWO, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FixedWindowGatewayQuotaService(
                        0, 100, 1, Duration.ofMinutes(1), Clock.fixed(START, ZoneOffset.UTC)));
    }

    private static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private AdjustableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        private void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("Test clock only supports UTC.");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
