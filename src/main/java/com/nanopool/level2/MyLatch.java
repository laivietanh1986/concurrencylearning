package com.nanopool.level2;

public interface MyLatch {
    void await() throws InterruptedException;

    void countDown();

    long getCount();
}
