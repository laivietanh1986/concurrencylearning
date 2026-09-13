package com.nanopool.level3;

import com.nanopool.level2.BoundedTaskQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

// Bai 9, buoc 2: NanoPool v1 - N thread co dinh, moi worker lap queue.take() roi run().
// Da boc lai try/catch quanh task.run() sau khi quan sat NanoPoolBroken teo dan roi treo.
// Bai 10: them submit(Callable) tra ve Future - MyFutureTask cung chi la mot Runnable
// khac, nen tai su dung nguyen ven hang doi va worker loop.
// Bai 11: them lifecycle shutdown()/shutdownNow()/awaitTermination(). Worker loop doi tu
// queue.take() blocking sang queue.poll(50ms) de worker tu tinh dan phat hien runState da doi.
// Bai 12: them coreSize/maxSize/keepAliveMs va rejection policy pluggable. Thu tu xu ly khi
// execute(): (1) bo vao hang doi neu con cho, (2) neu hang doi day thi tao them "extra" thread
// (vuot core, toi da maxSize), (3) neu da het ca hai thi ap dung rejection policy.
// Bai 13: them PoolStats (activeWorkers/queueDepth/submitted/completed/failed/rejected).
// Bon counter cong don dung LongAdder thay vi AtomicLong - it tranh chap hon khi nhieu
// thread cung ghi (xem docs/notes/level3-bai13).
// Bai 14: implement Executor (interface thuan, khong phai Executors/ThreadPoolExecutor bi
// cam) de dung duoc voi CompletableFuture.supplyAsync(..., pool) - mot trong 3 cach sua
// deadlock kieu thread starvation. Them workerThreads() de lab co the tu quan sat trang
// thai thread (Thread.getState()) giong nhu doc mot ban jstack dump.
public class NanoPool implements Executor {

    private static final int IDLE_POLL_MS = 50;

    // Ky thuat "ctl" cua ThreadPoolExecutor that: gop runState (3 bit cao) va workerCount
    // (29 bit thap) vao chung MOT AtomicInteger, de moi buoc chuyen trang thai la MOT CAS
    // duy nhat - khong the co khoang ho giua "doc runState" va "doc workerCount" rieng le.
    private static final int COUNT_BITS = Integer.SIZE - 3;
    private static final int COUNT_MASK = (1 << COUNT_BITS) - 1;

    private static final int RUNNING = -1 << COUNT_BITS;
    private static final int SHUTDOWN = 0 << COUNT_BITS;
    private static final int STOP = 1 << COUNT_BITS;
    private static final int TERMINATED = 2 << COUNT_BITS;

    private static int runStateOf(int c) {
        return c & ~COUNT_MASK;
    }

    private static int workerCountOf(int c) {
        return c & COUNT_MASK;
    }

    private static int ctlOf(int runState, int workerCount) {
        return runState | workerCount;
    }

    private final AtomicInteger ctl;
    private final BoundedTaskQueue<Runnable> queue;
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final AtomicLong workerSeq = new AtomicLong();

    private final int coreSize;
    private final int maxSize;
    private final long keepAliveMs;
    private final RejectionPolicy rejectionPolicy;

    private final ReentrantLock terminationLock = new ReentrantLock();
    private final Condition terminationCondition = terminationLock.newCondition();

    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final LongAdder submittedCount = new LongAdder();
    private final LongAdder completedCount = new LongAdder();
    private final LongAdder failedCount = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();

    public NanoPool(int coreSize, int maxSize, int queueCapacity, long keepAliveMs, RejectionPolicy rejectionPolicy) {
        if (coreSize < 0 || maxSize < 1 || maxSize < coreSize || keepAliveMs < 0) {
            throw new IllegalArgumentException("tham so pool khong hop le");
        }
        this.coreSize = coreSize;
        this.maxSize = maxSize;
        this.keepAliveMs = keepAliveMs;
        this.rejectionPolicy = rejectionPolicy;
        this.ctl = new AtomicInteger(ctlOf(RUNNING, coreSize));
        this.queue = new BoundedTaskQueue<>(queueCapacity);
        for (int i = 0; i < coreSize; i++) {
            Thread worker = new Thread(() -> workerLoop(null), "nanopool-core-" + workerSeq.incrementAndGet());
            workers.add(worker);
            worker.start();
        }
    }

