package com.nanopool.level3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Bai 14 - Deadlock lab: mot pool 2 thread, moi "outer" task lai submit mot "inner" task
// VAO CHINH PHOOL DO roi block cho ket qua bang get(). Neu ca 2 thread cua pool deu dang
// chay outer task va dang cho inner task, khong con thread nao ranh de chay inner task ca -
// treo vinh vien. Day KHONG phai deadlock kieu lock-ordering co dien (khong ai giu lock cua
// ai) - day la "thread starvation deadlock": tai nguyen (thread) bi can kiet, khong phai bi
// khoa cheo.
class DeadlockLabTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void twoOuterTasksBlockingOnInnerTasksOnTheSameTwoThreadPoolHangForever() throws Exception {
        NanoPool pool = new NanoPool(2, 2, 10, 0, RejectionPolicy.ABORT);

        int outerCount = 2;
        CountDownLatch outerStarted = new CountDownLatch(outerCount);
        List<Future<Integer>> outerFutures = new ArrayList<>();

        for (int i = 0; i < outerCount; i++) {
            Future<Integer> outerFuture = pool.submit(() -> {
                outerStarted.countDown();
                // Day chinh la cai bay: submit MOT task nua vao CUNG pool nay roi block cho no.
                Future<Integer> innerFuture = pool.submit(() -> 42);
                return innerFuture.get();
            });
            outerFutures.add(outerFuture);
        }

        assertTrue(outerStarted.await(2, TimeUnit.SECONDS),
                "ca 2 outer task phai bat dau chay va chiem het 2 thread duy nhat cua pool");

        // Cho mot khoang du de he thong on dinh that su vao trang thai treo (khong phai
        // chi la "chua kip chay xong" thong thuong).
        Thread.sleep(500);

        for (Future<Integer> f : outerFutures) {
            assertFalse(f.isDone(),
                    "outer task khong the nao hoan tat - ca 2 thread deu dang cho mot inner task ma khong ai ranh de chay no");
        }

        // Doc trang thai thread that su - day chinh xac la thu ma mot ban jstack dump the hien.
        List<Thread> workers = pool.workerThreads();
        assertFalse(workers.isEmpty());
        for (Thread w : workers) {
            Thread.State state = w.getState();
            assertTrue(state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING,
                    "worker '" + w.getName() + "' dang ket phai o trang thai WAITING/TIMED_WAITING (dang Condition.await()"
                            + " cho ket qua), KHONG phai BLOCKED (cho mot monitor lock) - do thuc te la: " + state);
        }

        // Chup mot ban jstack dump THAT (khong mo phong) de doc thu, dung dung cong cu ma
        // roadmap yeu cau - luu lai lam bang chung trong docs/notes.
        String dump = captureRealJstackDump();
        Path outFile = Paths.get("docs", "notes", "level3-bai14-jstack-dump.txt");
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, dump, StandardCharsets.UTF_8);
        assertTrue(dump.contains("WAITING") || dump.contains("parking"),
                "jstack dump phai the hien cac thread cua pool dang WAITING/parking");

        // Don dep: shutdownNow() interrupt ca 2 worker, dieu nay se lam innerFuture.get()
        // (va lan luot ca outerFuture.get() ben ngoai) nem InterruptedException/CancellationException
        // thay vi treo mai mai - khong de lai thread mo cho cac test sau.
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    private String captureRealJstackDump() throws IOException, InterruptedException {
        long pid = ProcessHandle.current().pid();
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        File jstackBin = new File(javaHome, "bin" + File.separator + (windows ? "jstack.exe" : "jstack"));

        ProcessBuilder pb = new ProcessBuilder(jstackBin.getAbsolutePath(), String.valueOf(pid));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(10, TimeUnit.SECONDS);
        return output;
    }
}
