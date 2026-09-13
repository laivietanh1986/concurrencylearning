package com.nanopool.level1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibilityLoopTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void volatileFlagStopsTheLoop() throws InterruptedException {
        VisibilityLoop lab = new VisibilityLoop();
        Thread worker = new Thread(lab::runVolatileLoop, "volatile-worker");
        worker.setDaemon(true);
        worker.start();

        Thread.sleep(300);
        lab.requestStopVolatile();
        worker.join(5000);

        assertFalse(worker.isAlive(), "volatile write must become visible to the worker and stop it");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void printlnInTheLoopAlsoMakesTheFlagVisible() throws InterruptedException {
        VisibilityLoop lab = new VisibilityLoop();
        Thread worker = new Thread(lab::runPlainLoopWithPrintln, "println-worker");
        worker.setDaemon(true);
        worker.start();

        Thread.sleep(300);
        lab.requestStopPlain();
        worker.join(5000);

        assertFalse(worker.isAlive(), "System.out.println is synchronized internally, its monitor exit/enter forces a memory barrier");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void plainFlagWithNoMemoryBarrierNeverStops() throws InterruptedException {
        VisibilityLoop lab = new VisibilityLoop();
        Thread worker = new Thread(lab::runPlainLoop, "plain-worker");
        worker.setDaemon(true);
        worker.start();

        Thread.sleep(300);
        lab.requestStopPlain();
        worker.join(3000);

        // This assertion IS the bug under study: once the JIT proves nothing in
        // the loop body can change stopPlain, it hoists the read into a register
        // and the worker never observes main's write.
        assertTrue(worker.isAlive(), "expected the classic JIT-hoisting visibility bug to reproduce");
    }
}
