package com.nanopool.level1;

import java.util.concurrent.atomic.AtomicInteger;

// Bai 1: Lost update - counter dua nhau. 4 bien the: int, volatile int, synchronized, AtomicInteger.
public class RaceLab {

    private int plainCounter = 0;
    private volatile int volatileCounter = 0;
    private int syncCounter = 0;
    private final AtomicInteger atomicCounter = new AtomicInteger(0);

    public void incrementPlain() {
        plainCounter++;
    }

    public void incrementVolatile() {
        volatileCounter++;
    }

    public synchronized void incrementSync() {
        syncCounter++;
    }

    public void incrementAtomic() {
        atomicCounter.incrementAndGet();
    }

    public int getPlainCounter() {
        return plainCounter;
    }

    public int getVolatileCounter() {
        return volatileCounter;
    }

    public int getSyncCounter() {
        return syncCounter;
    }

    public int getAtomicCounter() {
        return atomicCounter.get();
    }
}
