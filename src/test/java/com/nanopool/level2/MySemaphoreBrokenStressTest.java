package com.nanopool.level2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

class MySemaphoreBrokenStressTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void notifySingleWakeupMayStrandAWaiterUnderInterruption() throws InterruptedException {
        int rounds = 500;
        int threadCount = 4;
        int stuckRounds = 0;

        for (int round = 0; round < rounds; round++) {
            MySemaphoreBroken sem = new MySemaphoreBroken(0);
            Thread[] threads = new Thread[threadCount];
            for (int i = 0; i < threadCount; i++) {
                Thread t = new Thread(() -> {
                    try {
                        sem.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                t.setDaemon(true); // never let a truly-stuck thread hang the JVM/build
                threads[i] = t;
                t.start();
            }
            Thread.sleep(30); // let all 4 threads genuinely park inside wait() first

            // Release exactly one permit per thread: if nothing were ever
            // wasted, all 4 - interrupted or not - would eventually see a
            // permit and finish. Interrupt half right as the releases (and
            // their notify() calls) are landing, racing for the same threads.
            for (int i = 0; i < threadCount / 2; i++) {
                threads[i].interrupt();
            }
            for (int i = 0; i < threadCount; i++) {
                sem.release();
            }

            for (Thread t : threads) {
                t.join(200);
            }
            for (Thread t : threads) {
                if (t.isAlive()) {
                    stuckRounds++;
                    break;
                }
            }
        }

        System.out.println("MySemaphoreBroken: rounds with a permanently stuck waiter = " + stuckRounds + " / " + rounds);
        // Intentionally no assertion. JLS leaves the interrupt-vs-notify race
        // for Object.wait()/notify() unspecified; whether it reproduces here
        // is a real, honest measurement - see docs/notes/level2-bai06-semaphore.md.
    }
}
