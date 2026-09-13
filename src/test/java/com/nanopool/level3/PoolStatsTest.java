package com.nanopool.level3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolStatsTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void statsTrackSubmittedActiveQueueDepthCompletedFailedAndRejected() throws Exception {
        NanoPool pool = new NanoPool(1, 1, 2, 100, RejectionPolicy.ABORT);

        CountDownLatch task1Started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> {
            task1Started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(task1Started.await(2, TimeUnit.SECONDS));
        // Gio chac chan thread duy nhat cua pool dang ban - 2 execute() tiep theo se nam
        // trong hang doi, khong ai lay ra ca, cho toi khi release duoc tha.

        pool.execute(() -> {}); // vao hang doi (1/2), se thanh cong khi chay
        pool.execute(() -> {
            throw new RuntimeException("boom");
        }); // vao hang doi (2/2), se that bai khi chay

        PoolStats snap = pool.stats();
        assertEquals(3, snap.submitted());
        assertEquals(1, snap.activeWorkers());
        assertEquals(2, snap.queueDepth());
        assertEquals(0, snap.completed());
        assertEquals(0, snap.failed());
        assertEquals(0, snap.rejected());

        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> {}));
        snap = pool.stats();
        assertEquals(4, snap.submitted());
        assertEquals(1, snap.rejected());

        release.countDown();

        long deadline = System.currentTimeMillis() + 3000;
        while (pool.queueSize() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(100); // du thoi gian de task cuoi cung thuc su chay xong

        snap = pool.stats();
        assertEquals(0, snap.activeWorkers());
        assertEquals(0, snap.queueDepth());
        assertEquals(2, snap.completed(), "task chan (sau khi release) + task rong deu thanh cong");
        assertEquals(1, snap.failed(), "task nem RuntimeException phai duoc dem la failed, khong lam chet worker");
        assertEquals(1, snap.rejected());
        assertEquals(4, snap.submitted());
    }
}
