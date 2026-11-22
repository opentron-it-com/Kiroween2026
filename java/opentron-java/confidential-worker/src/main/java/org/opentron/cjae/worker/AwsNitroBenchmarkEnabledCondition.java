package org.opentron.cjae.worker;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class AwsNitroBenchmarkEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        boolean healthVsockEnabled = environment.getProperty(
                        "confidential.aws.nitro.health-vsock.enabled", Boolean.class, false)
                && environment.getProperty(
                        "confidential.aws.nitro.verify-on-startup", Boolean.class, false)
                && !environment.getProperty(
                        "confidential.gateway.enabled", Boolean.class, false);
        boolean benchmarkEnabled = environment.getProperty(
                        "confidential.aws.nitro.benchmark.enabled", Boolean.class, false)
                || environment.getProperty(
                        "confidential.aws.nitro.rag-benchmark.enabled", Boolean.class, false);
        return healthVsockEnabled && benchmarkEnabled;
    }
}
