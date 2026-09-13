package com.nanopool.level3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Bai 14, cach sua 1: dung MOT POOL RIENG cho task con. Outer task van block bang get()
// nhu cu (khong doi logic cua outer task), nhung inner task duoc submit vao mot NanoPool
// KHAC hoan toan - vi vay outer pool va inner pool khong bao gio tranh gianh cung mot tap
// thread, nen khong the co chuyen "ca 2 thread cua MOT pool deu ban cho nhau" nua.
class SeparateChildPoolFixTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void innerTasksOnADedicatedPoolNeverStarveTheOuterPool() throws Exception {
        NanoPool outerPool = new NanoPool(2, 2, 10, 0, RejectionPolicy.ABORT);
        NanoPool innerPool = new NanoPool(2, 2, 10, 0, RejectionPolicy.ABORT);

        int outerCount = 2;
        CountDownLatch outerStarted = new CountDownLatch(outerCount);
        List<Future<Integer>> outerFutures = new ArrayList<>();

        for (int i = 0; i < outerCount; i++) {
            int taskId = i;
            Future<Integer> outerFuture = outerPool.submit(() -> {
                outerStarted.countDown();
                Future<Integer> innerFuture = innerPool.submit(() -> taskId * 10 + 1);
                return innerFuture.get();
            });
            outerFutures.add(outerFuture);
        }

        assertTrue(outerStarted.await(2, TimeUnit.SECONDS));

        for (int i = 0; i < outerCount; i++) {
            int result = outerFutures.get(i).get(5, TimeUnit.SECONDS);
            assertEquals(i * 10 + 1, result, "outer task phai hoan tat va lay dung ket qua tu inner pool");
        }

        outerPool.shutdown();
        innerPool.shutdown();
        assertTrue(outerPool.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(innerPool.awaitTermination(2, TimeUnit.SECONDS));
    }
}
