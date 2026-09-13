package com.nanopool.level4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Bai 15: chung minh ap luc (backpressure) lan NGUOC ve tan nguon. Sink duoc lam cham co
// tinh (gia lap I/O cham). Vi moi stage.submit() la BoundedTaskQueue.put() (BLOCK khi day),
// mot khi hang doi cua sink day, worker cua transform bi chan trong luc goi sink.submit();
// hang doi cua transform theo do cung day dan, worker cua parse cung bi chan trong luc goi
// transform.submit(); cuoi cung hang doi cua parse day, va chinh THREAD NGUON (goi
// parse.submit() trong vong lap) bi chan. Do la "ap luc lan nguoc" - khong co buffer nao
// vo han de "nuot" toc do chenh lech, nen no phai lo ra o tan dau vao he thong.
class PipelineBackpressureTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void slowSinkBlocksTheSourceThreadThroughAllUpstreamStages() throws Exception {
        int itemCount = 40;
        int sinkDelayMs = 15;
        int queueCapacity = 2; // co tinh de nho - buffer nho thi ap luc lo ra o nguon cang nhanh
        LongAdder sinkReceived = new LongAdder();

        PipelineStage<Integer, Integer> sink = new PipelineStage<>(
                "sink", 1, queueCapacity, itemCount,
                x -> {
                    try {
                        Thread.sleep(sinkDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    sinkReceived.increment();
                    return x;
                },
                null);
        PipelineStage<Integer, Integer> transform = new PipelineStage<>(
                "transform", 1, queueCapacity, itemCount, x -> x * 2, sink);
        PipelineStage<String, Integer> parse = new PipelineStage<>(
                "parse", 1, queueCapacity, itemCount, Integer::parseInt, transform);

        sink.start();
        transform.start();
        parse.start();

        long t0 = System.nanoTime();
        for (int i = 0; i < itemCount; i++) {
            parse.submit(Integer.toString(i));
        }
        long submitWallMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("MEASURED backpressure: submit " + itemCount + " item mat " + submitWallMs
                + "ms trong khi sink cham " + sinkDelayMs + "ms/item (buffer moi tang chi " + queueCapacity + ")");

        // Neu buffer vo han (khong co backpressure that), submit() se tra ve gan nhu ngay lap
        // tuc cho ca 40 item. Vi buffer bi chan lai o tung tang, thoi gian submit THUC TE phai
        // xap xi thoi gian sink can de xu ly (het buffer dem) - tuc la CHINH nguon bi cham lai
        // theo toc do cua tang cham nhat, du nguon khong biet gi ve sink ca.
        long minExpectedMs = (long) (itemCount - (queueCapacity * 3L + 3)) * sinkDelayMs / 2;
        assertTrue(submitWallMs >= minExpectedMs,
                "thoi gian submit (" + submitWallMs + "ms) phai bi keo dai boi backpressure tu sink cham, ky vong >= "
                        + minExpectedMs + "ms");

        parse.poisonAll();
        assertTrue(parse.awaitTermination(15, TimeUnit.SECONDS));
        assertTrue(transform.awaitTermination(15, TimeUnit.SECONDS));
        assertTrue(sink.awaitTermination(15, TimeUnit.SECONDS));

        assertEquals(itemCount, sinkReceived.sum(), "moi item van phai toi dich, backpressure chi lam CHAM chu khong lam MAT item");
    }
}
