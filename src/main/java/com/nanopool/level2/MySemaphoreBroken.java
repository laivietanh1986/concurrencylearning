package com.nanopool.level2;

// Bai 6 (co tinh sai): dung synchronized + wait/notify() (khong phai notifyAll()).
// notify() chi danh thuc DUY NHAT mot thread dang wait. Neu chinh thread do
// bi interrupt dung luc dang duoc trao quyen (ngay sau notify(), truoc khi
// kip chay permits--), theo JLS hanh vi "interrupt vs notify" cho Object.wait()
// la KHONG duoc dac ta ro rang - wakeup do co the bi mat vinh vien va khong
// co ai goi notify() lai cho cac thread con lai dang cho.
public class MySemaphoreBroken {

    private int permits;

    public MySemaphoreBroken(int permits) {
        if (permits < 0) {
            throw new IllegalArgumentException("permits < 0");
        }
        this.permits = permits;
    }

    public synchronized void acquire() throws InterruptedException {
        while (permits == 0) {
            wait();
        }
        permits--;
    }

    public synchronized void release() {
        permits++;
        notify();
    }

    public synchronized int availablePermits() {
        return permits;
    }
}
