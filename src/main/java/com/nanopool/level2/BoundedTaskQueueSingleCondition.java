package com.nanopool.level2;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

// Bai 7 (de so sanh): mot Condition duy nhat + signalAll() - van dung nhung
// kem hieu qua hon (thundering herd): moi lan trang thai doi, CA producer lan
// consumer dang cho deu bi danh thuc, du phan lon se kiem tra dieu kien roi
// ngu lai ngay.
public class BoundedTaskQueueSingleCondition<T> implements TaskQueue<T> {

    private final Object[] buffer;
    private final int capacity;
    private int head;
    private int tail;
    private int count;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition stateChanged = lock.newCondition();

    private final AtomicLong wakeups = new AtomicLong();
    private final AtomicLong operations = new AtomicLong();

    public BoundedTaskQueueSingleCondition(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity <= 0");
        }
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }

    @Override
    public void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (count == capacity) {
                stateChanged.await();
                wakeups.incrementAndGet();
            }
            enqueue(item);
            operations.incrementAndGet();
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) {
                stateChanged.await();
                wakeups.incrementAndGet();
            }
            T item = dequeue();
            operations.incrementAndGet();
            stateChanged.signalAll();
            return item;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        long nanosLeft = unit.toNanos(timeout);
        lock.lock();
        try {
            while (count == capacity) {
                if (nanosLeft <= 0) {
                    return false;
                }
                nanosLeft = stateChanged.awaitNanos(nanosLeft);
                wakeups.incrementAndGet();
            }
            enqueue(item);
            operations.incrementAndGet();
            stateChanged.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanosLeft = unit.toNanos(timeout);
        lock.lock();
        try {
            while (count == 0) {
                if (nanosLeft <= 0) {
                    return null;
                }
                nanosLeft = stateChanged.awaitNanos(nanosLeft);
                wakeups.incrementAndGet();
            }
            T item = dequeue();
            operations.incrementAndGet();
            stateChanged.signalAll();
            return item;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }

    long getWakeups() {
        return wakeups.get();
    }

    long getOperations() {
        return operations.get();
    }

    private void enqueue(T item) {
        buffer[tail] = item;
        tail = (tail + 1) % capacity;
        count++;
    }

    @SuppressWarnings("unchecked")
    private T dequeue() {
        T item = (T) buffer[head];
        buffer[head] = null;
        head = (head + 1) % capacity;
        count--;
        return item;
    }
}
