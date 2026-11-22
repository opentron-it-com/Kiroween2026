package org.opentron.backend.security.confidential.gateway.api;

import org.opentron.backend.security.confidential.gateway.ConfidentialOperationGovernor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ConfidentialOperationGovernorConfiguration {
    @Bean
    public ConfidentialOperationGovernor confidentialOperationGovernor(
            @Value("${confidential.gateway.max-concurrent-operations:64}")
                    int maximumConcurrentOperations) {
        return new ConfidentialOperationGovernor(maximumConcurrentOperations);
    }
}
