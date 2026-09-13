# Bài 14 — Deadlock lab: thread starvation deadlock

## Kịch bản gốc: task A submit task B rồi block chờ B

```java
NanoPool pool = new NanoPool(2, 2, 10, 0, RejectionPolicy.ABORT); // dung 2 thread

Future<Integer> outer = pool.submit(() -> {
    Future<Integer> inner = pool.submit(() -> 42); // submit vao CHINH pool nay
    return inner.get();                            // block cho toi khi inner xong
});
```

Nếu có **2** outer task như trên chạy đồng thời trên một pool **2** thread: cả 2 thread duy nhất của pool đều đang bận chạy outer task, và cả 2 đều đang `get()` chờ một inner task — nhưng **không còn thread nào rảnh** để lấy inner task ra khỏi hàng đợi và chạy nó. Không ai giải phóng, không ai được giải phóng — treo vĩnh viễn. Đo được (`DeadlockLabTest`): sau 500ms, cả 2 `Future` của outer task đều `isDone() == false`, và tình trạng này không bao giờ tự khỏi (đã thử chờ, không đổi).

## Đây KHÔNG phải deadlock kiểu "khoá chéo" cổ điển

Deadlock kinh điển (lock ordering): thread T1 giữ lock A và chờ lock B; thread T2 giữ lock B và chờ lock A — một chu trình chờ-giữ tài nguyên (lock). `jstack` có bộ dò deadlock tự động, chuyên tìm đúng loại chu trình này (monitor lock hoặc `ReentrantLock`/`AbstractOwnableSynchronizer`).

Tình huống ở đây khác hẳn: **không ai giữ bất kỳ lock nào mà người khác cần**. Cả 2 worker thread chỉ đơn giản là đang `Condition.await()` chờ một kết quả — không có chu trình khoá-chờ nào cả về mặt lock. Vấn đề là **tài nguyên thread bị cạn kiệt** (thread starvation): công việc cần một thread rảnh để hoàn tất, nhưng mọi thread hiện có đều đang bận chờ chính công việc đó.

**Bằng chứng thực nghiệm** (chạy `jstack <pid>` thật, không mô phỏng — xem `docs/notes/level3-bai14-jstack-dump.txt` được test tự động ghi lại mỗi lần chạy):

```
"nanopool-core-1" ... waiting on condition  [...]
   java.lang.Thread.State: WAITING (parking)
        at jdk.internal.misc.Unsafe.park(...)
        - parking to wait for  <0x...> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
        at java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await(...)
        at com.nanopool.level3.MyFutureTask.awaitDone(MyFutureTask.java:136)
        at com.nanopool.level3.MyFutureTask.get(MyFutureTask.java:114)
        at com.nanopool.level3.DeadlockLabTest.lambda$...(DeadlockLabTest.java:43)
        ...
        at com.nanopool.level3.NanoPool.workerLoop(NanoPool.java:357)
```

Cả 2 worker (`nanopool-core-1`, `nanopool-core-2`) đều ở đúng trạng thái này. Và quan trọng nhất: **tìm chữ "deadlock" trong toàn bộ dump — không có dòng "Found one Java-level deadlock" nào cả**. Đây chính là bài học cốt lõi: **`jstack` không tự phát hiện được thread-starvation deadlock**, vì bộ dò của nó chỉ tìm chu trình lock, không tìm được kiểu "cạn tài nguyên" này. Người đọc dump phải **tự suy luận** từ việc thấy nhiều thread cùng ở trạng thái `WAITING` tại cùng một điểm (`MyFutureTask.awaitDone`), rồi tự hỏi "còn ai rảnh để hoàn thành việc mà các thread này đang chờ không?" — nếu câu trả lời là "không ai cả, vì tất cả thread đã liệt kê ở trên đều đang chờ", đó chính là dấu hiệu của thread starvation deadlock.

## Phân biệt `WAITING` / `TIMED_WAITING` / `BLOCKED` trong thread dump

- **`BLOCKED`** — thread đang chờ **giành một monitor lock** (`synchronized`) mà thread khác đang giữ. Đây là trạng thái xuất hiện trong deadlock kiểu khoá chéo cổ điển, và là thứ `jstack` chủ động dò tìm chu trình.
- **`WAITING`** — thread đang chờ **vô thời hạn** một tín hiệu từ thread khác: `Object.wait()` không timeout, `Condition.await()` không timeout, `Thread.join()` không timeout, `LockSupport.park()`. Không giữ hay chờ lock nào cả — chỉ đơn thuần "ngủ" cho tới khi được đánh thức.
- **`TIMED_WAITING`** — giống `WAITING` nhưng có giới hạn thời gian (`sleep(ms)`, `wait(ms)`, `await(timeout)`, `join(ms)`).

Trong lab này, cả hai worker thread đều ở `WAITING` (gọi `Condition.await()` không timeout bên trong `MyFutureTask.awaitDone()`) — **không phải `BLOCKED`**, đúng như dự đoán, vì `get()` chưa từng đụng tới một `synchronized` nào cả, nó dùng `ReentrantLock`/`Condition` (bài 10). Đây chính là lý do một người chỉ quen tìm `BLOCKED` trong dump để phát hiện deadlock sẽ **bỏ sót hoàn toàn** loại lỗi này.

## Ba cách sửa

### Cách 1 — pool riêng cho task con

```java
NanoPool outerPool = new NanoPool(2, 2, ...);
NanoPool innerPool = new NanoPool(2, 2, ...); // hoan toan doc lap

Future<Integer> outer = outerPool.submit(() -> {
    Future<Integer> inner = innerPool.submit(() -> 42); // KHAC pool
    return inner.get();
});
```

