package org.opentron.backend.security.confidential.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class ConfidentialOperationGovernorTest {
    @Test
    void limitsConcurrentOperationsAndReleasesPermitAfterCompletion() throws Exception {
        ConfidentialOperationGovernor governor = new ConfidentialOperationGovernor(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        governor.execute(() -> {
            Thread second = Thread.startVirtualThread(() -> {
                secondAttempted.countDown();
                try {
                    governor.execute(() -> {
                        secondEntered.countDown();
                        return null;
                    });
                } catch (Throwable failure) {
                    secondFailure.set(failure);
                } finally {
                    secondFinished.countDown();
                }
            });

            await(secondAttempted);
            assertNotEntered(secondEntered);
            return null;
        });

        await(secondFinished);
        assertNull(secondFailure.get());
        assertEquals(1, governor.maximumConcurrentOperations());
    }

    @Test
    void restoresInterruptStatusWhenWaitingIsInterrupted() throws Exception {
        ConfidentialOperationGovernor governor = new ConfidentialOperationGovernor(1);
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Boolean> interruptRestored = new AtomicReference<>(false);

        governor.execute(() -> {
            Thread waiter = Thread.startVirtualThread(() -> {
                attempted.countDown();
                try {
                    governor.execute(() -> null);
                } catch (IllegalStateException expected) {
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                } finally {
                    finished.countDown();
                }
            });
            await(attempted);
            waiter.interrupt();
            await(finished);
            return null;
        });

        assertTrue(interruptRestored.get());
    }

    @Test
    void rejectsNonPositivePermitLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConfidentialOperationGovernor(0));
    }

    @Test
    void explicitPermitCanCoverRequestParsingAndCannotBeReusedAfterClose() {
        ConfidentialOperationGovernor governor = new ConfidentialOperationGovernor(1);
        ConfidentialOperationGovernor.Permit permit = governor.acquire();

        assertEquals("processed", governor.execute(permit, () -> "processed"));
        permit.close();
        assertThrows(
                IllegalArgumentException.class,
                () -> governor.execute(permit, () -> "should not run"));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test thread was interrupted.", exception);
        }
    }

    private static void assertNotEntered(CountDownLatch latch) {
        try {
            assertFalse(latch.await(100, TimeUnit.MILLISECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test thread was interrupted.", exception);
        }
    }
}
