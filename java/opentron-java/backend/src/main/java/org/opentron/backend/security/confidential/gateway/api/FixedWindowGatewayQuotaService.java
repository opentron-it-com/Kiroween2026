package org.opentron.backend.security.confidential.gateway.api;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

public final class FixedWindowGatewayQuotaService implements GatewayQuotaService {
    private final int maximumRequests;
    private final long maximumTextCharacters;
    private final int maximumTenants;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, UsageWindow> usageByTenant =
            new ConcurrentHashMap<>();

    public FixedWindowGatewayQuotaService(
            int maximumRequests,
            long maximumTextCharacters,
            int maximumTenants,
            Duration window,
            Clock clock) {
        if (maximumRequests < 1 || maximumTextCharacters < 1 || maximumTenants < 1
                || window == null || window.isNegative() || window.isZero()
                || window.toMillis() < 1 || clock == null) {
            throw new IllegalArgumentException("Gateway quota settings are invalid.");
        }
        this.maximumRequests = maximumRequests;
        this.maximumTextCharacters = maximumTextCharacters;
        this.maximumTenants = maximumTenants;
        this.window = window;
        this.clock = clock;
    }

    @Override
    public void checkAndConsume(GatewayPrincipal principal, int textCharacters) {
        if (principal == null || textCharacters < 0) {
            throw new IllegalArgumentException("Gateway quota request is invalid.");
        }
        long currentWindow = Math.floorDiv(clock.instant().toEpochMilli(), window.toMillis());
        UsageWindow usage = usageFor(principal.tenantId(), currentWindow);
        synchronized (usage) {
            usage.advance(currentWindow);
            if (usage.requests >= maximumRequests
                    || textCharacters > maximumTextCharacters - usage.textCharacters) {
                throw new GatewayQuotaExceededException("Gateway tenant quota exceeded.");
            }
            usage.requests++;
            usage.textCharacters += textCharacters;
        }
    }

    private synchronized UsageWindow usageFor(String tenantId, long currentWindow) {
        UsageWindow existing = usageByTenant.get(tenantId);
        if (existing != null) {
            return existing;
        }
        usageByTenant.entrySet().removeIf(
                entry -> entry.getValue().windowIndex < currentWindow - 1);
        if (usageByTenant.size() >= maximumTenants) {
            throw new GatewayQuotaExceededException(
                    "Gateway tenant capacity quota exceeded.");
        }
        return usageByTenant.computeIfAbsent(tenantId, ignored -> new UsageWindow(currentWindow));
    }

    private static final class UsageWindow {
        private volatile long windowIndex;
        private int requests;
        private long textCharacters;

        private UsageWindow(long windowIndex) {
            this.windowIndex = windowIndex;
        }

        private void advance(long currentWindow) {
            if (currentWindow < windowIndex) {
                throw new GatewayQuotaExceededException(
                        "Gateway request belongs to an expired quota window.");
            }
            if (windowIndex < currentWindow) {
                windowIndex = currentWindow;
                requests = 0;
                textCharacters = 0;
            }
        }
    }
}
