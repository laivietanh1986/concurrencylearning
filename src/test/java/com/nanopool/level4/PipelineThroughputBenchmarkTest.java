package com.nanopool.level4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Bai 15: do throughput end-to-end va p50/p99 tung tang cua ca pipeline 3 tang voi dung
// 1.000.000 item nhu de bai, KHONG co do cham gia lap (de do toc do "that" ma kien truc nay
// dat duoc). Ket qua p50/p99/throughput la SO DO THAT tu chinh may nay, khong phai doan.
class PipelineThroughputBenchmarkTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void measuresEndToEndThroughputAndPerStageLatencyPercentiles() throws Exception {
        int itemCount = 1_000_000;
        LongAdder sinkSum = new LongAdder();

        PipelineStage<Integer, Integer> sink = new PipelineStage<>(
                "sink", 4, 1000, itemCount,
                x -> {
                    sinkSum.add(x);
                    return x;
                },
                null);
        PipelineStage<Integer, Integer> transform = new PipelineStage<>(
                "transform", 4, 1000, itemCount, x -> x * 2, sink);
        PipelineStage<String, Integer> parse = new PipelineStage<>(
                "parse", 4, 1000, itemCount, Integer::parseInt, transform);

        sink.start();
        transform.start();
        parse.start();

        long t0 = System.nanoTime();
        for (int i = 0; i < itemCount; i++) {
            parse.submit(Integer.toString(i));
        }
        parse.poisonAll();

        assertTrue(parse.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(transform.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(sink.awaitTermination(30, TimeUnit.SECONDS));
        long totalMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(itemCount, sink.processedCount(), "sink phai nhan du moi item");

        long expectedSum = 0;
        for (int i = 0; i < itemCount; i++) {
            expectedSum += i * 2L;
        }
        assertEquals(expectedSum, sinkSum.sum(), "tong ket qua o sink phai dung (khong item nao bi xu ly sai/trung)");

        double throughputPerSec = itemCount / (totalMs / 1000.0);

        for (PipelineStage<?, ?> stage : new PipelineStage<?, ?>[] {parse, transform, sink}) {
            PipelineStage.LatencyStats stats = stage.latencyStats();
            System.out.println("MEASURED pipeline stage=" + stage.name()
                    + " count=" + stats.count()
                    + " p50=" + (stats.p50Nanos() / 1000) + "us"
                    + " p99=" + (stats.p99Nanos() / 1000) + "us");
        }
        System.out.println("MEASURED pipeline end-to-end: " + itemCount + " item trong " + totalMs
                + "ms = " + String.format("%.0f", throughputPerSec) + " item/s");
    }
}
