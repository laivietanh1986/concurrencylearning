package com.nanopool.level2;

import java.util.concurrent.atomic.AtomicLong;

// Bai 8, cach 2 (co tinh sai): nuot InterruptedException, khong lam gi ca.
// Thread.interrupt() se khong bao gio dung duoc task nay.
public class InterruptibleTaskBroken implements Runnable {

    private final AtomicLong iterations = new AtomicLong();

    public long getIterations() {
        return iterations.get();
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(10);
                iterations.incrementAndGet();
            } catch (InterruptedException e) {
                // BUG: nuot exception, khong restore co, khong break.
            }
        }
    }
}
