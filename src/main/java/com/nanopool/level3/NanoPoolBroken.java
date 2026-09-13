package com.nanopool.level3;

import com.nanopool.level2.BoundedTaskQueue;

// Bai 9, buoc 1: KHONG try/catch quanh task.run() - de quan sat dieu gi xay ra
// khi mot task nem exception trong mot thread pool tu viet tay.
public class NanoPoolBroken {

    private final BoundedTaskQueue<Runnable> queue;
    private final Thread[] workers;

    public NanoPoolBroken(int nThreads, int queueCapacity) {
        this.queue = new BoundedTaskQueue<>(queueCapacity);
        this.workers = new Thread[nThreads];
        for (int i = 0; i < nThreads; i++) {
            workers[i] = new Thread(this::workerLoop, "nanopool-broken-" + i);
            workers[i].start();
        }
    }

    public void execute(Runnable task) {
        try {
            queue.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int aliveWorkerCount() {
        int alive = 0;
        for (Thread w : workers) {
            if (w.isAlive()) {
                alive++;
            }
        }
        return alive;
    }

    public int queueSize() {
        return queue.size();
    }

    private void workerLoop() {
        while (true) {
            Runnable task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            task.run(); // co tinh KHONG bat exception - mot task nem loi se giet ca thread nay
        }
    }
}
