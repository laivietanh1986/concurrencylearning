package com.nanopool.level1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RaceLabTest {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 100_000;

    @Test
    void observeAllFourVariants() throws InterruptedException {
        RaceLab lab = new RaceLab();
        Thread[] plain = spawn(lab::incrementPlain);
        Thread[] vol = spawn(lab::incrementVolatile);
        Thread[] sync = spawn(lab::incrementSync);
        Thread[] atomic = spawn(lab::incrementAtomic);

        joinAll(plain);
        joinAll(vol);
        joinAll(sync);
        joinAll(atomic);

        int expected = THREADS * ITERATIONS;
        System.out.println("plain     = " + lab.getPlainCounter() + " (expected " + expected + ")");
        System.out.println("volatile  = " + lab.getVolatileCounter() + " (expected " + expected + ")");
        System.out.println("sync      = " + lab.getSyncCounter() + " (expected " + expected + ")");
        System.out.println("atomic    = " + lab.getAtomicCounter() + " (expected " + expected + ")");

        // synchronized and Atomic must always be exact; plain/volatile are the buggy
        // variants and are intentionally left unasserted so the race is visible.
        assertEquals(expected, lab.getSyncCounter());
        assertEquals(expected, lab.getAtomicCounter());
    }

    private Thread[] spawn(Runnable incrementOnce) {
        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    incrementOnce.run();
                }
            });
            threads[i].start();
        }
        return threads;
    }

    private void joinAll(Thread[] threads) throws InterruptedException {
        for (Thread t : threads) {
            t.join();
        }
    }
}
