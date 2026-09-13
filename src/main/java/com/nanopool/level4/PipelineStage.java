package com.nanopool.level4;

import com.nanopool.level2.BoundedTaskQueue;
import com.nanopool.level3.NanoPool;
import com.nanopool.level3.RejectionPolicy;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

// Bai 15: mot tang (stage) cua pipeline nhieu tang. Khac voi cach dung NanoPool.execute()
// truoc gio (moi lan mot task ngan), o day moi worker cua hostPool chay MOT vong lap tieu
// thu (consumeLoop) VO HAN - "task" duy nhat no nhan la chinh vong lap do. inputQueue moi
// la hang doi du lieu thuc su giua cac tang (khong phai hang doi task ben trong NanoPool).
//
// Poison pill: de dung mot stage co N worker (concurrency = N) can dung DUNG N "vien thuoc
// doc" - moi worker take() duoc dung 1 vien roi tu thoat vong lap. Worker CUOI CUNG nhan du
// N vien (dem bang AtomicInteger) moi la nguoi chuyen tiep N' vien thuoc doc xuong tang ke
// (N' = concurrency cua tang ke, co the khac N) va goi shutdown() tren hostPool cua CHINH no.
// Day la ky thuat that duoc dung trong cac he thong hang doi da worker (vd JMS, Kafka
// consumer group) khi khong muon block cho ca stage truoc terminate xong roi moi bat dau
// dong stage sau.
public class PipelineStage<IN, OUT> {

    public static final Object POISON_PILL = new Object();

    private final String name;
    private final int concurrency;
    private final Function<IN, OUT> processFn;
    private final BoundedTaskQueue<Object> inputQueue;
    private final NanoPool hostPool;
    private final PipelineStage<OUT, ?> nextStage;

    private final AtomicInteger poisonsSeen = new AtomicInteger();
    private final LongAdder processedCount = new LongAdder();
    private final long[] latenciesNanos;
    private final AtomicInteger latencyIndex = new AtomicInteger();

    public PipelineStage(String name, int concurrency, int queueCapacity, int expectedItems,
                          Function<IN, OUT> processFn, PipelineStage<OUT, ?> nextStage) {
        this.name = name;
        this.concurrency = concurrency;
        this.processFn = processFn;
        this.nextStage = nextStage;
        this.inputQueue = new BoundedTaskQueue<>(queueCapacity);
        this.hostPool = new NanoPool(concurrency, concurrency, concurrency, 0, RejectionPolicy.ABORT);
        this.latenciesNanos = new long[Math.max(1, expectedItems)];
    }

    // Khoi dong dung concurrency worker - moi worker se chay consumeLoop() vo han cho toi
    // khi nhan duoc vien thuoc doc cua rieng no.
    public void start() {
        for (int i = 0; i < concurrency; i++) {
            hostPool.execute(this::consumeLoop);
        }
    }

    // Goi tu tang truoc (hoac tu nguon) - BLOCK neu inputQueue day, day chinh la diem
    // backpressure lan nguoc: neu tang nay xu ly cham hon tang truoc dua vao, nguoi goi
    // submit() se bi chan o day, tuc la CHINH worker cua tang truoc bi chan.
    public void submit(IN item) throws InterruptedException {
        inputQueue.put(item);
    }

    @SuppressWarnings("unchecked")
    private void consumeLoop() {
        while (true) {
            Object item;
            try {
                item = inputQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (item == POISON_PILL) {
                onPoisonReceived();
                return;
            }
            long t0 = System.nanoTime();
            OUT result = processFn.apply((IN) item);
            recordLatency(System.nanoTime() - t0);
            processedCount.increment();
            if (nextStage != null) {
                try {
                    nextStage.submit(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void recordLatency(long nanos) {
        int idx = latencyIndex.getAndIncrement();
        if (idx < latenciesNanos.length) {
            latenciesNanos[idx] = nanos;
        }
    }

    private void onPoisonReceived() {
        if (poisonsSeen.incrementAndGet() == concurrency) {
            if (nextStage != null) {
                nextStage.poisonAll();
            }
            hostPool.shutdown();
        }
    }

    // Enqueue dung "concurrency" vien thuoc doc - moi worker cua stage nay se an dung 1 vien.
    public void poisonAll() {
        for (int i = 0; i < concurrency; i++) {
            try {
                inputQueue.put(POISON_PILL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Dong "bang interrupt" - KHONG cho worker co co hoi hoan tat item dang xu ly do hay
    // chuyen tiep no xuong tang sau. Dung de mo phong "shutdown bang interrupt lam mat item".
    public void shutdownNowAbandoningInFlightWork() {
        hostPool.shutdownNow();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return hostPool.awaitTermination(timeout, unit);
    }

    public String name() {
        return name;
    }

    public long processedCount() {
        return processedCount.sum();
    }

    public int queueDepth() {
        return inputQueue.size();
    }

    public LatencyStats latencyStats() {
        int n = Math.min(latencyIndex.get(), latenciesNanos.length);
        long[] copy = Arrays.copyOf(latenciesNanos, n);
        Arrays.sort(copy);
        return new LatencyStats(n, percentile(copy, 50), percentile(copy, 99));
    }

    private static long percentile(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        return sorted[idx];
    }

    public record LatencyStats(int count, long p50Nanos, long p99Nanos) {
    }
}
