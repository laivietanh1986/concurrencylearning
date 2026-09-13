package com.nanopool.level4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Bai 15: shutdown CO THU TU bang poison pill. Sau khi nguon da submit het item that, ta
// "dau doc" tang dau tien (poisonAll()) - vien thuoc doc di theo DUNG con duong cua du lieu
// that (qua hang doi FIFO cua tung tang), nen no chi toi noi SAU tat ca item that o truoc no
// da duoc xu ly va chuyen tiep. Moi tang tu day chuyen sang tang ke khi CA "concurrency"
// worker cua no da an du thuoc doc. Ket qua: khong item nao bi mat, du pipeline dang chay.
class PoisonPillShutdownTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void gracefulPoisonPillShutdownProcessesEveryItemBeforeTerminating() throws Exception {
        int itemCount = 500;
        Set<Integer> sinkResults = ConcurrentHashMap.newKeySet();

        PipelineStage<Integer, Integer> sink = new PipelineStage<>(
                "sink", 2, 8, itemCount,
                x -> {
                    sinkResults.add(x);
                    return x;
                },
                null);
        PipelineStage<Integer, Integer> transform = new PipelineStage<>(
                "transform", 2, 8, itemCount, x -> x * 2, sink);
        PipelineStage<String, Integer> parse = new PipelineStage<>(
                "parse", 2, 8, itemCount, Integer::parseInt, transform);

        sink.start();
        transform.start();
        parse.start();

        for (int i = 0; i < itemCount; i++) {
            parse.submit(Integer.toString(i));
        }

        // Dau doc tang dau tien - vien thuoc doc di theo dung con duong FIFO cua du lieu that,
        // nen no chi den sau khi tat ca item that o TRUOC no trong hang doi da duoc xu ly.
        parse.poisonAll();

        assertTrue(parse.awaitTermination(10, TimeUnit.SECONDS), "tang parse phai terminate");
        assertTrue(transform.awaitTermination(10, TimeUnit.SECONDS), "tang transform phai terminate");
        assertTrue(sink.awaitTermination(10, TimeUnit.SECONDS), "tang sink phai terminate");

        assertEquals(itemCount, parse.processedCount(), "parse phai xu ly du moi item");
        assertEquals(itemCount, transform.processedCount(), "transform phai xu ly du moi item");
        assertEquals(itemCount, sink.processedCount(), "sink phai nhan du moi item - khong mat item nao khi shutdown co thu tu");
        assertEquals(itemCount, sinkResults.size(), "moi item phai toi sink dung MOT LAN, khong trung khong thieu");

        for (int i = 0; i < itemCount; i++) {
            assertTrue(sinkResults.contains(i * 2), "ket qua transform*2 cua item " + i + " phai co mat o sink");
        }
    }
}
