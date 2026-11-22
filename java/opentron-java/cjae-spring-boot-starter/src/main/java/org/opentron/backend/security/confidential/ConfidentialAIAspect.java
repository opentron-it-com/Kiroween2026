package org.opentron.backend.security.confidential;

import java.util.List;
import java.util.concurrent.CompletionStage;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConfidentialAIAspect {
    private final List<ConfidentialExecutionPolicy> executionPolicies;

    public ConfidentialAIAspect(List<ConfidentialExecutionPolicy> executionPolicies) {
        this.executionPolicies = List.copyOf(executionPolicies);
    }

    @Around("@annotation(org.opentron.backend.security.confidential.ConfidentialAI) "
            + "|| @within(org.opentron.backend.security.confidential.ConfidentialAI)")
    public Object aroundConfidentialAI(ProceedingJoinPoint joinPoint) throws Throwable {
        return requireConfidentialExecution(joinPoint);
    }

    public Object requireConfidentialRuntime(ProceedingJoinPoint joinPoint) throws Throwable {
        return requireConfidentialExecution(joinPoint);
    }

    public Object requireConfidentialExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        if (executionPolicies.size() != 1) {
            throw new ConfidentialExecutionDeniedException(
                    "Confidential execution requires exactly one configured execution policy.");
        }
        if (isAsynchronous(joinPoint)) {
            throw new ConfidentialExecutionDeniedException(
                    "@ConfidentialAI supports synchronous method execution only.");
        }

        executionPolicies.get(0).verifyConfidentialExecution();
        return joinPoint.proceed();
    }

    private static boolean isAsynchronous(ProceedingJoinPoint joinPoint) {
        if (!(joinPoint.getSignature() instanceof MethodSignature signature)) {
            return false;
        }
        Class<?> returnType = signature.getReturnType();
        return CompletionStage.class.isAssignableFrom(returnType)
                || java.util.concurrent.Future.class.isAssignableFrom(returnType)
                || implementsReactivePublisher(returnType);
    }

    private static boolean implementsReactivePublisher(Class<?> type) {
        if (type == null) {
            return false;
        }
        if ("org.reactivestreams.Publisher".equals(type.getName())) {
            return true;
        }
        for (Class<?> implementedInterface : type.getInterfaces()) {
            if (implementsReactivePublisher(implementedInterface)) {
                return true;
            }
        }
        return implementsReactivePublisher(type.getSuperclass());
    }
}
