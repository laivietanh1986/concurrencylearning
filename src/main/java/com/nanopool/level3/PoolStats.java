package com.nanopool.level3;

// Bai 13: mot lat cat (snapshot) bat bien cua trang thai NanoPool tai mot thoi diem.
// queueDepth va activeWorkers la "gauge" (gia tri tuc thoi), con lai la counter cong don.
public record PoolStats(int activeWorkers, int queueDepth, long submitted, long completed, long failed, long rejected) {
}
