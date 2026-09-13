package com.nanopool.level3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Bai 14, cach sua 3: gioi han do sau de quy. Day la truong hop tong quat hon cua bai toan
// goc: mot phep tinh chia-de-tri (divide and conquer) de quy submit nua ben trai vao CHINH
// pool do roi block cho ket qua, tuong tu ForkJoinPool nhung KHONG co work-stealing. Voi
// pool co dinh N thread, ngay khi do sau de quy vuot qua N, tat ca N thread deu co the dang
// bi "khoa" cho nhau - dung 1 gioi han do sau: qua gioi han thi tinh THANG tren chinh thread
// hien tai (khong submit vao pool nua) se cham dut chuoi block truoc khi no vuot qua N.
class RecursionDepthLimitFixTest {

    private static final int POOL_SIZE = 2;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void unlimitedRecursionDepthDeadlocksOnABoundedPool() throws Exception {
        NanoPool pool = new NanoPool(POOL_SIZE, POOL_SIZE, 100, 0, RejectionPolicy.ABORT);
        AtomicReference<Long> result = new AtomicReference<>();

        Thread caller = new Thread(() -> {
            try {
                result.set(sumRangeNoLimit(pool, 0, 15));
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        });
        caller.start();
        caller.join(1500);

        assertTrue(caller.isAlive(),
                "de quy khong gioi han do sau tren pool 2 thread phai treo vinh vien mot khi do sau de quy vuot qua 2");

        for (Thread w : pool.workerThreads()) {
            Thread.State state = w.getState();
            assertTrue(state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING,
                    "worker phai dang WAITING (Condition.await() cho ket qua nhanh trai), thuc te: " + state);
        }

        pool.shutdownNow();
        caller.join(3000);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void limitingRecursionDepthAvoidsTheDeadlockAndStillProducesTheCorrectResult() throws Exception {
        NanoPool pool = new NanoPool(POOL_SIZE, POOL_SIZE, 100, 0, RejectionPolicy.ABORT);

        long result = sumRangeWithDepthLimit(pool, 0, 15, 0, POOL_SIZE);

        assertEquals(120, result, "tong 0..15 phai dung bang 120 du mot phan duoc tinh de quy tren pool, phan con lai chay thang");

        pool.shutdown();
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
    }

    // Khong gioi han: MOI lan chia doi deu submit nua trai vao pool roi block - do sau de quy
    // se vuot qua POOL_SIZE gan nhu ngay lap tuc voi mot khoang [0,15] (can toi 4 muc de quy).
    private long sumRangeNoLimit(NanoPool pool, long lo, long hi) throws Exception {
        if (hi - lo <= 1) {
            return lo + hi;
        }
        long mid = (lo + hi) / 2;
        Future<Long> leftFuture = pool.submit(() -> sumRangeNoLimit(pool, lo, mid));
        long right = sumRangeNoLimit(pool, mid + 1, hi);
        return leftFuture.get() + right;
    }

    // Co gioi han: chi submit vao pool khi do sau hien tai CHUA vuot qua maxPoolDepth. Qua
    // gioi han, tinh thang (de quy binh thuong tren chinh thread hien tai) - khong bao gio
    // tao them mot chuoi block moi vao pool nua, nen tong so "cho ket qua" dong thoi tren
    // pool khong bao gio vuot qua so thread that su co.
    private long sumRangeWithDepthLimit(NanoPool pool, long lo, long hi, int depth, int maxPoolDepth) throws Exception {
        if (hi - lo <= 1) {
            return lo + hi;
        }
        long mid = (lo + hi) / 2;
        if (depth >= maxPoolDepth) {
            long left = sumRangeWithDepthLimit(pool, lo, mid, depth + 1, maxPoolDepth);
            long right = sumRangeWithDepthLimit(pool, mid + 1, hi, depth + 1, maxPoolDepth);
            return left + right;
        }
        Future<Long> leftFuture = pool.submit(() -> sumRangeWithDepthLimit(pool, lo, mid, depth + 1, maxPoolDepth));
        long right = sumRangeWithDepthLimit(pool, mid + 1, hi, depth + 1, maxPoolDepth);
        return leftFuture.get() + right;
    }
}
