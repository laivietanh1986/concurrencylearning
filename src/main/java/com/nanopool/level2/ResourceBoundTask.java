package com.nanopool.level2;

import java.util.concurrent.atomic.AtomicLong;

public class ResourceBoundTask implements Runnable {

    private final BlockingResource resource;
    private final AtomicLong iterations = new AtomicLong();
    private volatile boolean stoppedByClose = false;

    public ResourceBoundTask(BlockingResource resource) {
        this.resource = resource;
    }

    public long getIterations() {
        return iterations.get();
    }

    public boolean wasStoppedByClose() {
        return stoppedByClose;
    }

    @Override
    public void run() {
        try {
            while (true) {
                resource.read();
                iterations.incrementAndGet();
            }
        } catch (ResourceClosedException e) {
            stoppedByClose = true;
        }
    }
}
