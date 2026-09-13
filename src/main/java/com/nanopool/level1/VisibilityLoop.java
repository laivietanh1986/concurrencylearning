package com.nanopool.level1;

// Bai 2: Vong lap khong bao gio dung - visibility.
public class VisibilityLoop {

    private boolean stopPlain = false;
    private volatile boolean stopVolatile = false;

    public void runPlainLoop() {
        long iterations = 0;
        while (!stopPlain) {
            iterations++;
        }
        System.out.println("plain loop stopped after " + iterations + " iterations");
    }

    public void runPlainLoopWithPrintln() {
        long iterations = 0;
        while (!stopPlain) {
            iterations++;
            if (iterations % 200_000_000L == 0) {
                System.out.println("still looping... " + iterations);
            }
        }
        System.out.println("plain+println loop stopped after " + iterations + " iterations");
    }

    public void runVolatileLoop() {
        long iterations = 0;
        while (!stopVolatile) {
            iterations++;
        }
        System.out.println("volatile loop stopped after " + iterations + " iterations");
    }

    public void requestStopPlain() {
        stopPlain = true;
    }

    public void requestStopVolatile() {
        stopVolatile = true;
    }

    public static void main(String[] args) throws InterruptedException {
        String mode = args.length > 0 ? args[0] : "plain";
        VisibilityLoop lab = new VisibilityLoop();
        Thread worker;
        Runnable stopper;

        switch (mode) {
            case "plain" -> {
                worker = new Thread(lab::runPlainLoop, "worker");
                stopper = lab::requestStopPlain;
            }
            case "println" -> {
                worker = new Thread(lab::runPlainLoopWithPrintln, "worker");
                stopper = lab::requestStopPlain;
            }
            case "volatile" -> {
                worker = new Thread(lab::runVolatileLoop, "worker");
                stopper = lab::requestStopVolatile;
            }
            default -> throw new IllegalArgumentException("unknown mode: " + mode + " (use plain|println|volatile)");
        }

        worker.setDaemon(true);
        worker.start();
        Thread.sleep(1000);
        System.out.println("main: setting stop flag (mode=" + mode + ")");
        stopper.run();
        worker.join(5000);

        if (worker.isAlive()) {
            System.out.println("main: worker still ALIVE after 5s -> bug reproduced (mode=" + mode + ")");
        } else {
            System.out.println("main: worker stopped normally (mode=" + mode + ")");
        }
    }
}
