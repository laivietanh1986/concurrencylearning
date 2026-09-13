package com.nanopool.level2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundedTaskQueueTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void spscPreservesOrderWithTwoConditionQueue() throws InterruptedException {
        runSpsc(BoundedTaskQueue::new);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void spscPreservesOrderWithSingleConditionQueue() throws InterruptedException {
        runSpsc(BoundedTaskQueueSingleCondition::new);
    }

    private void runSpsc(IntFunction<TaskQueue<Integer>> factory) throws InterruptedException {
        int itemCount = 50_000;
        TaskQueue<Integer> queue = factory.apply(16);
        List<Integer> consumed = new ArrayList<>(itemCount);

        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < itemCount; i++) {
                    queue.put(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < itemCount; i++) {
                    consumed.add(queue.take());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        consumer.start();
        producer.start();
        producer.join();
        consumer.join();

        assertEquals(itemCount, consumed.size());
        for (int i = 0; i < itemCount; i++) {
            assertEquals(i, consumed.get(i), "FIFO order violated at index " + i);
        }
    }

    // "Xuong song" test - se duoc tai su dung lai o cac bai sau (vd bai 9).
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void mpmcBackboneNoLossNoDuplicationTwoCondition() throws InterruptedException {
        runMpmc(new BoundedTaskQueue<>(64));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void mpmcBackboneNoLossNoDuplicationSingleCondition() throws InterruptedException {
        runMpmc(new BoundedTaskQueueSingleCondition<>(64));
    }

    private void runMpmc(TaskQueue<Integer> queue) throws InterruptedException {
        int producers = 4;
        int consumers = 3;
        int totalItems = 100_000;
        int perProducer = totalItems / producers;

        Thread[] producerThreads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            int start = p * perProducer;
            producerThreads[p] = new Thread(() -> {
                try {
                    for (int i = start; i < start + perProducer; i++) {
                        queue.put(i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        AtomicIntegerArray seenCounts = new AtomicIntegerArray(totalItems);
        AtomicInteger reserved = new AtomicInteger(0);
        Thread[] consumerThreads = new Thread[consumers];
        for (int c = 0; c < consumers; c++) {
            consumerThreads[c] = new Thread(() -> {
                try {
                    while (true) {
                        int idx = reserved.getAndIncrement();
                        if (idx >= totalItems) {
                            break;
                        }
                        int value = queue.take();
                        seenCounts.incrementAndGet(value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        for (Thread t : consumerThreads) {
            t.start();
        }
        for (Thread t : producerThreads) {
            t.start();
        }
        for (Thread t : producerThreads) {
            t.join();
        }
        for (Thread t : consumerThreads) {
            t.join();
        }

        for (int i = 0; i < totalItems; i++) {
            assertEquals(1, seenCounts.get(i), "item " + i + " was seen " + seenCounts.get(i) + " times (expected exactly 1)");
        }
    }
}
