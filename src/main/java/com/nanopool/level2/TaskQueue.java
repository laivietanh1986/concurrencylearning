package com.nanopool.level2;

import java.util.concurrent.TimeUnit;

public interface TaskQueue<T> {

    void put(T item) throws InterruptedException;

    T take() throws InterruptedException;

    boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException;

    T poll(long timeout, TimeUnit unit) throws InterruptedException;

    int size();

    int capacity();
}
