package com.nanopool.level3;

// Bai 12: ba chinh sach xu ly khi pool bao hoa (hang doi day VA da dat maxSize).
public enum RejectionPolicy {
    // Nem RejectedExecutionException ngay lap tuc - khong chay, khong cho.
    ABORT,
    // Chay task ngay tren chinh thread dang goi execute()/submit() - mot dang backpressure:
    // thread dang "san xuat" task se tu bi cham lai vi phai tu tay chay task do.
    CALLER_RUNS,
    // Chan (block) thread goi cho toi khi hang doi co cho trong - mot dang backpressure khac,
    // it "xam lan" hon CALLER_RUNS (khong chay task tren thread la, chi cho).
    BLOCK_UNTIL_SPACE
}
