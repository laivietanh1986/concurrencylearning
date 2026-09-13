package com.nanopool.level2;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

// Bai 6: MySemaphore dung ReentrantLock + Condition.
public class MySemaphore {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition permitAvailable = lock.newCondition();
    private int permits;

    public MySemaphore(int permits) {
        if (permits < 0) {
            throw new IllegalArgumentException("permits < 0");
        }
        this.permits = permits;
    }

    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (permits == 0) {
                permitAvailable.await();
            }
            permits--;
        } finally {
            lock.unlock();
        }
    }

    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        long nanosLeft = unit.toNanos(timeout);
        lock.lock();
        try {
            while (permits == 0) {
                if (nanosLeft <= 0) {
                    return false;
                }
                nanosLeft = permitAvailable.awaitNanos(nanosLeft);
            }
            permits--;
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void release() {
        lock.lock();
        try {
            permits++;
            permitAvailable.signal();
        } finally {
            lock.unlock();
        }
    }

    public int availablePermits() {
        lock.lock();
        try {
            return permits;
        } finally {
            lock.unlock();
        }
    }
}