    // Tuong thich nguoc voi bai 9-11: pool co dinh nThreads, luon block khi hang doi day
    // (giu nguyen hanh vi cu cua execute() truoc khi co rejection policy).
    public NanoPool(int nThreads, int queueCapacity) {
        this(nThreads, nThreads, queueCapacity, 0, RejectionPolicy.BLOCK_UNTIL_SPACE);
    }

    public void execute(Runnable task) {
        // "submitted" dem MOI lan goi execute(), bat ke ket qua sau do la chay duoc hay bi
        // reject - submitted = completed + failed + rejected + (dang cho/dang chay do dang).
        submittedCount.increment();

        if (isShutdown()) {
            rejectedCount.increment();
            throw new RejectedExecutionException("NanoPool da shutdown, khong nhan task moi");
        }

        if (isRunning() && offerToQueue(task)) {
            // Doi lai sau khi enqueue (bai 11): neu pool vua roi khoi RUNNING dung luc ta
            // dang offer(), phai vet task ra va reject tuong minh - khong de no nam im lim.
            if (isShutdown() && queue.remove(task)) {
                rejectedCount.increment();
                throw new RejectedExecutionException("NanoPool da shutdown ngay trong luc submit task nay");
            }
            return;
        }

        if (tryAddWorker(task, false)) {
            return;
        }

        applyRejectionPolicy(task);
    }

    public PoolStats stats() {
        return new PoolStats(
                activeWorkers.get(),
                queue.size(),
                submittedCount.sum(),
                completedCount.sum(),
                failedCount.sum(),
                rejectedCount.sum());
    }

    public <T> Future<T> submit(Callable<T> task) {
        MyFutureTask<T> futureTask = new MyFutureTask<>(task);
        execute(futureTask);
        return futureTask;
    }

    public void shutdown() {
        advanceRunState(SHUTDOWN);
        tryTerminate();
    }

    public List<Runnable> shutdownNow() {
        advanceRunState(STOP);
        for (Thread w : workers) {
            w.interrupt();
        }
        List<Runnable> remaining = drainQueue();
        tryTerminate();
        return remaining;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanosLeft = unit.toNanos(timeout);
        terminationLock.lock();
        try {
            while (runStateOf(ctl.get()) != TERMINATED) {
                if (nanosLeft <= 0) {
                    return false;
                }
                nanosLeft = terminationCondition.awaitNanos(nanosLeft);
            }
            return true;
        } finally {
            terminationLock.unlock();
        }
    }

    public boolean isShutdown() {
        return runStateOf(ctl.get()) != RUNNING;
    }

