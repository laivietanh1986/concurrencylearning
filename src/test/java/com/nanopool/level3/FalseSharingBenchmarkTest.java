package com.nanopool.level3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Bai 13, thi nghiem phu: moi thread ghi vao MOT o rieng cua no trong mot mang long[] dung
// chung - khong co race dieu kien logic nao (moi thread chi dung o cua minh), nhung neu cac
// o do nam chung mot cache line 64 byte, phan cung van phai dong bo cache line lien tuc giua
// cac core (false sharing) du chuong trinh hoan toan dung ve mat logic.
class FalseSharingBenchmarkTest {

    private static final int THREADS = 8;
    private static final long ITERATIONS = 200_000_000L;
    private static final int PADDING = 8; // 8 long = 64 byte - dung 1 cache line tren kien truc pho bien

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void paddedCountersAvoidFalseSharingAndAreMeasurablyFaster() throws InterruptedException {
        long unpaddedMs = benchmarkUnpadded();
        long paddedMs = benchmarkPadded();
        System.out.println("false sharing: unpadded (lien nhau)=" + unpaddedMs + "ms, padded (moi counter 1 cache line)=" + paddedMs + "ms");

        assertTrue(paddedMs < unpaddedMs,
                "mang co padding (moi counter rieng mot cache line) phai nhanh hon ro ret so voi mang khong padding, do measured: unpadded="
                        + unpaddedMs + "ms padded=" + paddedMs + "ms");
    }

    private long benchmarkUnpadded() throws InterruptedException {
        long[] counters = new long[THREADS];
        return run(counters, i -> i);
    }

    private long benchmarkPadded() throws InterruptedException {
        long[] counters = new long[THREADS * PADDING];
        return run(counters, i -> i * PADDING);
    }

    private long run(long[] counters, java.util.function.IntUnaryOperator indexOf) throws InterruptedException {
        Thread[] threads = new Thread[THREADS];
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < THREADS; t++) {
            int idx = indexOf.applyAsInt(t);
            threads[t] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (long j = 0; j < ITERATIONS; j++) {
                    counters[idx]++;
                }
            });
        }
        for (Thread th : threads) {
            th.start();
        }
        ready.await();
        long startTime = System.nanoTime();
        start.countDown();
        for (Thread th : threads) {
            th.join();
        }
        return (System.nanoTime() - startTime) / 1_000_000;
    }
}
