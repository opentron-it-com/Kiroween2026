package org.opentron.cjae.starter;

import java.util.List;

import org.opentron.backend.security.confidential.ConfidentialAIAspect;
import org.opentron.backend.security.confidential.ConfidentialExecutionPolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@AutoConfiguration
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class ConfidentialAIStarterAutoConfiguration {
    @Bean
    public ConfidentialAIAspect confidentialAIAspect(
            List<ConfidentialExecutionPolicy> executionPolicies) {
        return new ConfidentialAIAspect(executionPolicies);
    }
}
