package com.nanopool.level2;

// Bai 5 (co tinh sai): guarded wait viet bang "if" thay vi "while".
public class MyCountDownLatchSyncBroken implements MyLatch {

    private long count;

    public MyCountDownLatchSyncBroken(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count < 0");
        }
        this.count = count;
    }

    @Override
    public synchronized void await() throws InterruptedException {
        if (count > 0) {
            wait();
        }
    }

    @Override
    public synchronized void countDown() {
        if (count > 0) {
            count--;
            if (count == 0) {
                notifyAll();
            }
        }
    }

    @Override
    public synchronized long getCount() {
        return count;
    }

    // Test-only hook: manufactures the exact hazard "if" cannot survive - a
    // notifyAll() firing while the guarded condition is still true (a
    // spurious wakeup, or an unrelated notify on the same monitor).
    synchronized void debugForceSpuriousWakeup() {
        notifyAll();
    }
}
