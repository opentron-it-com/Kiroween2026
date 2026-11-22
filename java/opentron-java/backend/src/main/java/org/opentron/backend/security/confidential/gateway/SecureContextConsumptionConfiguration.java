package org.opentron.backend.security.confidential.gateway;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("gateway-oidc")
@ConditionalOnProperty(name = "confidential.gateway.enabled", havingValue = "true")
public class SecureContextConsumptionConfiguration {
    @Bean
    public SecureContextConsumptionStore secureContextConsumptionStore(
            JdbcTemplate jdbcTemplate) {
        return new JdbcSecureContextConsumptionStore(jdbcTemplate, Clock.systemUTC());
    }
}
