# Bài 10 — NanoPool v2: `Future` và cuộc đua giữa `cancel()` và task vừa bắt đầu chạy

## Ý tưởng cốt lõi

`submit(Callable<T>)` cần trả về một "cái vé" (`Future<T>`) cho phép người gọi:
- `get()` — chờ và lấy kết quả (block cho tới khi xong).
- `cancel(boolean)` — huỷ task, có hai chế độ khác nhau.
- `isDone()` / `isCancelled()` — hỏi trạng thái mà không chờ.

Mẹo quan trọng nhất của bài này: **`MyFutureTask` chính nó chỉ là một `Runnable`** — nó có `run()` bọc quanh `callable.call()`. Vì vậy `NanoPool` không cần sửa gì cả: `submit()` chỉ tạo một `MyFutureTask`, rồi gọi `execute(futureTask)` y hệt như submit một `Runnable` bình thường ở bài 9 — tái sử dụng nguyên `BoundedTaskQueue` và worker loop.

```java
public <T> Future<T> submit(Callable<T> task) {
    MyFutureTask<T> futureTask = new MyFutureTask<>(task);
    execute(futureTask);
    return futureTask;
}
```

## State machine

```
NEW ──cancel(false)──────────────────────────► CANCELLED
NEW ──cancel(true)───► INTERRUPTING ──(sau khi interrupt() xong)──► INTERRUPTED
NEW ──run() bắt đầu set kết quả──► COMPLETING ──► NORMAL (thành công)
NEW ──run() bắt đầu set lỗi──────► COMPLETING ──► EXCEPTIONAL (task ném exception)
```

`COMPLETING` là một trạng thái trung gian **cực kỳ mỏng** (chỉ tồn tại giữa lúc CAS state và lúc ghi xong `outcome`) — nó tồn tại để đóng một cửa sổ race: nếu không có nó, `cancel()` có thể nhìn thấy state vẫn là `NEW` và "thắng" ngay đúng lúc `run()` đã lấy được kết quả nhưng chưa kịp ghi vào field `outcome`.

## Cuộc đua thật sự: `cancel(true)` vs. task "vừa mới bắt đầu chạy"

Đây là phần khó nhất, và cũng là lý do vì sao không chỉ có 3 trạng thái "vui vẻ" `NEW → COMPLETING → NORMAL/EXCEPTIONAL/CANCELLED` mà cần thêm `INTERRUPTING`/`INTERRUPTED`.

Câu hỏi: `cancel(true)` phải interrupt **đúng thread đang chạy task đó**. Nhưng "đang chạy" là một trạng thái thay đổi theo thời gian thực — tại thời điểm `cancel(true)` được gọi, worker thread có thể đang ở bất kỳ đâu trong số các thời điểm sau:
1. Chưa bắt đầu (`runner == null`) → không có gì để interrupt, chỉ cần CAS state để task không bao giờ chạy.
2. Đã set `runner` nhưng chưa gọi `callable.call()`.
3. Đang chạy dở `callable.call()`.
4. Đã chạy xong, đang ghi `outcome` (COMPLETING).
5. Đã ghi xong (`NORMAL`/`EXCEPTIONAL`).

Nếu `cancel()` chỉ đơn giản làm "đọc `runner`, gọi `interrupt()`", có hai lỗi tiềm ẩn:
- **Lỗi 1 — interrupt "rơi" sang task tiếp theo**: nếu `run()` đã chạy xong task hiện tại và worker thread đã quay lại `queue.take()` để lấy task **kế tiếp**, một lệnh `interrupt()` đến trễ sẽ vô tình đánh dấu cờ interrupt lên thread đó ngay khi nó đang xử lý một task hoàn toàn khác — một con bug im lặng, cực kỳ khó tái hiện.
- **Lỗi 2 — ghi đè kết quả hợp lệ**: nếu task đã hoàn thành và trả kết quả đúng, `cancel()` gọi sau đó không được phép biến kết quả đó thành "đã huỷ".

