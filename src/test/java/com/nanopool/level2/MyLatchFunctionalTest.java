package com.nanopool.level2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyLatchFunctionalTest {

    private static final int WORKERS = 5;
    private static final int WAITERS = 10;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void syncImplementationNeverReleasesAWaiterWhileCountIsPositive() throws InterruptedException {
        runBasicLatchTest(MyCountDownLatchSync::new);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void lockImplementationNeverReleasesAWaiterWhileCountIsPositive() throws InterruptedException {
        runBasicLatchTest(MyCountDownLatchLock::new);
    }

    private void runBasicLatchTest(IntFunction<MyLatch> factory) throws InterruptedException {
        MyLatch latch = factory.apply(WORKERS);

        // Plain array, no volatile/Atomic needed: each waiter thread only
        // writes its own slot, and Thread.join() below happens-before the
        // main thread reading these values (see bai 4 - the join edge).
        boolean[] countWasZeroOnRelease = new boolean[WAITERS];

        Thread[] waiterThreads = new Thread[WAITERS];
        for (int i = 0; i < WAITERS; i++) {
            int idx = i;
            waiterThreads[i] = new Thread(() -> {
                try {
                    latch.await();
                    countWasZeroOnRelease[idx] = (latch.getCount() == 0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (Thread t : waiterThreads) {
            t.start();
        }
        Thread.sleep(50); // give waiters time to actually be parked in await()

        Thread[] workerThreads = new Thread[WORKERS];
        for (int i = 0; i < WORKERS; i++) {
            workerThreads[i] = new Thread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                latch.countDown();
            });
        }
        for (Thread t : workerThreads) {
            t.start();
        }
        for (Thread t : workerThreads) {
            t.join();
        }
        for (Thread t : waiterThreads) {
            t.join(2000);
        }

        assertEquals(0, latch.getCount());
        for (int i = 0; i < WAITERS; i++) {
            assertTrue(countWasZeroOnRelease[i], "waiter " + i + " returned from await() while count was still > 0");
        }
    }
}
