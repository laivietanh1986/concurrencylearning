package com.nanopool.level3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Bai 14, cach sua 2: KHONG block bang get() ben trong mot task cua chinh pool do nua.
// NanoPool gio implements Executor (interface thuan, khong phai lop Executors/ThreadPoolExecutor
// bi cam), nen dung duoc thang voi CompletableFuture.supplyAsync(..., pool). "outer" va
// "inner" van chay tren CHUNG mot pool 2 thread nhu bai toan goc, nhung vi khong co thread
// nao BLOCK cho ket qua cua thread khac - moi giai doan chi la mot task doc lap duoc dua vao
// hang doi roi CHAY XONG LA THA THREAD RA NGAY - nen khong the xay ra starvation.
class CompletableFutureFixTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void composingWithCompletableFutureNeverBlocksAPoolThread() throws Exception {
        NanoPool pool = new NanoPool(2, 2, 10, 0, RejectionPolicy.ABORT);

        int chainCount = 4; // nhieu hon so thread cua pool (2) - neu con blocking se treo
        List<CompletableFuture<Integer>> chains = new ArrayList<>();
        for (int i = 0; i < chainCount; i++) {
            int taskId = i;
            CompletableFuture<Integer> chain = CompletableFuture
                    .supplyAsync(() -> taskId * 10, pool)           // "outer" stage
                    .thenComposeAsync(x -> CompletableFuture.supplyAsync(() -> x + 1, pool), pool); // "inner" stage
            chains.add(chain);
        }

        for (int i = 0; i < chainCount; i++) {
            int result = chains.get(i).get(5, TimeUnit.SECONDS);
            assertEquals(i * 10 + 1, result, "moi chuoi outer->inner phai hoan tat dung ket qua du pool chi co 2 thread");
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
    }
}
