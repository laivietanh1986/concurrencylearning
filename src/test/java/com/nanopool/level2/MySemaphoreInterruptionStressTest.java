package com.nanopool.level2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySemaphoreInterruptionStressTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void permitsAreNeverLostWhenHalfTheThreadsAreInterrupted() throws InterruptedException {
        int permits = 10;
        int threadCount = 40;
        MySemaphore sem = new MySemaphore(permits);

        AtomicInteger currentlyHeld = new AtomicInteger(0);
        AtomicInteger maxHeldObserved = new AtomicInteger(0);
        AtomicInteger successfulAcquires = new AtomicInteger(0);
        AtomicInteger interruptedCount = new AtomicInteger(0);
        boolean[] flagRestoredAfterInterrupt = new boolean[threadCount];

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            threads[i] = new Thread(() -> {
                boolean acquired = false;
                try {
                    sem.acquire();
                    acquired = true;
                    int held = currentlyHeld.incrementAndGet();
                    maxHeldObserved.updateAndGet(prev -> Math.max(prev, held));
                    successfulAcquires.incrementAndGet();
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    interruptedCount.incrementAndGet();
                    // The rule: a checked InterruptedException caught where we
                    // cannot rethrow (Runnable) must restore the flag, never be
                    // swallowed silently.
                    Thread.currentThread().interrupt();
                    flagRestoredAfterInterrupt[idx] = true;
                } finally {
                    if (acquired) {
                        currentlyHeld.decrementAndGet();
                        sem.release();
                    }
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        // interrupt half the threads essentially immediately - some will
        // still be queued in acquire(), some may already be running/sleeping,
        // some may already be done. All three cases must be handled safely.
        for (int i = 0; i < threadCount; i += 2) {
            threads[i].interrupt();
        }
        for (Thread t : threads) {
            t.join(5000);
        }

        System.out.printf("successfulAcquires=%d interruptedCount=%d maxHeldObserved=%d%n",
                successfulAcquires.get(), interruptedCount.get(), maxHeldObserved.get());

        assertTrue(maxHeldObserved.get() <= permits,
                "semaphore must never let more concurrent holders than it has permits");
        assertEquals(permits, sem.availablePermits(),
                "every acquired permit must eventually be released - none lost, none duplicated");
        assertEquals(0, currentlyHeld.get(), "no thread should still be holding a permit after all joined");

        for (int i = 0; i < threadCount; i += 2) {
            Thread t = threads[i];
            assertTrue(!t.isAlive(), "thread " + i + " must have terminated");
            if (flagRestoredAfterInterrupt[i]) {
                assertTrue(t.isInterrupted(), "thread " + i + " caught InterruptedException but did not restore the flag");
            }
        }
    }
}
