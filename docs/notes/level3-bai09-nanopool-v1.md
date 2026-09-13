# Bài 9 — NanoPool v1: khi một task ném exception, cả thread pool có thể chết dần

## Ý tưởng cốt lõi

Một thread pool tối giản chỉ cần hai thứ:
1. Một hàng đợi task dùng chung (ở đây tái sử dụng nguyên `BoundedTaskQueue` từ bài 7).
2. N thread cố định ("worker"), mỗi worker chạy vòng lặp vô hạn: `queue.take()` rồi gọi `task.run()`.

```java
private void workerLoop() {
    while (true) {
        Runnable task = queue.take();
        task.run();
    }
}
```

`execute(Runnable task)` bên ngoài chỉ đơn giản là `queue.put(task)`. Không có gì phức tạp — cho tới khi một task ném exception.

## Bước 1 — cố tình KHÔNG bọc try/catch (`NanoPoolBroken`)

Nếu `task.run()` ném một `RuntimeException` mà không có try/catch nào bắt nó bên trong `workerLoop()`, exception đó thoát thẳng ra khỏi phương thức `run()` của chính **worker thread**. Theo đặc tả của `Thread` trong Java: khi một exception không được bắt thoát ra khỏi `Thread.run()`, JVM gọi `UncaughtExceptionHandler` mặc định (in stack trace ra `stderr`) rồi **thread đó kết thúc vĩnh viễn** — không có gì "bắt" nó quay lại vòng lặp `while(true)` được nữa, vì bản thân stack frame chứa vòng lặp đó đã bị unwind theo exception.

Đo được khi submit 1000 task (task nào có index chia hết cho 10 thì ném exception — 100/1000 task) vào pool 3 thread, hàng đợi capacity 5:

```
Exception in thread "nanopool-broken-0" java.lang.RuntimeException: boom 0
Exception in thread "nanopool-broken-1" java.lang.RuntimeException: boom 10
Exception in thread "nanopool-broken-2" java.lang.RuntimeException: boom 20
```

Cả 3 thread chết **gần như ngay lập tức** — chỉ sau 3 trong số 100 "quả bom" đầu tiên (task index 0, 10, 20), vì 3 quả bom đầu tiên trong hàng đợi được 3 worker khác nhau lấy ra gần như cùng lúc. Sau đó:
- `aliveWorkerCount() == 0` — không còn worker nào sống để gọi `queue.take()`.
- Producer (`execute()` → `queue.put()`) vẫn tiếp tục enqueue cho tới khi hàng đợi đầy (capacity 5), rồi `put()` gọi `notFull.await()` và **treo vĩnh viễn** — không còn ai `take()` để `signal()` đánh thức nó nữa.
- Test đo được: `submitted.get() < 1000` (submitter bị kẹt trong `put()`, không bao giờ submit hết 1000 task).

Đây chính xác là "pool teo dần rồi treo" mà đề bài mô tả: mỗi task lỗi giết một worker → pool "teo" từng chút một → khi worker cuối cùng chết, hàng đợi đầy lên và không ai rút ra nữa → toàn bộ hệ thống treo (deadlock kiểu "hàng đợi đầy, không ai tiêu thụ").

## Bước 2 — bọc lại try/catch (`NanoPool`)

```java
try {
    task.run();
} catch (Throwable t) {
    // nuot loi - worker khong duoc phep chet vi mot task
}
```

Chỉ cần một `try/catch(Throwable)` quanh lời gọi `task.run()`, nằm **bên trong** vòng lặp `while(true)` (không phải bao quanh cả vòng lặp), là đủ: exception bị bắt và nuốt ngay tại chỗ, thread không bao giờ rời khỏi `workerLoop()`, vòng lặp tự nhiên quay lại `queue.take()` để lấy task kế tiếp.

Đo được với cùng 1000 task (100 task ném exception) trên pool 3 thread: sau khi hàng đợi rút cạn, `aliveWorkerCount() == 3` (không thread nào chết) và `completed.get() == 900` — đúng bằng số task **không** ném exception. 100 task lỗi bị nuốt âm thầm, nhưng không kéo theo bất kỳ hậu quả nào cho các task khác hay cho pool.

**Lưu ý quan trọng — bắt `Throwable` chứ không chỉ `Exception`:** một task tệ có thể ném `Error` (ví dụ lỗi logic gây `StackOverflowError`), và nếu chỉ bắt `Exception`, `Error` vẫn sẽ giết worker y hệt bài học ở bước 1. (Trong thực tế, `ThreadPoolExecutor` của JDK cũng đối mặt đúng vấn đề này — đó là lý do `Runnable.run()` không được phép "biến mất" khỏi thread mà không qua tay executor.)

## Vì sao thiết kế này quan trọng hơn nó trông có vẻ

Một thread pool không có try/catch quanh `task.run()` là một **quả bom hẹn giờ ẩn**: hệ thống chạy hoàn toàn bình thường cho tới ngày một task nào đó (do lỗi lập trình, dữ liệu bất thường, timeout của một cuộc gọi mạng...) ném ra một exception chưa từng được tính tới. Từ thời điểm đó, pool bắt đầu "rò rỉ" worker — mỗi lần đúng loại task lỗi đó chạy lại, một worker biến mất — cho tới khi throughput giảm dần rồi toàn bộ ứng dụng treo, mà **không có bất kỳ log lỗi rõ ràng nào** ngoài vài dòng stack trace trôi qua console (nếu không có `UncaughtExceptionHandler` tuỳ chỉnh, rất dễ bị bỏ qua giữa hàng nghìn dòng log khác).

## Câu hỏi tự kiểm tra
- Nếu `workerLoop()` bắt exception nhưng đặt `try/catch` bao quanh **toàn bộ vòng lặp `while(true)`** thay vì chỉ quanh `task.run()`, worker có còn sống sót đúng cách không? (Gợi ý: sau khi catch, control flow đi tới đâu — có quay lại đầu `while(true)` được không, hay thoát khỏi `workerLoop()` luôn?)
- Tại sao bài học ở đây lại áp dụng đúng cả cho `Thread.UncaughtExceptionHandler` mặc định của JVM lẫn cho các thread pool thật trong JDK (`ThreadPoolExecutor`) — chúng xử lý "task ném exception" bằng chính sách nào để không bị vấn đề tương tự?
