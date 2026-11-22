package org.opentron.backend.pricing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PricingServiceTest {

    @Test
    void hasPricesForCurrentAnthropicAndGeminiModels() {
        PricingService service = new PricingService();

        assertEquals(8.00, service.priceFor("claude-fable-5-2").orElseThrow().inputPerMillionUsd());
        assertEquals(2.00, service.priceFor("claude-sonnet-5-5").orElseThrow().inputPerMillionUsd());
        assertEquals(0.10, service.priceFor("claude-haiku-5-5").orElseThrow().inputPerMillionUsd());
        assertEquals(3.00, service.priceFor("gemini-4-neon").orElseThrow().inputPerMillionUsd());
        assertEquals(1.50, service.priceFor("gemini-4-hydrogen").orElseThrow().inputPerMillionUsd());
    }
}
