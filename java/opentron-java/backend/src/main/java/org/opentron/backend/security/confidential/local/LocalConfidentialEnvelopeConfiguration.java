package org.opentron.backend.security.confidential.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentron.backend.security.confidential.ConfidentialExecutionPolicy;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!aws-nitro & !gcp-cvm & !local-untrusted & !prod & !gateway-oidc")
public class LocalConfidentialEnvelopeConfiguration {
    @Bean
    public LocalConfidentialEnvelopeService localConfidentialEnvelopeService(ObjectMapper objectMapper) {
        return new LocalConfidentialEnvelopeService(objectMapper);
    }

    @Bean
    public ConfidentialExecutionPolicy localEncryptedRequestExecutionPolicy() {
        return new LocalEncryptedRequestExecutionPolicy();
    }

    @Bean
    public FilterRegistrationBean<LocalConfidentialEnvelopeFilter> localConfidentialEnvelopeFilter(
            LocalConfidentialEnvelopeService envelopeService,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<LocalConfidentialEnvelopeFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new LocalConfidentialEnvelopeFilter(envelopeService, objectMapper));
        registration.addUrlPatterns("/*");
        registration.setAsyncSupported(true);
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        return registration;
    }
}
