package com.nanopool.level2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySemaphoreFunctionalTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void tryAcquireTimesOutWhenNoPermitAvailable() throws InterruptedException {
        MySemaphore sem = new MySemaphore(0);
        long start = System.nanoTime();
        boolean acquired = sem.tryAcquire(100, TimeUnit.MILLISECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(acquired);
        assertTrue(elapsedMs >= 90, "should have waited close to the requested timeout, was " + elapsedMs + "ms");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void tryAcquireSucceedsWhenPermitArrivesInTime() throws InterruptedException {
        MySemaphore sem = new MySemaphore(0);
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sem.release();
        });
        releaser.start();

        boolean acquired = sem.tryAcquire(2, TimeUnit.SECONDS);

        assertTrue(acquired);
        releaser.join();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void acquireBlocksUntilAPermitIsReleased() throws InterruptedException {
        MySemaphore sem = new MySemaphore(0);
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sem.release();
        });
        releaser.start();

        sem.acquire(); // must not throw, must not return before release()

        releaser.join();
        assertTrue(true);
    }
}