Đo được (`SeparateChildPoolFixTest`): cả 2 outer task hoàn tất đúng kết quả trong vài giây. Vì `innerPool` có tập thread hoàn toàn tách biệt với `outerPool`, việc `outerPool`'s 2 thread cùng bận không ảnh hưởng gì tới khả năng `innerPool` có thread rảnh xử lý task con. **Đánh đổi**: tốn thêm thread nhàn rỗi khi không có việc, và phải tự tay quản lý vòng đời của nhiều pool.

### Cách 2 — không block, dùng `CompletableFuture`

```java
CompletableFuture<Integer> chain = CompletableFuture
    .supplyAsync(() -> outerWork(), pool)
    .thenComposeAsync(x -> CompletableFuture.supplyAsync(() -> innerWork(x), pool), pool);
```

`NanoPool` giờ implements `java.util.concurrent.Executor` (chỉ là một interface một-phương-thức, không phải lớp `Executors`/`ThreadPoolExecutor` bị cấm) nên dùng thẳng được với `CompletableFuture`. Đo được (`CompletableFutureFixTest`): 4 chuỗi outer→inner (nhiều hơn số thread của pool) đều hoàn tất đúng kết quả trên **cùng một pool 2 thread** — không cần pool thứ hai. Bí quyết: không giai đoạn nào gọi `get()` chặn bên trong một task của pool — mỗi giai đoạn là một task độc lập, chạy xong là **trả thread lại cho pool ngay**; giai đoạn kế tiếp chỉ được nộp vào hàng đợi (qua callback), không ai phải "giữ chỗ" một thread trong lúc chờ.

### Cách 3 — giới hạn độ sâu đệ quy

Áp dụng cho phiên bản tổng quát hơn: một phép chia-để-trị đệ quy (như tính tổng một dải số bằng chia đôi), mỗi tầng lại `submit()` nửa trái vào **chính pool đó** rồi `get()`. Đây chính là "bài toán A-submit-B" lặp lại nhiều tầng — và với pool cố định N thread, ngay khi độ sâu đệ quy vượt quá N, tất cả N thread có thể cùng lúc đang `get()` chờ nhau.

```java
if (depth >= maxPoolDepth) {
    // qua gioi han - tinh THANG tren chinh thread hien tai, KHONG submit vao pool nua
    return sumRangeWithDepthLimit(pool, lo, mid, depth + 1, maxPoolDepth)
         + sumRangeWithDepthLimit(pool, mid + 1, hi, depth + 1, maxPoolDepth);
}
Future<Long> leftFuture = pool.submit(() -> sumRangeWithDepthLimit(pool, lo, mid, depth + 1, maxPoolDepth));
long right = sumRangeWithDepthLimit(pool, mid + 1, hi, depth + 1, maxPoolDepth);
return leftFuture.get() + right;
```

Đo được (`RecursionDepthLimitFixTest`): với `maxPoolDepth = 2` (bằng đúng số thread của pool), tổng `0..15` (đòi hỏi tới 4 tầng đệ quy nếu không giới hạn) tính đúng ra `120` mà không treo — vì từ tầng thứ 3 trở đi, mọi thứ được tính thẳng trên thread hiện tại, không bao giờ tạo thêm một chuỗi chờ mới trên pool. Ngược lại, phiên bản không giới hạn (`sumRangeNoLimit`) treo gần như ngay lập tức — đúng như dự đoán, vì `[0,15]` cần nhiều hơn 2 tầng đệ quy, và mỗi tầng đều vô điều kiện `submit()` rồi `get()`.

Đây chính xác là lý do `ForkJoinPool` (dùng cho `RecursiveTask`) tồn tại: nó dùng **work-stealing**, cho phép một thread đang rảnh "mượn" việc từ hàng đợi riêng của thread khác, nên không bao giờ rơi vào tình trạng "N thread, N+1 công việc phải hoàn tất đồng thời để giải phóng nhau". Một `NanoPool`/`ThreadPoolExecutor` thường không có cơ chế đó, nên tự đệ quy-nộp-vào-chính-nó là một anti-pattern trừ khi có giới hạn độ sâu tường minh như trên.

## "Lock ordering" — vì sao được nhắc tới nhưng không xuất hiện ở đây

`lock ordering` là nguyên nhân của deadlock kiểu cổ điển (hai lock bị hai thread giữ chéo nhau) — cách phòng tránh kinh điển là **luôn giành lock theo một thứ tự cố định, thống nhất trên toàn hệ thống**. Bài này cố tình đối chiếu với thread-starvation deadlock để làm rõ: **không phải mọi thứ "trông giống deadlock" đều do lock ordering sai** — đôi khi vấn đề nằm ở việc mô hình hoá sự phụ thuộc giữa các đơn vị công việc (task) trên một tài nguyên có giới hạn (thread pool), một lớp lỗi hoàn toàn khác, cần cách chẩn đoán và cách sửa khác hẳn.

## Câu hỏi tự kiểm tra
- Nếu pool có `coreSize=2, maxSize=8` (thay vì cố định 2) thay vì cố định 2 thread, kịch bản gốc (2 outer task) còn treo không? Tại sao "cách sửa 1" (pool riêng) và việc đơn giản là tăng `maxSize` lại là hai giải pháp khác nhau về bản chất, dù cả hai đều "thêm thread"?
- `CompletableFutureFixTest` dùng `thenComposeAsync(..., pool)` thay vì `thenCompose(...)` (không truyền `pool`). Nếu bỏ tham số `pool` ở lệnh gọi đó, giai đoạn `inner` sẽ chạy trên thread nào, và điều đó có còn tránh được starvation không?
