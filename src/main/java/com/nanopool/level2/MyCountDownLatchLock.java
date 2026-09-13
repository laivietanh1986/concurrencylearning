package com.nanopool.level2;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

// Bai 5: cung logic nhu MyCountDownLatchSync nhung dung ReentrantLock + Condition.
public class MyCountDownLatchLock implements MyLatch {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition counted = lock.newCondition();
    private long count;

    public MyCountDownLatchLock(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count < 0");
        }
        this.count = count;
    }

    @Override
    public void await() throws InterruptedException {
        lock.lock();
        try {
            while (count > 0) {
                counted.await();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void countDown() {
        lock.lock();
        try {
            if (count > 0) {
                count--;
                if (count == 0) {
                    counted.signalAll();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long getCount() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    void debugForceSpuriousWakeup() {
        lock.lock();
        try {
            counted.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
