package com.nanopool.level2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpuriousWakeupTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void ifInsteadOfWhileLetsASpuriousWakeupEscapeEarly() throws InterruptedException {
        MyCountDownLatchSyncBroken latch = new MyCountDownLatchSyncBroken(1);
        AtomicBoolean returned = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            try {
                latch.await();
                returned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        Thread.sleep(100); // let the waiter actually park inside wait()

        latch.debugForceSpuriousWakeup(); // simulate a spurious wakeup - count is still 1
        waiter.join(1000);

        assertTrue(returned.get(), "BUG: with 'if', a spurious wakeup makes await() return even though count is still 1");
        assertEquals(1, latch.getCount(), "count was never decremented - the latch contract has been violated");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void whileRecheckInSyncVersionSurvivesASpuriousWakeup() throws InterruptedException {
        MyCountDownLatchSync latch = new MyCountDownLatchSync(1);
        AtomicBoolean returned = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            try {
                latch.await();
                returned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        Thread.sleep(100);

        latch.debugForceSpuriousWakeup();
        Thread.sleep(200); // give it every chance to wrongly return - it must not
        assertFalse(returned.get(), "the while-loop must re-check the condition and keep waiting");

        latch.countDown();
        waiter.join(1000);
        assertTrue(returned.get(), "after the real countDown, the waiter must be released");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void whileRecheckInLockVersionSurvivesASpuriousWakeup() throws InterruptedException {
        MyCountDownLatchLock latch = new MyCountDownLatchLock(1);
        AtomicBoolean returned = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            try {
                latch.await();
                returned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        Thread.sleep(100);

        latch.debugForceSpuriousWakeup();
        Thread.sleep(200);
        assertFalse(returned.get(), "Condition.await() also requires a while-loop - the JDK docs warn about this explicitly");

        latch.countDown();
        waiter.join(1000);
        assertTrue(returned.get(), "after the real countDown, the waiter must be released");
    }
}
