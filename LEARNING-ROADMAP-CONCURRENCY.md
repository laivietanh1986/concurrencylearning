# Lộ trình học Java Concurrency (dễ → khó)

Xây dựng dần **một project duy nhất**: `nanopool` — một concurrency engine tự viết từ đầu.
Chỉ cần Java 21 + Maven + JUnit 5, không cần Spring, không cần database.

Mỗi mục nên làm trên một branch riêng hoặc commit riêng để so sánh diff trước/sau.
Ràng buộc xuyên suốt Cấp 1–3: **không dùng** `Executors`, `ThreadPoolExecutor`, `ArrayBlockingQueue`, `LinkedBlockingQueue`. Được dùng `ReentrantLock`, `Condition`, `AtomicX`, `LongAdder`, `CountDownLatch`.

## Cấp 1 — Nền tảng (nhìn thấy lỗi bằng mắt)

1. **Lost update — counter đua nhau**
   - Viết `RaceLab`: 8 thread cùng `counter++` 100.000 lần. Chạy 4 biến thể: `int`, `volatile int`, `synchronized`, `AtomicInteger`.
   - Khái niệm: atomicity vs visibility là hai vấn đề khác nhau; vì sao `volatile` không cứu được `++` (read-modify-write không atomic).

2. **Vòng lặp không bao giờ dừng — visibility**
   - Một thread chạy `while (!stop) {}`, main thread set `stop = true` sau 1 giây. Quan sát thread không dừng, rồi thêm `volatile`.
   - Thử thêm `System.out.println` vào vòng lặp — bug biến mất. Tự giải thích tại sao.
   - Khái niệm: JIT hoisting, register caching, memory barrier ẩn trong `synchronized`.

3. **Publish object chưa khởi tạo xong**
   - Một thread tạo object nhiều field rồi gán vào biến shared; thread khác đọc và thấy field = 0/null.
   - Khái niệm: safe publication, `final` field freeze semantics, vì sao double-checked locking cần `volatile`.

4. **Vẽ happens-before cho 3 bài trên**
   - Với mỗi bài, viết ra edge nào tạo ra quan hệ happens-before và giữa hai action nào.
   - Khái niệm: happens-before, các edge có sẵn (program order, monitor lock, volatile write/read, `Thread.start()`, `Thread.join()`).

## Cấp 2 — Tự viết synchronizer

5. **`MyCountDownLatch`**
   - Viết hai lần: lần 1 bằng `synchronized` + `wait/notifyAll`, lần 2 bằng `ReentrantLock` + `Condition`. So sánh code.
   - Cố tình viết `if (count > 0) wait();` trước, tìm ra trường hợp sai, rồi đổi sang `while`.
   - Khái niệm: guarded wait, spurious wakeup, `notify()` vs `notifyAll()`.

6. **`MySemaphore` + interruption**
   - `acquire()`, `tryAcquire(timeout)`, `release()`. Test: N thread tranh permit, interrupt ngẫu nhiên một nửa, assert tổng permit không bị mất.
   - Bẫy cần tự gặp: thread bị interrupt ngay sau khi được `signal()` nhưng trước khi nhận permit → permit bị mất, thread khác treo vĩnh viễn.
   - Khái niệm: interrupt là flag chứ không phải lệnh kill; `InterruptedException` làm clear flag; quy tắc ném lại hoặc `Thread.currentThread().interrupt()`.

7. **`BoundedTaskQueue` — circular buffer**
   - Mảng cố định, head/tail index, `put`/`take`/`offer(timeout)`/`poll(timeout)`. Làm SPSC trước, rồi mở rộng MPMC.
   - Test xương sống: 4 producer + 3 consumer, 100.000 item, assert **không mất, không trùng**. Test này dùng lại xuyên suốt.
   - Khái niệm: hai `Condition` riêng (`notFull`/`notEmpty`); vì sao một `Condition` + `signalAll()` vẫn đúng nhưng tệ hơn (thundering herd).

8. **Cancellation — ba cách dừng một task**
   - Cùng một task chạy lâu, dừng bằng: cờ `volatile boolean`, `Thread.interrupt()`, và đóng resource.
   - Viết bản nuốt `InterruptedException` trước để thấy task không dừng được, rồi sửa.
   - Khái niệm: cancellation policy, blocking không interrupt được (socket read), khi nào phải restore interrupt flag.

## Cấp 3 — Tự viết thread pool

