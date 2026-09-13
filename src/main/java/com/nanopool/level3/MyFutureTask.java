package com.nanopool.level3;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

// Bai 10: tu viet mot phien ban toi gian cua java.util.concurrent.FutureTask,
// dung de hoc state machine va cai bay giua cancel(true) va task vua bat dau chay.
public class MyFutureTask<T> implements RunnableFuture<T> {

    private static final int NEW = 0;
    private static final int COMPLETING = 1;
    private static final int NORMAL = 2;
    private static final int EXCEPTIONAL = 3;
    private static final int CANCELLED = 4;
    private static final int INTERRUPTING = 5;
    private static final int INTERRUPTED = 6;

    // AtomicInteger vua cho CAS, vua la volatile read/write - day la "hang rao"
    // giup outcome (mot field thuong) duoc cong bo an toan sang thread khac (bai 3).
    private final AtomicInteger state = new AtomicInteger(NEW);
    private final Callable<T> callable;
    private Object outcome;
    private volatile Thread runner;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition done = lock.newCondition();

    public MyFutureTask(Callable<T> callable) {
        this.callable = callable;
    }

    @Override
    public void run() {
        if (state.get() != NEW) {
            return;
        }
        runner = Thread.currentThread();
        try {
            if (state.get() == NEW) {
                T result = null;
                Throwable failure = null;
                try {
                    result = callable.call();
                } catch (Throwable ex) {
                    failure = ex;
                }
                if (state.get() == NEW) {
                    if (failure == null) {
                        setResult(result);
                    } else {
                        setException(failure);
                    }
                }
                // Neu state != NEW o day: cancel() da thang truoc khi task xong -
                // ket qua/loi bi bo qua co tinh, khong duoc ghi de len CANCELLED.
            }
        } finally {
            runner = null;
            // Neu cancel(true) dang/da goi interrupt() len thread nay, phai doi no
            // CAS xong INTERRUPTING -> INTERRUPTED roi moi tra quyen dieu khien ve
            // worker loop - neu khong, interrupt() co the "roi" sang task ke tiep
            // ma chinh thread nay se chay ngay sau do.
            while (state.get() == INTERRUPTING) {
                Thread.yield();
            }
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        int target = mayInterruptIfRunning ? INTERRUPTING : CANCELLED;
        // CAS nay la ranh gioi quyet dinh: chi thanh cong neu task chua chay xong
        // (van con NEW). Neu run() da CAS sang COMPLETING truoc do, cancel that bai.
        if (!state.compareAndSet(NEW, target)) {
            return false;
        }
        try {
            if (mayInterruptIfRunning) {
                Thread t = runner;
                if (t != null) {
                    t.interrupt();
                }
            }
        } finally {
            if (mayInterruptIfRunning) {
                state.set(INTERRUPTED);
            }
        }
        finishCompletion();
        return true;
    }

    @Override
    public boolean isCancelled() {
        return state.get() >= CANCELLED;
    }

    @Override
    public boolean isDone() {
        return state.get() != NEW;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        int s = state.get();
        if (s <= COMPLETING) {
            s = awaitDone(-1, null);
        }
        return report(s);
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        int s = state.get();
        if (s <= COMPLETING) {
            s = awaitDone(timeout, unit);
            if (s <= COMPLETING) {
                throw new TimeoutException();
            }
        }
        return report(s);
    }

    private int awaitDone(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            if (unit == null) {
                while (state.get() <= COMPLETING) {
                    done.await();
                }
            } else {
                long nanosLeft = unit.toNanos(timeout);
                while (state.get() <= COMPLETING) {
                    if (nanosLeft <= 0) {
                        break;
                    }
                    nanosLeft = done.awaitNanos(nanosLeft);
                }
            }
            return state.get();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private T report(int s) throws ExecutionException {
        Object x = outcome;
        if (s == NORMAL) {
            return (T) x;
        }
        if (s >= CANCELLED) {
            throw new CancellationException();
        }
        throw new ExecutionException((Throwable) x);
    }

    private void setResult(T result) {
        if (state.compareAndSet(NEW, COMPLETING)) {
            outcome = result;
            state.set(NORMAL);
            finishCompletion();
        }
    }

    private void setException(Throwable failure) {
        if (state.compareAndSet(NEW, COMPLETING)) {
            outcome = failure;
            state.set(EXCEPTIONAL);
            finishCompletion();
        }
    }

    private void finishCompletion() {
        lock.lock();
        try {
            done.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
