package com.nanopool.level4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Bai 15: doi chieu voi PoisonPillShutdownTest - lan nay dong pipeline bang shutdownNow()
// (interrupt() thang vao worker) NGAY TRONG LUC con item dang nam trong hang doi hoac dang
// duoc xu ly do dang (sink co delay gia lap I/O cham). Khac voi poison pill (di theo dung
// hang doi FIFO, chi "toi luot" sau khi moi item that da qua), interrupt() khong quan tam
// hang doi con gi - no cham dut NGAY LAP TUC, nen item dang xu ly do dang / con nam trong
// hang doi se KHONG BAO GIO toi duoc sink.
class InterruptShutdownLosesItemsTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void interruptingMidFlightLosesItemsThatWereQueuedOrInProgress() throws Exception {
        int itemCount = 200;
        int sinkDelayMs = 20; // gia lap sink cham - de chac chan con item dang "in flight" luc ta interrupt
        LongAdder sinkReceived = new LongAdder();
        CountDownLatch firstSinkItemStarted = new CountDownLatch(1);

        PipelineStage<Integer, Integer> sink = new PipelineStage<>(
                "sink", 1, 4, itemCount,
                x -> {
                    firstSinkItemStarted.countDown();
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
                "transform", 1, 4, itemCount, x -> x * 2, sink);
        PipelineStage<String, Integer> parse = new PipelineStage<>(
                "parse", 1, 4, itemCount, Integer::parseInt, transform);

        sink.start();
        transform.start();
        parse.start();

        Thread source = new Thread(() -> {
            try {
                for (int i = 0; i < itemCount; i++) {
                    parse.submit(Integer.toString(i));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        source.start();

        // Cho den khi sink bat dau xu ly item DAU TIEN - dam bao co it nhat 1 item "dang do
        // dang" va (voi sinkDelayMs=20ms trong khi nguon co the day toi 200 item gan nhu ngay
        // lap tuc vao cac hang doi con boundedCapacity=4) chac chan con nhieu item khac dang
        // xep hang phia sau no o cac tang - vi sink la diem cham nhat, backpressure don ca
        // pipeline lai ngay tu som.
        assertTrue(firstSinkItemStarted.await(5, TimeUnit.SECONDS));

        // Dong NGAY bang interrupt - khong doi cho hang doi ro can nhu poison pill.
        parse.shutdownNowAbandoningInFlightWork();
        transform.shutdownNowAbandoningInFlightWork();
        sink.shutdownNowAbandoningInFlightWork();

        source.interrupt();
        source.join(2000);

        System.out.println("MEASURED interrupt-shutdown item loss: sink nhan duoc "
                + sinkReceived.sum() + "/" + itemCount + " item");

        assertTrue(sinkReceived.sum() < itemCount,
                "shutdown bang interrupt phai lam mat it nhat mot vai item - sink chi nhan duoc "
                        + sinkReceived.sum() + "/" + itemCount);
    }
}
