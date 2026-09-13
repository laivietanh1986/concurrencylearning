package com.nanopool.level2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ThunderingHerdTest {

    // High contention on purpose: tiny capacity + many producers AND many
    // consumers all blocked on the SAME lock at once, so every state change
    // has the maximum number of "innocent bystander" waiters to needlessly
    // wake up in the single-Condition version.
    private static final int CAPACITY = 2;
    private static final int PRODUCERS = 8;
    private static final int CONSUMERS = 8;
    private static final int ITEMS_PER_PRODUCER = 4_000;

    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void singleConditionWakesUpMoreThreadsPerOperationThanTwoConditions() throws InterruptedException {
        BoundedTaskQueue<Integer> twoCondition = new BoundedTaskQueue<>(CAPACITY);
        BoundedTaskQueueSingleCondition<Integer> singleCondition = new BoundedTaskQueueSingleCondition<>(CAPACITY);

        double twoConditionRatio = runAndMeasureWakeupRatio(twoCondition, twoCondition::getWakeups, twoCondition::getOperations);
        double singleConditionRatio = runAndMeasureWakeupRatio(singleCondition, singleCondition::getWakeups, singleCondition::getOperations);

        System.out.printf("wakeups-per-operation: twoCondition=%.2f singleCondition=%.2f%n",
                twoConditionRatio, singleConditionRatio);

        assertTrue(singleConditionRatio > twoConditionRatio,
                "signalAll() on one Condition should cause more wakeups per operation than signal() on two Conditions");
    }

    private double runAndMeasureWakeupRatio(TaskQueue<Integer> queue, java.util.function.LongSupplier wakeups,
                                             java.util.function.LongSupplier operations) throws InterruptedException {
        int totalItems = PRODUCERS * ITEMS_PER_PRODUCER;

        Thread[] producers = new Thread[PRODUCERS];
        for (int p = 0; p < PRODUCERS; p++) {
            producers[p] = new Thread(() -> {
                try {
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        queue.put(i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        java.util.concurrent.atomic.AtomicInteger reserved = new java.util.concurrent.atomic.AtomicInteger(0);
        Thread[] consumers = new Thread[CONSUMERS];
        for (int c = 0; c < CONSUMERS; c++) {
            consumers[c] = new Thread(() -> {
                try {
                    while (reserved.getAndIncrement() < totalItems) {
                        queue.take();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        for (Thread t : consumers) {
            t.start();
        }
        for (Thread t : producers) {
            t.start();
        }
        for (Thread t : producers) {
            t.join();
        }
        for (Thread t : consumers) {
            t.join();
        }

        return (double) wakeups.getAsLong() / (double) operations.getAsLong();
    }
}
