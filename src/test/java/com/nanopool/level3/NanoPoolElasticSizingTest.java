package com.nanopool.level3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NanoPoolElasticSizingTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void extraThreadsBeyondCoreActuallyDieAfterKeepAliveExpires() throws Exception {
        NanoPool pool = new NanoPool(1, 4, 2, 150, RejectionPolicy.ABORT);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        CountDownLatch coreBusy = new CountDownLatch(1);
        pool.execute(() -> {
            coreBusy.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(coreBusy.await(2, TimeUnit.SECONDS));
        assertEquals(1, pool.currentPoolSize());

        pool.execute(blocker); // vao hang doi (1/2)
        pool.execute(blocker); // vao hang doi (2/2)
        assertEquals(2, pool.queueSize());
        assertEquals(1, pool.currentPoolSize(), "hang doi con cho, chua can toi extra thread nao");

        pool.execute(blocker); // hang doi da day - phai spawn extra thread
        assertEquals(2, pool.currentPoolSize());
        pool.execute(blocker);
        assertEquals(3, pool.currentPoolSize());
        pool.execute(blocker);
        assertEquals(4, pool.currentPoolSize(), "phai dat toi maxSize");

        assertThrows(RejectedExecutionException.class, () -> pool.execute(blocker),
                "hang doi day va da dat maxSize - phai reject theo ABORT");

        release.countDown();

        long deadline = System.currentTimeMillis() + 5000;
        int size;
        do {
            Thread.sleep(20);
            size = pool.currentPoolSize();
        } while (size > 1 && System.currentTimeMillis() < deadline);

        assertEquals(1, size, "cac extra thread phai tu ket thuc sau keepAliveMs khi het viec, chi con lai dung coreSize");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void largeQueueCapacityPreventsGrowingPastCoreSizeEvenUnderSustainedLoad() throws Exception {
        NanoPool pool = new NanoPool(2, 8, 100, 200, RejectionPolicy.ABORT);
        CountDownLatch release = new CountDownLatch(1);
        int totalTasks = 20;
        for (int i = 0; i < totalTasks; i++) {
            pool.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        Thread.sleep(200);
        assertEquals(2, pool.currentPoolSize(),
                "hang doi con cho (capacity 100 > 20 task) nen KHONG bao gio can toi extra thread, du maxSize=8");

        release.countDown();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void abortPolicyThrowsWhenQueueIsFullAndAtMaxSize() throws Exception {
        NanoPool pool = new NanoPool(1, 1, 1, 100, RejectionPolicy.ABORT);
        CountDownLatch busy = new CountDownLatch(1);
        pool.execute(() -> {
            busy.countDown();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(busy.await(2, TimeUnit.SECONDS));

        pool.execute(() -> {}); // dien not hang doi (1/1)
        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> {}));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void callerRunsPolicyExecutesOnTheCallingThreadAsBackpressure() throws Exception {
        NanoPool pool = new NanoPool(1, 1, 1, 100, RejectionPolicy.CALLER_RUNS);
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> {
            busy.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(busy.await(2, TimeUnit.SECONDS));

        pool.execute(() -> {}); // dien not hang doi (1/1)

        String callerName = Thread.currentThread().getName();
        AtomicReference<String> ranOnThread = new AtomicReference<>();
        pool.execute(() -> ranOnThread.set(Thread.currentThread().getName()));

        assertEquals(callerName, ranOnThread.get(),
                "CALLER_RUNS phai chay task ngay tren thread dang goi execute(), khong dua vao worker nao cua pool");

        release.countDown();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void blockUntilSpacePolicyBlocksTheCallerUntilRoomIsAvailable() throws Exception {
        NanoPool pool = new NanoPool(1, 1, 1, 100, RejectionPolicy.BLOCK_UNTIL_SPACE);
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> {
            busy.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(busy.await(2, TimeUnit.SECONDS));

        pool.execute(() -> {}); // dien not hang doi (1/1)

        AtomicBoolean submitted = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            pool.execute(() -> {}); // phai block o day cho toi khi co cho trong
            submitted.set(true);
        });
        producer.start();

        Thread.sleep(300);
        assertFalse(submitted.get(), "BLOCK_UNTIL_SPACE phai chan thread goi khi chua co cho trong");
        assertTrue(producer.isAlive());

        release.countDown();

        producer.join(3000);
        assertTrue(submitted.get(), "sau khi co cho trong, execute() phai tra ve binh thuong");
    }
}