    public boolean isTerminated() {
        return runStateOf(ctl.get()) == TERMINATED;
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

    // So worker "chinh thuc" dang duoc ctl dem (co the vua thoat nhung Thread.isAlive()
    // chua kip cap nhat) - dung de kiem tra elastic sizing o bai 12.
    public int currentPoolSize() {
        return workerCountOf(ctl.get());
    }

    public int queueSize() {
        return queue.size();
    }

    // Snapshot chi de quan sat (lab bai 14) - tuong duong voi thong tin mot ban jstack dump
    // se cho thay: danh sach thread cua pool va (qua Thread.getState()) trang thai cua chung.
    public List<Thread> workerThreads() {
        return new ArrayList<>(workers);
    }

    private boolean isRunning() {
        return runStateOf(ctl.get()) == RUNNING;
    }

    private boolean offerToQueue(Runnable task) {
        try {
            return queue.offer(task, 0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void applyRejectionPolicy(Runnable task) {
        switch (rejectionPolicy) {
            case ABORT:
                rejectedCount.increment();
                throw new RejectedExecutionException("NanoPool bao hoa (hang doi day, da dat maxSize) - ABORT");
            case CALLER_RUNS:
                if (isShutdown()) {
                    rejectedCount.increment();
                    throw new RejectedExecutionException("NanoPool da shutdown - khong the CALLER_RUNS");
                }
                // Chay ngay tren chinh thread dang goi - day la backpressure. Khong tinh vao
                // activeWorkers vi day khong phai mot worker thread cua pool.
                runTaskAndRecordOutcome(task);
                return;
            case BLOCK_UNTIL_SPACE:
                try {
                    queue.put(task); // chan thread goi cho toi khi co cho - backpressure khac
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    rejectedCount.increment();
                    throw new RejectedExecutionException("bi interrupt khi cho BLOCK_UNTIL_SPACE", e);
                }
                if (isShutdown() && queue.remove(task)) {
                    rejectedCount.increment();
                    throw new RejectedExecutionException("NanoPool da shutdown ngay trong luc submit task nay");
                }
                return;
            default:
                throw new IllegalStateException("rejection policy khong duoc ho tro: " + rejectionPolicy);
        }
    }

    private void runTaskAndRecordOutcome(Runnable task) {
        try {
            task.run();
            completedCount.increment();
        } catch (Throwable t) {
            failedCount.increment();
        }
    }

    private boolean tryAddWorker(Runnable firstTask, boolean core) {
        while (true) {
            int c = ctl.get();
            if (runStateOf(c) != RUNNING) {
                return false;
            }
            int wc = workerCountOf(c);
            int limit = core ? coreSize : maxSize;
            if (wc >= limit) {
                return false;
            }
            if (ctl.compareAndSet(c, c + 1)) {
                break;
            }
        }
        Thread worker = new Thread(() -> workerLoop(firstTask),
                "nanopool-" + (core ? "core-" : "extra-") + workerSeq.incrementAndGet());
        workers.add(worker);
        worker.start();
        return true;
    }

    private void advanceRunState(int targetState) {
        while (true) {
            int c = ctl.get();
            if (runStateOf(c) >= targetState || ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c)))) {
                return;
            }
        }
    }

    private void decrementWorkerCount() {
        while (true) {
            int c = ctl.get();
            if (ctl.compareAndSet(c, c - 1)) {
                return;
            }
        }
    }

    private void tryTerminate() {
        while (true) {
            int c = ctl.get();
            int rs = runStateOf(c);
            if (rs == RUNNING || rs >= TERMINATED) {
                return;
            }
            if (rs == SHUTDOWN && queue.size() != 0) {
                return;
            }
            if (workerCountOf(c) != 0) {
                return;
            }
            if (ctl.compareAndSet(c, ctlOf(TERMINATED, 0))) {
                terminationLock.lock();
                try {
                    terminationCondition.signalAll();
                } finally {
                    terminationLock.unlock();
                }
                return;
            }
        }
    }

    private List<Runnable> drainQueue() {
        List<Runnable> drained = new ArrayList<>();
        try {
            Runnable task;
            while ((task = queue.poll(0, TimeUnit.MILLISECONDS)) != null) {
                drained.add(task);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return drained;
    }

    private void workerLoop(Runnable firstTask) {
        Runnable task = firstTask;
        try {
            while (true) {
                if (task == null) {
                    task = getTask();
                    if (task == null) {
                        return;
                    }
                }
                activeWorkers.incrementAndGet();
                try {
                    runTaskAndRecordOutcome(task); // nuot Throwable ben trong - bai 9: task loi khong duoc giet worker
                } finally {
                    activeWorkers.decrementAndGet();
                }
                task = null;
            }
        } finally {
            workers.remove(Thread.currentThread());
            tryTerminate();
        }
    }

    // getTask() null co dung mot y nghia kep: "khong con viec, thoat" (SHUTDOWN/STOP) hoac
    // "la mot extra thread da ranh qua keepAliveMs, tu ket thuc de tro ve dung coreSize".
    // Ca hai truong hop deu phai decrementWorkerCount() dung MOT LAN duy nhat ngay tai day -
    // khong lam lai o workerLoop, tranh dem trung.
    private Runnable getTask() {
        boolean timedOut = false;
        while (true) {
            int c = ctl.get();
            int rs = runStateOf(c);

            if (rs >= STOP || (rs == SHUTDOWN && queue.size() == 0)) {
                decrementWorkerCount();
                return null;
            }

            int wc = workerCountOf(c);
            boolean isExtra = wc > coreSize;

            if (isExtra && timedOut) {
                if (ctl.compareAndSet(c, c - 1)) {
                    return null;
                }
                continue; // co worker khac vua doi ctl - thu lai tu dau
            }

            try {
                long pollMs = isExtra ? keepAliveMs : IDLE_POLL_MS;
                Runnable task = queue.poll(pollMs, TimeUnit.MILLISECONDS);
                if (task != null) {
                    return task;
                }
                timedOut = isExtra;
            } catch (InterruptedException e) {
                timedOut = false;
            }
        }
    }
}
