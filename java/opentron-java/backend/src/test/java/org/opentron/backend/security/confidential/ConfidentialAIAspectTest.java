package org.opentron.backend.security.confidential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.util.List;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

class ConfidentialAIAspectTest {
    @Test
    void proceedsWhenRuntimeVerifierApproves() throws Throwable {
        ConfidentialExecutionPolicy policy = mock(ConfidentialExecutionPolicy.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("completed");

        ConfidentialAIAspect aspect = new ConfidentialAIAspect(List.of(policy));

        assertEquals("completed", aspect.requireConfidentialExecution(joinPoint));
        verify(policy).verifyConfidentialExecution();
        verify(joinPoint).proceed();
    }

    @Test
    void deniesExecutionWhenVerifierRejectsRuntime() throws Throwable {
        ConfidentialExecutionPolicy policy = mock(ConfidentialExecutionPolicy.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        doThrow(new ConfidentialExecutionDeniedException("Attestation rejected."))
                .when(policy).verifyConfidentialExecution();

        ConfidentialAIAspect aspect = new ConfidentialAIAspect(List.of(policy));

        assertThrows(
                ConfidentialExecutionDeniedException.class,
                () -> aspect.requireConfidentialExecution(joinPoint));
        verify(joinPoint, never()).proceed();
    }

    @Test
    void deniesExecutionWhenNoVerifierIsConfigured() {
        ConfidentialAIAspect aspect = new ConfidentialAIAspect(List.of());
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);

        assertThrows(
                ConfidentialExecutionDeniedException.class,
                () -> aspect.requireConfidentialRuntime(joinPoint));
    }

    @Test
    void deniesExecutionWhenMultipleVerifiersAreConfigured() {
        ConfidentialAIAspect aspect = new ConfidentialAIAspect(
                List.of(mock(ConfidentialExecutionPolicy.class),
                        mock(ConfidentialExecutionPolicy.class)));
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);

        assertThrows(
                ConfidentialExecutionDeniedException.class,
                () -> aspect.requireConfidentialExecution(joinPoint));
    }
}
