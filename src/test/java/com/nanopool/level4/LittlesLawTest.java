package com.nanopool.level4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Bai 15: do that Little's Law (L = lambda * W) tren mot stage bi bao hoa co tinh, de co
// can cu CHON queueCapacity thay vi doan mo. L = so item trung binh dang nam trong stage
// (hang doi + dang duoc xu ly), do bang cach LAY MAU dinh ky. lambda = thong luong thuc te
// (item/giay) stage xu ly duoc khi bao hoa - bi chan boi toc do service, khong phai toc do
// nguon day vao. W = thoi gian trung binh MOT item nam trong stage, do truc tiep bang
// timestamp tu luc submit() toi luc xu ly xong.
//
// Quan trong: thoi gian chay phai LON HON NHIEU so voi W du kien, neu khong hieu ung "khoi
// dong" (queue con dang day tu 0 len day) va "ket thuc" (con item dang do dang khi ta dung
// do) se chiem ty trong lon va lam sai lech phep do (Little's Law la mot dinh luat ve trang
// thai on dinh/trung binh dai han, khong dung cho mot khoang thoi gian ngan bang chinh W).
class LittlesLawTest {

    private record TimedItem(long submitNanos) {
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void measuredQueueDepthMatchesArrivalRateTimesTimeInSystem() throws Exception {
        int serviceDelayMs = 5;
        int queueCapacity = 20; // nho so voi thoi gian chay, de W du kien (~queueCapacity*serviceDelayMs) << durationMs
        long durationMs = 3000;

        LongAdder processed = new LongAdder();
        List<Long> timeInSystemNanos = new CopyOnWriteArrayList<>();
        List<Integer> queueDepthSamples = new CopyOnWriteArrayList<>();

        PipelineStage<TimedItem, Object> sink = new PipelineStage<>(
                "sink", 1, queueCapacity, 2_000_000,
                item -> {
                    try {
                        Thread.sleep(serviceDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    timeInSystemNanos.add(System.nanoTime() - item.submitNanos());
                    processed.increment();
                    return null;
                },
                null);
        sink.start();

        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                queueDepthSamples.add(sink.queueDepth());
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.start();

        // Feeder day nhanh het muc co the - luon nhanh hon toc do service, nen sink bao hoa va
        // tro thanh "nut that co chai": lambda do duoc se bang dung toc do service (1/serviceDelayMs),
        // KHONG phai toc do feeder co the day.
        Thread feeder = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    sink.submit(new TimedItem(System.nanoTime()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        feeder.start();

        Thread.sleep(durationMs);
        feeder.interrupt();
        sampler.interrupt();
        feeder.join(2000);
        sampler.join(2000);

        double avgQueueDepth = queueDepthSamples.stream().mapToInt(Integer::intValue).average().orElseThrow();
        double avgTimeInSystemSec = timeInSystemNanos.stream().mapToLong(Long::longValue).average().orElseThrow() / 1_000_000_000.0;
        double throughputPerSec = processed.sum() / (durationMs / 1000.0);
        double predictedL = throughputPerSec * avgTimeInSystemSec;
        double ratio = avgQueueDepth / predictedL;

        System.out.println("MEASURED Little's Law: L=" + String.format("%.2f", avgQueueDepth)
                + " lambda=" + String.format("%.1f", throughputPerSec) + " item/s"
                + " W=" + String.format("%.4f", avgTimeInSystemSec) + "s"
                + " lambda*W=" + String.format("%.2f", predictedL)
                + " ty le L/(lambda*W)=" + String.format("%.3f", ratio)
                + " processed=" + processed.sum() + " samples=" + queueDepthSamples.size());

        sink.shutdownNowAbandoningInFlightWork();

        assertTrue(ratio > 0.7 && ratio < 1.3,
                "L do duoc (" + avgQueueDepth + ") phai xap xi lambda*W (" + predictedL + ") theo Little's Law, ty le=" + ratio);
    }
}
