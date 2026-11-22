package org.opentron.backend.security.confidential.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!onnx-pii")
public class GatewayPiiDetectorConfiguration {
    @Bean
    public PiiDetector prototypeRuleBasedPiiDetector() {
        return new PrototypeRuleBasedPiiDetector();
    }
}