9. **`NanoPool` v1 — chạy được**
   - N thread cố định, mỗi worker loop `queue.take()` rồi `run()`. Chỉ có `execute(Runnable)`. Dùng lại queue ở mục 7.
   - Bỏ `try/catch` quanh `task.run()` trước: submit 1000 task trong đó 100 task ném exception, quan sát pool teo dần rồi treo. Rồi mới bọc lại.
   - Khái niệm: worker loop, vì sao task exception không được phép giết worker, `ThreadFactory` đặt tên thread, `UncaughtExceptionHandler`.

10. **`NanoPool` v2 — thêm `Future`**
    - `submit(Callable<T>)` trả `Future<T>`. Tự viết `MyFutureTask` với state machine `NEW → COMPLETING → NORMAL / EXCEPTIONAL / CANCELLED`.
    - `cancel(true)` phải interrupt đúng thread đang chạy task đó; `cancel(false)` chỉ ngăn task chưa chạy.
    - Khái niệm: `get()` block bằng `Condition`, exception bọc thành `ExecutionException`, CAS state trước khi interrupt để tránh race giữa `cancel()` và task vừa start.
    - Đọc kèm: source `java.util.concurrent.FutureTask` của JDK.

11. **`NanoPool` v3 — lifecycle**
    - `shutdown()`, `shutdownNow()`, `awaitTermination()`; state machine `RUNNING → SHUTDOWN → STOP → TERMINATED`.
    - Gộp runState + workerCount vào một `AtomicInteger` (kỹ thuật `ctl` của JDK) để transition atomic.
    - Viết `awaitTermination` bằng `while(!terminated) sleep(10)` trước, nhận ra đây là anti-pattern, rồi đổi sang `Condition.await()` + `signalAll` khi worker cuối thoát.
    - Khái niệm: `shutdown` drain queue vs `shutdownNow` interrupt và trả về task chưa chạy; submit sau shutdown phải reject deterministic, không âm thầm mất.

12. **`NanoPool` v4 — elastic sizing + rejection**
    - Thêm `coreSize`, `maxSize`, `queueCapacity`, `keepAliveMs`. Thread vượt core phải **chết thật** sau keep-alive (assert `poolSize` giảm).
    - Ba rejection policy pluggable: `ABORT`, `CALLER_RUNS`, `BLOCK_UNTIL_SPACE`. Assert `CALLER_RUNS` chạy trên thread gọi bằng cách so tên thread.
    - Khái niệm: thứ tự core → queue → extra thread của JDK và khi nào thứ tự đó là lựa chọn sai; vì sao `CALLER_RUNS` chính là một dạng backpressure.

13. **Metrics không trở thành nút nghẽn**
    - `PoolStats`: `activeWorkers`, `queueDepth`, `submitted/completed/failed/rejected`. Benchmark `AtomicLong` vs `LongAdder` ở 1/4/16/32 thread.
    - Thí nghiệm phụ: `long[16]` mỗi thread ghi một index → chậm; padding thành `long[16*8]` ghi index `i*8` → nhanh hơn rõ rệt.
    - Khái niệm: cache line 64 byte, false sharing, striping của `LongAdder`.

14. **Deadlock lab — thread starvation deadlock**
    - Pool 2 thread. Task A bên trong lại `submit(B)` rồi gọi `B.get()`. Quan sát treo.
    - Dump bằng `jstack`, đọc trạng thái `WAITING (parking)`, truy ra nguyên nhân từ dump.
    - Implement 3 cách sửa: pool riêng cho task con, `CompletableFuture` không block, giới hạn độ sâu đệ quy.
    - Khái niệm: thread starvation deadlock, lock ordering, phân biệt `BLOCKED` vs `WAITING` vs `TIMED_WAITING` trong thread dump.

## Cấp 4 — Từ pool lên hệ thống

15. **Pipeline nhiều tầng + backpressure thật**
    - 3 stage (parse → transform → sink), mỗi stage một `NanoPool` + queue riêng. Cố tình làm stage cuối chậm.
    - Shutdown có thứ tự bằng poison pill; thử shutdown bằng interrupt trước để thấy item đang xử lý dở bị mất.
    - Đo throughput end-to-end và p50/p99 mỗi stage với 1.000.000 item.
    - Khái niệm: áp lực lan ngược về source, vì sao unbounded buffer ở bất kỳ tầng nào cũng là lỗi, Little's Law (`L = λ × W`) để chọn queue depth.

16. **Timeout & retry không chiếm worker**
    - Timeout cho mỗi task (`TIMED_OUT` state). Retry với exponential backoff nhưng **không** `sleep` trên worker thread.
    - Làm hai bản: bản 1 dùng priority queue theo deadline, bản 2 dùng hashed timing wheel.
    - Khái niệm: `DelayQueue`, `O(log n)` vs `O(1)`, vì sao Netty và Kafka chọn timing wheel.

