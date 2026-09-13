package com.nanopool.level3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyFutureTaskTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void submitReturnsTheCallablesResult() throws Exception {
        NanoPool pool = new NanoPool(2, 5);
        Future<Integer> future = pool.submit(() -> 21 * 2);
        assertEquals(42, future.get());
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void exceptionThrownByTheTaskIsWrappedInExecutionException() {
        NanoPool pool = new NanoPool(2, 5);
        Future<String> future = pool.submit(() -> {
            throw new IllegalStateException("boom");
        });
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals("boom", ex.getCause().getMessage());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void cancelFalsePreventsAnUnstartedTaskFromEverRunning() throws Exception {
        NanoPool pool = new NanoPool(1, 5);
        CountDownLatch blockerRunning = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        // giu doc quyen thread duy nhat cua pool de dam bao task ben duoi con NEW
        pool.execute(() -> {
            blockerRunning.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerRunning.await(2, TimeUnit.SECONDS));

        AtomicBoolean started = new AtomicBoolean(false);
        Future<String> future = pool.submit(() -> {
            started.set(true);
            return "done";
        });

        boolean cancelled = future.cancel(false);
        releaseBlocker.countDown();

        assertTrue(cancelled);
        assertTrue(future.isCancelled());
        assertThrows(CancellationException.class, future::get);
        Thread.sleep(200); // du thoi gian de worker ranh ra va (khong duoc) chay task da huy
        assertFalse(started.get(), "a task cancelled before it started must never run its body");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void cancelTrueInterruptsTheThreadActuallyRunningTheTask() throws Exception {
        NanoPool pool = new NanoPool(1, 5);
        CountDownLatch taskStarted = new CountDownLatch(1);
        AtomicBoolean interruptedInsideTask = new AtomicBoolean(false);

        Future<String> future = pool.submit(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(5000);
                return "should not reach here";
            } catch (InterruptedException e) {
                interruptedInsideTask.set(true);
                throw e;
            }
        });

        assertTrue(taskStarted.await(2, TimeUnit.SECONDS));
        boolean cancelled = future.cancel(true);

        assertTrue(cancelled);
        assertTrue(future.isCancelled());
        assertThrows(CancellationException.class, future::get);
        Thread.sleep(200);
        assertTrue(interruptedInsideTask.get(), "cancel(true) must interrupt the thread that is actually running the task");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void cancelAfterCompletionHasNoEffectAndDoesNotChangeTheResult() throws Exception {
        NanoPool pool = new NanoPool(1, 5);
        Future<Integer> future = pool.submit(() -> 42);
        assertEquals(42, future.get());

        boolean cancelled = future.cancel(true);

        assertFalse(cancelled, "cancel() on an already-completed task must return false");
        assertFalse(future.isCancelled());
        assertEquals(42, future.get());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void getWithTimeoutThrowsBeforeCompletionThenSucceedsAfter() throws Exception {
        NanoPool pool = new NanoPool(1, 5);
        CountDownLatch release = new CountDownLatch(1);
        Future<String> future = pool.submit(() -> {
            release.await();
            return "ok";
        });

        assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS));

        release.countDown();
        assertEquals("ok", future.get(2, TimeUnit.SECONDS));
    }
}
