package org.opentron.cjae.worker;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class AwsNitroHealthVsockEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return context.getEnvironment().getProperty(
                        "confidential.aws.nitro.health-vsock.enabled", Boolean.class, false)
                && context.getEnvironment().getProperty(
                        "confidential.aws.nitro.verify-on-startup", Boolean.class, false)
                && !context.getEnvironment().getProperty(
                        "confidential.gateway.enabled", Boolean.class, false);
    }
}