17. **Rate limiting + bulkhead**
    - Token bucket giới hạn tốc độ submit. Sau đó chia pool thành các ngăn theo loại task.
    - Làm bản **không có** bulkhead trước: cho một loại task chậm chiếm hết worker, quan sát cả hệ thống chết theo.
    - Khái niệm: isolation, failure containment, so sánh với Resilience4j/Hystrix.

18. **Work-stealing pool**
    - Mỗi worker một deque riêng: owner push/pop một đầu (LIFO), thief steal đầu kia (FIFO).
    - Tìm cả workload mà work-stealing **thua** shared queue (task đồng đều, không tạo task con).
    - Khái niệm: cache locality vì sao owner dùng LIFO, thief dùng FIFO; nguyên lý của `ForkJoinPool`.

## Cấp 5 — Đo lường & so sánh với JDK

19. **Benchmark harness đàng hoàng**
    - Dùng JMH đo lại mục 12 và 18; HdrHistogram cho latency. Hai workload: CPU-bound (hashing) và blocking I/O (sleep).
    - Bắt buộc tìm ra một điểm dữ liệu mà **thêm thread làm chậm đi**, rồi giải thích.
    - Khái niệm: warmup/JIT, dead code elimination (`Blackhole`), coordinated omission, oversubscription (context switch cost, cache thrashing).

20. **Pool sizing — suy ra công thức**
    - Từ số đo ở mục 19, tự suy ra `N = N_cpu × U_cpu × (1 + W/C)` chứ không thuộc lòng. Đối chiếu số thực đo với số công thức dự đoán.
    - Mô phỏng: 64 worker thread nhưng connection pool chỉ 10 → quan sát thread xếp hàng ở `getConnection()`.
    - Khái niệm: Amdahl's Law, tương tác giữa thread pool và connection pool (HikariCP), biểu hiện trong metrics.

21. **Ring buffer kiểu Disruptor**
    - Ring buffer với sequence, pre-allocate slot để tránh GC, cache line padding. So throughput với `BoundedTaskQueue` ở mục 7.
    - Khái niệm: mechanical sympathy, sequence barrier, vì sao lock-free thắng lock ở throughput rất cao.

22. **Virtual threads — cùng interface, hai cách chạy**
    - Thêm một implementation virtual-thread-backed sau cùng interface với `NanoPool`. Benchmark lại cả hai workload ở mục 19.
    - Khái niệm: carrier thread, mounting/unmounting, pinning (`synchronized` và native frame), vì sao pool virtual thread là anti-pattern, structured concurrency.

23. **Đọc lại source JDK và so với code của mình**
    - Đọc `ThreadPoolExecutor`, `ArrayBlockingQueue`, `FutureTask`, `AbstractQueuedSynchronizer`. Liệt kê những chỗ JDK làm khác mình và lý do.
    - Khái niệm: AQS như một framework synchronizer chung; `Worker extends AQS` và vì sao nó cố tình không reentrant.

---

### Cách dùng file này
- Làm tuần tự, mỗi mục một commit để so diff trước/sau.
- Với mỗi mục có ghi "viết bản sai trước": làm đúng thứ tự đó. Tự tay tái tạo bug thì mới nhớ được cơ chế.
- Mọi khẳng định về hiệu năng phải có số đo, không đoán.
- Sau mỗi cấp, tự trả lời không nhìn tài liệu:
  - **Cấp 1:** Vì sao `volatile` sửa được mục 2 nhưng không sửa được mục 1?
  - **Cấp 2:** Vì sao guarded wait bắt buộc dùng `while`? Interrupt flag bị clear khi nào?
  - **Cấp 3:** Vẽ luồng `submit()` từ lúc gọi đến lúc task chạy, đi đủ core → queue → max → reject. `shutdown()` khác `shutdownNow()` ở đúng những điểm nào?
  - **Cấp 4:** Backpressure lan ngược bằng cơ chế nào? Khi nào work-stealing thua shared queue?
  - **Cấp 5:** Virtual thread giải quyết vấn đề gì và **không** giải quyết vấn đề gì?
- Trả lời không được thì quay lại mục đó, đừng đi tiếp.

### Nếu chỉ có ~20 giờ
Làm đúng 8 mục: **2 → 7 → 8 → 9 → 10 → 11 → 12 → 14**.
Đây là đường ngắn nhất tới một thread pool hoàn chỉnh và hiểu được deadlock. Bỏ Cấp 4–5 nếu hết thời gian.
