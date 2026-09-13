package com.nanopool.level2;

// Bai 8, cach 3: mo phong mot cuoc goi blocking KHONG phan hoi Thread.interrupt()
// (giong Socket.getInputStream().read() thuc te trong Java). Cach duy nhat de
// huy mot thread dang ket trong read() la dong resource lai.
public class BlockingResource {

    private final Object lock = new Object();
    private boolean closed = false;
    private boolean dataAvailable = false;

    public String read() {
        synchronized (lock) {
            while (!closed && !dataAvailable) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    // Co tinh mo phong mot native blocking call khong quan tam
                    // toi interrupt status cua Java - tiep tuc cho.
                }
            }
            if (closed) {
                throw new ResourceClosedException("resource closed while blocked in read()");
            }
            dataAvailable = false;
            return "data";
        }
    }

    public void makeDataAvailable() {
        synchronized (lock) {
            dataAvailable = true;
            lock.notifyAll();
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }
}
