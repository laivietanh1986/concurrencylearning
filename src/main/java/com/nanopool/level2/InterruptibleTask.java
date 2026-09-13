package com.nanopool.level2;

import java.util.concurrent.atomic.AtomicLong;

// Bai 8, cach 2 (da sua): kiem tra co interrupt moi vong lap, va khi bat duoc
// InterruptedException phai restore lai co truoc khi thoat (bai 6's rule).
public class InterruptibleTask implements Runnable {

    private final AtomicLong iterations = new AtomicLong();

    public long getIterations() {
        return iterations.get();
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(10);
                iterations.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
