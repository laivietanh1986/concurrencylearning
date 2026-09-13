package com.nanopool.level3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Bai 13: so sanh AtomicLong (mot bien nho dung chung, CAS canh tranh truc tiep) voi
// LongAdder (nhieu "cell" noi bo, moi thread thuong roi vao cell khac nhau - striping)
// o cac muc do tranh chap khac nhau.
class CounterBenchmarkTest {

    private static final long INCREMENTS_PER_THREAD = 3_000_000L;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void longAdderOutperformsAtomicLongUnderHighContention() throws InterruptedException {
        for (int threads : new int[]{1, 4, 16, 32}) {
            long atomicMs = benchmarkAtomicLong(threads);
            long adderMs = benchmarkLongAdder(threads);
            System.out.println("threads=" + threads + " AtomicLong=" + atomicMs + "ms LongAdder=" + adderMs + "ms");
        }

        // Chi assert o muc tranh chap cao (32 thread) - day la vung LongAdder duoc thiet ke
        // de thang ro rang. O 1 thread, LongAdder co the CHAM HON AtomicLong (chi phi
        // indirection qua Cell) - khong assert gi o do, chi bao cao trong docs.
        long atomic32 = benchmarkAtomicLong(32);
        long adder32 = benchmarkLongAdder(32);
        System.out.println("repeat 32 threads: AtomicLong=" + atomic32 + "ms LongAdder=" + adder32 + "ms");
        assertTrue(adder32 <= atomic32,
                "LongAdder phai nhanh hon hoac bang AtomicLong khi tranh chap cao (32 thread), do measured: AtomicLong="
                        + atomic32 + "ms LongAdder=" + adder32 + "ms");
    }

    private long benchmarkAtomicLong(int nThreads) throws InterruptedException {
        AtomicLong counter = new AtomicLong();
        return benchmark(nThreads, counter::incrementAndGet);
    }

    private long benchmarkLongAdder(int nThreads) throws InterruptedException {
        LongAdder counter = new LongAdder();
        return benchmark(nThreads, counter::increment);
    }

    private long benchmark(int nThreads, Runnable incrementOp) throws InterruptedException {
        Thread[] threads = new Thread[nThreads];
        CountDownLatch ready = new CountDownLatch(nThreads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < nThreads; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (long j = 0; j < INCREMENTS_PER_THREAD; j++) {
                    incrementOp.run();
                }
            });
        }
        for (Thread t : threads) {
            t.start();
        }
        ready.await();
        long startTime = System.nanoTime();
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        return (System.nanoTime() - startTime) / 1_000_000;
    }
}
