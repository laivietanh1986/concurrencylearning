package com.nanopool.level1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsafePublicationTest {

    private enum Outcome { NOT_OBSERVED, CONSISTENT, TORN }

    // Plain-reference variants (unsafe, finalFields) must NOT call any
    // time/IO method inside the spin - that would accidentally add a memory
    // barrier and hide the very bug we are trying to observe (see bai 2).
    private static final int PLAIN_SPIN_BOUND = 2_000_000;
    private static final long SAFE_SPIN_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void unsafePublicationIsNeverAssertedSafe() throws InterruptedException {
        int rounds = 1000;
        int notObserved = 0, consistent = 0, torn = 0;
        for (int i = 0; i < rounds; i++) {
            Outcome o = unsafeRound();
            switch (o) {
                case NOT_OBSERVED -> notObserved++;
                case CONSISTENT -> consistent++;
                case TORN -> torn++;
            }
        }
        System.out.printf("unsafe: notObserved=%d consistent=%d torn=%d / %d%n",
                notObserved, consistent, torn, rounds);
        // Intentionally no assertion: on x86 TSO this almost never reproduces
        // "torn" in practice even though the JMM permits it. Absence of a
        // failure here does NOT mean the code is correct - see docs/notes.
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void finalFieldsAreNeverTornWhenTheReferenceIsObserved() throws InterruptedException {
        int rounds = 1000;
        int notObserved = 0, torn = 0;
        for (int i = 0; i < rounds; i++) {
            Outcome o = finalFieldsRound();
            if (o == Outcome.NOT_OBSERVED) notObserved++;
            if (o == Outcome.TORN) torn++;
        }
        System.out.printf("finalFields: notObserved=%d / %d, torn=%d%n", notObserved, rounds, torn);
        // The reference itself is still a plain field, so "not observed" is
        // expected sometimes (same JIT-hoisting story as bai 2). What must
        // NEVER happen is a torn object once the reference IS visible - that
        // is the final-field freeze guarantee (JLS 17.5).
        assertEquals(0, torn, "final fields must never appear partially initialized once the reference is visible");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void volatileReferencePublicationIsAlwaysSafe() throws InterruptedException {
        int rounds = 500;
        for (int i = 0; i < rounds; i++) {
            Outcome o = volatileRound();
            assertTrue(o != Outcome.NOT_OBSERVED, "volatile reference should become visible well within 200ms");
            assertTrue(o != Outcome.TORN, "volatile write is a StoreStore/StoreLoad barrier - fields cannot be torn");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void synchronizedPublicationIsAlwaysSafe() throws InterruptedException {
        int rounds = 500;
        for (int i = 0; i < rounds; i++) {
            Outcome o = syncRound();
            assertTrue(o != Outcome.NOT_OBSERVED, "synchronized read should observe the publish well within 200ms");
            assertTrue(o != Outcome.TORN, "monitor exit/enter is a full memory barrier - fields cannot be torn");
        }
    }

    private Outcome unsafeRound() throws InterruptedException {
        UnsafePublication lab = new UnsafePublication();
        Thread writer = new Thread(() -> lab.publishUnsafe(1, 2, "ready"));
        AtomicReference<UnsafePublication.Holder> seen = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            UnsafePublication.Holder h = null;
            for (int i = 0; i < PLAIN_SPIN_BOUND && h == null; i++) {
                h = lab.readUnsafe();
            }
            seen.set(h);
        });
        reader.start();
        writer.start();
        writer.join();
        reader.join();

        UnsafePublication.Holder h = seen.get();
        if (h == null) return Outcome.NOT_OBSERVED;
        boolean consistent = h.a == 1 && h.b == 2 && "ready".equals(h.label);
        return consistent ? Outcome.CONSISTENT : Outcome.TORN;
    }

    private Outcome finalFieldsRound() throws InterruptedException {
        UnsafePublication lab = new UnsafePublication();
        Thread writer = new Thread(() -> lab.publishFinalFields(1, 2, "ready"));
        AtomicReference<UnsafePublication.FinalHolder> seen = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            UnsafePublication.FinalHolder h = null;
            for (int i = 0; i < PLAIN_SPIN_BOUND && h == null; i++) {
                h = lab.readFinalFields();
            }
            seen.set(h);
        });
        reader.start();
        writer.start();
        writer.join();
        reader.join();

        UnsafePublication.FinalHolder h = seen.get();
        if (h == null) return Outcome.NOT_OBSERVED;
        boolean consistent = h.a == 1 && h.b == 2 && "ready".equals(h.label);
        return consistent ? Outcome.CONSISTENT : Outcome.TORN;
    }

    private Outcome volatileRound() throws InterruptedException {
        UnsafePublication lab = new UnsafePublication();
        Thread writer = new Thread(() -> lab.publishVolatile(1, 2, "ready"));
        AtomicReference<UnsafePublication.Holder> seen = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            UnsafePublication.Holder h = null;
            long start = System.nanoTime();
            while (h == null && System.nanoTime() - start < SAFE_SPIN_BUDGET_NANOS) {
                h = lab.readVolatile();
            }
            seen.set(h);
        });
        reader.start();
        writer.start();
        writer.join();
        reader.join();

        UnsafePublication.Holder h = seen.get();
        if (h == null) return Outcome.NOT_OBSERVED;
        boolean consistent = h.a == 1 && h.b == 2 && "ready".equals(h.label);
        return consistent ? Outcome.CONSISTENT : Outcome.TORN;
    }

    private Outcome syncRound() throws InterruptedException {
        UnsafePublication lab = new UnsafePublication();
        Thread writer = new Thread(() -> lab.publishSync(1, 2, "ready"));
        AtomicReference<UnsafePublication.Holder> seen = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            UnsafePublication.Holder h = null;
            long start = System.nanoTime();
            while (h == null && System.nanoTime() - start < SAFE_SPIN_BUDGET_NANOS) {
                h = lab.readSync();
            }
            seen.set(h);
        });
        reader.start();
        writer.start();
        writer.join();
        reader.join();

        UnsafePublication.Holder h = seen.get();
        if (h == null) return Outcome.NOT_OBSERVED;
        boolean consistent = h.a == 1 && h.b == 2 && "ready".equals(h.label);
        return consistent ? Outcome.CONSISTENT : Outcome.TORN;
    }
}
