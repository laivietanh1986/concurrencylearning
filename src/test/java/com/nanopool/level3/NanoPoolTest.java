package com.nanopool.level3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NanoPoolTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void withoutTryCatchAnUncaughtExceptionKillsTheWorkerThreadAndThePoolEventuallyHangs() throws InterruptedException {
        int nThreads = 3;
        NanoPoolBroken pool = new NanoPoolBroken(nThreads, 5);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger submitted = new AtomicInteger();

        Thread submitter = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                final int idx = i;
                pool.execute(() -> {
                    if (idx % 10 == 0) {
                        throw new RuntimeException("boom " + idx);
                    }
                    completed.incrementAndGet();
                });
                submitted.incrementAndGet();
            }
        });
        submitter.setDaemon(true);
        submitter.start();

        submitter.join(3000);

        assertTrue(submitter.isAlive(),
                "submitter must be stuck in put() once every worker thread has died and the bounded queue is full");
        assertEquals(0, pool.aliveWorkerCount(), "every worker thread must have died from an uncaught exception");
        assertTrue(submitted.get() < 1000, "the pool must hang before all 1000 tasks are submitted");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void wrappingTaskRunInTryCatchLetsThePoolSurviveMisbehavingTasks() throws InterruptedException {
        int nThreads = 3;
        int total = 1000;
        NanoPool pool = new NanoPool(nThreads, 5);
        AtomicInteger completed = new AtomicInteger();

        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.execute(() -> {
                if (idx % 10 == 0) {
                    throw new RuntimeException("boom " + idx);
                }
                completed.incrementAndGet();
            });
        }

        long deadline = System.currentTimeMillis() + 5000;
        while ((pool.queueSize() > 0 || completed.get() < total - total / 10) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(nThreads, pool.aliveWorkerCount(), "all worker threads must survive despite 100 throwing tasks");
        assertEquals(total - total / 10, completed.get(), "every non-throwing task must have completed");
    }
}
