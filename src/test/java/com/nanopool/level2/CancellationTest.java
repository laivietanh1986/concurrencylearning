package com.nanopool.level2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void volatileFlagStopsACpuBoundLoop() throws InterruptedException {
        VolatileFlagTask task = new VolatileFlagTask();
        Thread t = new Thread(task);
        t.start();
        Thread.sleep(100);

        task.cancel();
        t.join(2000);

        assertFalse(t.isAlive(), "volatile flag must stop a plain CPU-bound loop");
        assertTrue(task.getIterations() > 0, "the task should have done some work before being cancelled");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void swallowingInterruptedExceptionMeansTheTaskNeverStops() throws InterruptedException {
        InterruptibleTaskBroken task = new InterruptibleTaskBroken();
        Thread t = new Thread(task);
        t.setDaemon(true); // guarantee it can never hang the build even if it truly never stops
        t.start();
        Thread.sleep(100);

        t.interrupt();
        t.join(500);

        // This assertion IS the bug under study: swallowing InterruptedException
        // with an empty catch block means interrupt() has no effect at all.
        assertTrue(t.isAlive(), "expected the broken task to ignore interrupt() and keep running");
        long before = task.getIterations();
        Thread.sleep(100);
        assertTrue(task.getIterations() > before, "task is still actively looping, unaffected by the earlier interrupt()");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void checkingAndRestoringTheFlagStopsTheTaskPromptly() throws InterruptedException {
        InterruptibleTask task = new InterruptibleTask();
        Thread t = new Thread(task);
        t.start();
        Thread.sleep(100);

        t.interrupt();
        t.join(2000);

        assertFalse(t.isAlive(), "fixed task must stop once interrupted");
        assertTrue(task.getIterations() > 0, "the task should have done some work before being cancelled");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void interruptAloneCannotStopABlockedResourceRead() throws InterruptedException {
        BlockingResource resource = new BlockingResource(); // never makeDataAvailable() -> read() blocks forever
        ResourceBoundTask task = new ResourceBoundTask(resource);
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
        Thread.sleep(100);

        t.interrupt();
        t.join(300);

        assertTrue(t.isAlive(), "a read() that swallows InterruptedException (like a raw blocking socket) cannot be cancelled by interrupt() alone");

        resource.close();
        t.join(2000);

        assertFalse(t.isAlive(), "closing the resource must unblock the read() and stop the task");
        assertTrue(task.wasStoppedByClose(), "the task must observe ResourceClosedException, not just silently die");
    }
}
