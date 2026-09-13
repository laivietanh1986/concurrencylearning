package com.nanopool.level3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NanoPoolLifecycleTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void shutdownDrainsAlreadyQueuedTasksBeforeTerminating() throws Exception {
        NanoPool pool = new NanoPool(2, 25);
        AtomicInteger completed = new AtomicInteger();
        int total = 20;

        CountDownLatch blockersRunning = new CountDownLatch(2);
        CountDownLatch releaseBlockers = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            pool.execute(() -> {
                blockersRunning.countDown();
                try {
                    releaseBlockers.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(blockersRunning.await(2, TimeUnit.SECONDS));

        for (int i = 0; i < total; i++) {
            pool.execute(completed::incrementAndGet);
        }
        assertEquals(total, pool.queueSize());

        pool.shutdown();
        assertTrue(pool.isShutdown());
        assertFalse(pool.isTerminated(), "must not be terminated while the queue still has work and blockers are running");

        releaseBlockers.countDown();

        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(total, completed.get(), "shutdown() must drain and run every already-queued task, never drop them");
        assertEquals(0, pool.aliveWorkerCount());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void executeAndSubmitAfterShutdownAreRejectedDeterministically() throws Exception {
        NanoPool pool = new NanoPool(2, 10);
        pool.shutdown();

        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> {}));
        assertThrows(RejectedExecutionException.class, () -> pool.submit(() -> 1));
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void shutdownNowInterruptsTheRunningTaskAndReturnsUnexecutedOnes() throws Exception {
        NanoPool pool = new NanoPool(1, 10);
        CountDownLatch taskStarted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        pool.execute(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(taskStarted.await(2, TimeUnit.SECONDS));

        AtomicInteger neverRanCount = new AtomicInteger();
        Runnable neverRuns = neverRanCount::incrementAndGet;
        for (int i = 0; i < 5; i++) {
            pool.execute(neverRuns);
        }

        List<Runnable> notRun = pool.shutdownNow();

        assertEquals(5, notRun.size(), "every task still sitting in the queue must come back from shutdownNow()");
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(interrupted.get(), "shutdownNow() must interrupt the thread actually running a task");
        assertEquals(0, neverRanCount.get(), "tasks returned by shutdownNow() must never have executed");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void awaitTerminationReturnsFalseBeforeTasksFinishThenTrueAfter() throws Exception {
        NanoPool pool = new NanoPool(1, 5);
        pool.execute(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pool.shutdown();

        assertFalse(pool.awaitTermination(100, TimeUnit.MILLISECONDS));
        assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void awaitTerminationWakesUpPromptlyRightAfterTheLastWorkerExits() throws Exception {
        NanoPool pool = new NanoPool(1, 5);
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pool.shutdown();

        Thread waiter = new Thread(() -> {
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        waiter.start();
        Thread.sleep(200); // dam bao waiter da that su vao awaitTermination() truoc khi ta release

        release.countDown();
        long waitStart = System.nanoTime();
        waiter.join(2000);
        long latencyMs = (System.nanoTime() - waitStart) / 1_000_000;
        System.out.println("MEASURED signal-based termination latency = " + latencyMs + "ms");

        assertFalse(waiter.isAlive());
        assertTrue(latencyMs < 500,
                "Condition-based awaitTermination should wake up quickly (measured " + latencyMs + "ms), not on a coarse sleep(10) polling schedule");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void terminationLatencyWhenTheWorkerIsIdlyPollingAnEmptyQueue() throws Exception {
        NanoPool pool = new NanoPool(1, 5);
        Thread.sleep(60); // de chac chan worker dang nam trong queue.poll(50ms) tren hang doi rong

        long start = System.nanoTime();
        pool.shutdown();
        assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("MEASURED idle-poll termination latency = " + elapsedMs + "ms");

        assertTrue(elapsedMs < 200,
                "expected termination within about one poll cycle (~50ms), measured " + elapsedMs + "ms");
    }
}
