package com.nanopool.level2;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

// Bai 7: circular buffer MPMC voi hai Condition rieng (notFull/notEmpty).
public class BoundedTaskQueue<T> implements TaskQueue<T> {

    private final Object[] buffer;
    private final int capacity;
    private int head;
    private int tail;
    private int count;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    // Test-only counters to measure the thundering-herd effect (bai 7 docs).
    private final AtomicLong wakeups = new AtomicLong();
    private final AtomicLong operations = new AtomicLong();

    public BoundedTaskQueue(int capacity) {
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
                notFull.await();
                wakeups.incrementAndGet();
            }
            enqueue(item);
            operations.incrementAndGet();
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) {
                notEmpty.await();
                wakeups.incrementAndGet();
            }
            T item = dequeue();
            operations.incrementAndGet();
            notFull.signal();
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
                nanosLeft = notFull.awaitNanos(nanosLeft);
                wakeups.incrementAndGet();
            }
            enqueue(item);
            operations.incrementAndGet();
            notEmpty.signal();
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
                nanosLeft = notEmpty.awaitNanos(nanosLeft);
                wakeups.incrementAndGet();
            }
            T item = dequeue();
            operations.incrementAndGet();
            notFull.signal();
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

    // Bai 11: dung de "vet" mot task ra khoi hang doi khi no thua race voi shutdown()
    // (task da duoc enqueue nhung pool chuyen khoi RUNNING truoc khi co worker nao kip lay ra).
    public boolean remove(T item) {
        lock.lock();
        try {
            Object[] remaining = new Object[count];
            int idx = head;
            int n = 0;
            boolean found = false;
            for (int i = 0; i < count; i++) {
                Object elem = buffer[idx];
                if (!found && elem == item) {
                    found = true;
                } else {
                    remaining[n++] = elem;
                }
                idx = (idx + 1) % capacity;
            }
            if (found) {
                for (int i = 0; i < capacity; i++) {
                    buffer[i] = (i < n) ? remaining[i] : null;
                }
                head = 0;
                count = n;
                tail = n % capacity;
                notFull.signal();
            }
            return found;
        } finally {
            lock.unlock();
        }
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
