package com.nanopool.level2;

// Bai 5: guarded wait dung - "while" thay vi "if".
public class MyCountDownLatchSync implements MyLatch {

    private long count;

    public MyCountDownLatchSync(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count < 0");
        }
        this.count = count;
    }

    @Override
    public synchronized void await() throws InterruptedException {
        while (count > 0) {
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

    synchronized void debugForceSpuriousWakeup() {
        notifyAll();
    }
}