**Giải pháp** (mô phỏng đúng kỹ thuật JDK dùng cho `FutureTask` thật):
1. `cancel(true)` **CAS `state` từ `NEW` sang `INTERRUPTING` trước khi gọi `interrupt()`**. Nếu CAS thất bại (state đã là `COMPLETING` trở lên), `cancel()` trả về `false` ngay — không đụng gì tới `runner` hay `outcome` nữa. Đây chính là câu roadmap nhắc: *"CAS state trước khi interrupt để tránh race giữa cancel() và task vừa start."*
2. Nếu CAS thắng, `runner` (một field `volatile`, được `run()` gán ngay khi bắt đầu) chắc chắn phản ánh đúng thread nào — nếu có — đang thực sự chạy `callable.call()`, hoặc `null` nếu chưa kịp bắt đầu.
3. `run()`, ngay sau khi `callable.call()` trả về (dù thành công hay ném lỗi), kiểm tra lại `state == NEW` trước khi ghi `outcome` — nếu `cancel()` đã thắng cuộc đua CAS trong lúc `callable.call()` đang chạy, `run()` **bỏ qua hoàn toàn** kết quả/lỗi vừa tính được, không ghi đè lên `CANCELLED`/`INTERRUPTING`.
4. Quan trọng nhất để chặn Lỗi 1: trong `finally` của `run()`, sau khi set `runner = null`, nếu `state == INTERRUPTING`, thread phải **spin-wait** (`Thread.yield()` lặp) cho tới khi `cancel()` hoàn tất việc gọi `interrupt()` và CAS state sang `INTERRUPTED`. Nhờ vậy, `run()` (và do đó worker loop) không bao giờ quay lại `queue.take()` để nhận task tiếp theo trong khi một lệnh `interrupt()` từ `cancel()` vẫn còn "đang bay" — loại bỏ hoàn toàn khả năng interrupt rơi nhầm sang task kế tiếp.

Test `cancelTrueInterruptsTheThreadActuallyRunningTheTask` đo được: task đang `Thread.sleep(5000)`, gọi `cancel(true)` sau khi task chắc chắn đã bắt đầu (đợi qua `CountDownLatch`) → `InterruptedException` được ném ra bên trong task trong vòng chưa tới 200ms, không phải chờ hết 5 giây.

Test `cancelFalsePreventsAnUnstartedTaskFromEverRunning` đo được: giữ độc quyền thread duy nhất của pool bằng một task chặn (`blocker`), submit task thứ hai rồi `cancel(false)` trong khi nó chắc chắn còn `NEW` (chưa vào hàng đợi thread nào) → sau khi thả blocker ra, worker lấy task đã-huỷ ra khỏi hàng đợi nhưng `run()` thấy `state != NEW` ngay từ đầu nên **không bao giờ gọi `callable.call()`** — biến `started` đo được vẫn là `false`.

## `get()` block bằng `Condition`, không bằng vòng lặp `sleep`

```java
private int awaitDone(long timeout, TimeUnit unit) throws InterruptedException {
    lock.lock();
    try {
        while (state.get() <= COMPLETING) {
            done.await(); // hoac done.awaitNanos(...) neu co timeout
        }
        return state.get();
    } finally {
        lock.unlock();
    }
}
```

Đây là đúng "guarded wait" pattern đã học ở bài 5–6: điều kiện chờ là `state <= COMPLETING` (chưa xong), kiểm tra trong vòng `while` (không phải `if`) để chống spurious wakeup, và `finishCompletion()` gọi `done.signalAll()` mỗi khi task hoàn tất theo bất kỳ đường nào (`NORMAL`, `EXCEPTIONAL`, hay `CANCELLED`/`INTERRUPTED`).

Về visibility: `outcome` là một field **thường** (không `volatile`), nhưng nó vẫn an toàn để đọc từ thread khác — không phải nhờ `lock`, mà nhờ trật tự: `run()` ghi `outcome` **trước khi** ghi `state` (một `AtomicInteger`, vốn có ngữ nghĩa volatile); `get()`/`report()` đọc `state` **trước khi** đọc `outcome`. Đây chính là kỹ thuật "volatile làm hàng rào" đã học ở bài 1–3: ghi field thường → ghi volatile, rồi đọc volatile → đọc field thường, tạo ra happens-before đủ để field thường được nhìn thấy đúng, dù thread đọc có đi qua `lock` (do phải chờ ở `awaitDone`) hay không (đọc thẳng khi `state` đã `> COMPLETING` từ trước).

## Exception được bọc thành `ExecutionException`

Task ném ra bất kỳ `Throwable` nào cũng được `run()` bắt lại, lưu vào `outcome`, và `report()` bọc nó thành `new ExecutionException(cause)` khi `get()` được gọi — thay vì để exception gốc bay thẳng ra, làm người gọi `get()` không phân biệt được "task thất bại vì lỗi của chính nó" với "lỗi trong chính cơ chế `Future`". Đo được: `future.get()` trên một task ném `IllegalStateException("boom")` ném ra `ExecutionException` với `getCause()` đúng là `IllegalStateException("boom")` gốc.

## Câu hỏi tự kiểm tra
- Nếu bỏ vòng `while (state.get() == INTERRUPTING) { Thread.yield(); }` ở `finally` của `run()`, kịch bản cụ thể nào (thứ tự các bước) sẽ khiến interrupt "rơi" sang task kế tiếp mà thread này chạy sau đó?
- Tại sao `cancel(false)` không cần quan tâm tới `runner` hay chờ `INTERRUPTING` gì cả — nó chỉ cần một CAS `NEW → CANCELLED` duy nhất là xong? Sự khác biệt căn bản giữa hai chế độ `cancel(true)`/`cancel(false)` nằm ở chỗ nào trong state machine?
