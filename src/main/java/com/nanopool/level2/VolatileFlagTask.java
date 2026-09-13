package com.nanopool.level2;

import java.util.concurrent.atomic.AtomicLong;

// Bai 8, cach 1: dung mot co volatile boolean de bao task dung lai.
// Chi hoat dong voi task CPU-bound, tu kiem tra co moi vong lap - khong giup
// gi neu task dang block trong mot cuoc goi khac (sleep/wait/IO).
public class VolatileFlagTask implements Runnable {

    private volatile boolean cancelled = false;
    private final AtomicLong iterations = new AtomicLong();

    public void cancel() {
        cancelled = true;
    }

    public long getIterations() {
        return iterations.get();
    }

    @Override
    public void run() {
        while (!cancelled) {
            iterations.incrementAndGet();
        }
    }
}
