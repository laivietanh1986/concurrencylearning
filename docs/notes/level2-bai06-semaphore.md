# Bài 6 — `MySemaphore` + interruption

## API và cách dùng ReentrantLock/Condition

`MySemaphore` giữ một số nguyên `permits`, bảo vệ bởi `ReentrantLock` + một `Condition` duy nhất (`permitAvailable`):

- `acquire()`: `while (permits == 0) permitAvailable.await();` rồi `permits--`.
- `tryAcquire(timeout, unit)`: giống trên nhưng dùng `awaitNanos` với ngân sách thời gian giảm dần, trả `false` nếu hết giờ mà vẫn chưa có permit.
- `release()`: `permits++;` rồi `signal()`.

Cấu trúc giống hệt `MyCountDownLatchLock` ở bài 5 — cùng là guarded wait, khác ở chỗ điều kiện chờ đảo ngược (latch chờ count **về** 0, semaphore chờ permits **khác** 0).

## Bẫy cố tình: `MySemaphoreBroken` dùng `notify()` thay vì `Condition`

Roadmap mô tả một bug kinh điển: *"thread bị interrupt ngay sau khi được `notify()` chọn nhưng trước khi kịp lấy permit → wakeup đó bị lãng phí, thread khác treo vĩnh viễn."* Đây là hệ quả của một sự thật ít người để ý: **JLS không đặc tả rõ thứ tự giữa "một thread bị interrupt" và "cùng thread đó đang được `notify()` chọn"** khi cả hai xảy ra gần như đồng thời trên `Object.wait()`/`notify()` thô. Ngược lại, `java.util.concurrent.locks.Condition` (dựa trên AQS) được lập trình **có chủ đích** để xử lý đúng race này: nếu một thread đã thực sự được `signal()` chuyển sang hàng chờ giành lock trước khi interrupt tới, `Condition.await()` sẽ **trả về bình thường** (không ném exception), chỉ tự đặt lại cờ interrupt để code gọi biết mà xử lý sau — nhờ vậy permit không bao giờ bị "lãng phí". Đây chính là lý do `MySemaphore` (bản đúng) miễn nhiễm với bug này còn bản `wait/notify()` thô thì không.

## Hai lần đo — và bài học về việc tự thiết kế sai bài test trước khi đo đúng

**Lần đo đầu tiên** (thiết kế sai): 4 thread gọi `acquire()` trên semaphore 0 permit, interrupt 2 thread ngay sau khi start, rồi chỉ `release()` **2 lần**. Kết quả: **451/500 vòng bị treo**. Nhìn con số này tưởng đã "bắt được bug", nhưng phân tích kỹ lại thấy đây là **lỗi thiết kế test, không phải bug của `MySemaphoreBroken`**: với 4 thread mà chỉ có 2 permit, đúng 2 thread **chắc chắn phải treo mãi mãi theo thiết kế** (không có `release()` nào khác đến để cứu chúng) — kể cả nếu semaphore hoàn toàn đúng, kết quả vẫn treo y hệt! Con số 451/500 không đo được điều ta muốn đo.

**Lần đo thứ hai** (đã sửa): release đúng **4 lần cho 4 thread** (khớp cung–cầu), để mọi thread — kể cả 2 thread bị interrupt — về lý thuyết đều có thể lấy được permit nếu không có gì bị lãng phí; đợi cả 4 thread thực sự park trong `wait()` (`Thread.sleep(30)`) trước khi bắn interrupt + release gần như đồng thời để tối đa hoá khả năng trúng đúng khe hở race. Kết quả: **0/500 vòng bị treo**.

→ Đây là bài học kép:
1. **Một test concurrency có thể "phát hiện bug" hoàn toàn sai lý do** — luôn phải tự hỏi "nếu code đúng 100%, kết quả mong đợi ở kịch bản test này là gì?" trước khi tin vào một con số bất thường.
2. Giống hệt bug "torn object" ở bài 3: JLS **cho phép** race "interrupt vs notify" gây mất wakeup, nhưng **rất khó ép nó xảy ra** từ code tầng ứng dụng trên JVM hiện tại (HotSpot xử lý notify/interrupt đủ nhanh/gọn để khe hở thực tế cực hẹp). Không đo được không có nghĩa code đúng theo đặc tả — nó chỉ có nghĩa lần này JVM không lộ ra.

## Test chính theo đúng yêu cầu roadmap: "N thread tranh permit, interrupt ngẫu nhiên một nửa, assert tổng permit không mất"

Với `MySemaphore` (bản đúng, dùng `Condition`): 40 thread tranh 10 permit, một nửa (20 thread) bị `interrupt()` gần như ngay sau khi start — có thể trúng vào lúc đang `await()`, đang `Thread.sleep()` sau khi đã có permit, hoặc đã chạy xong. Mỗi thread dùng đúng pattern **acquire → finally luôn release nếu đã acquire thành công**, dù có bị interrupt giữa chừng lúc đang giữ permit hay không:

```java
boolean acquired = false;
try {
    sem.acquire();
    acquired = true;
    ... làm việc, có thể bị interrupt ở đây ...
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // never nuốt exception
} finally {
    if (acquired) sem.release();          // luôn tra lai permit neu da lay duoc
}
```

Kết quả đo được: `successfulAcquires=29, interruptedCount=20` (tổng 40 threads đều được xử lý, một số acquire thành công trước khi bị interrupt lúc đang sleep), `maxHeldObserved=10` (không bao giờ vượt quá 10 — đúng giới hạn permit), và `availablePermits()` cuối cùng đúng bằng 10 — **không mất, không thừa permit nào**, dù bị interrupt loạn xạ.

## `InterruptedException` và quy tắc không được nuốt

Ba điểm mấu chốt:

1. **Interrupt là một cờ (flag), không phải lệnh giết**: `Thread.interrupt()` chỉ đặt `interrupted status = true`. Thread bị nhắm tới **tự quyết định** khi nào kiểm tra cờ này và phản ứng ra sao — không có gì buộc nó dừng ngay lập tức.
2. **Các API blocking "biết interrupt"** (`wait()`, `Condition.await()`, `Thread.sleep()`, `join()`...) khi thấy cờ bị set sẽ **ném `InterruptedException` VÀ tự xoá cờ (clear)** — nghĩa là sau khi bắt exception, `Thread.currentThread().isInterrupted()` sẽ trả về `false` nếu bạn không làm gì thêm.
3. **Vì cờ đã bị xoá, nếu code gọi bạn (caller) cần biết "đã có ai yêu cầu dừng chưa"**, bạn **bắt buộc** phải khôi phục lại: hoặc `throw` tiếp `InterruptedException` (nếu method cho phép khai báo `throws`), hoặc gọi `Thread.currentThread().interrupt()` để đặt lại cờ trước khi tiếp tục (khi đang ở trong `Runnable.run()` không được khai báo checked exception). **Tuyệt đối không được `catch (InterruptedException e) {}` bỏ trống** — đó là cách chắc chắn nhất để một cơ chế cancellation ở tầng trên (ví dụ `NanoPool.shutdownNow()` ở bài 11) không bao giờ biết được thread đã được yêu cầu dừng.

Test đã xác minh cụ thể: mọi thread bị interrupt trong lúc đang giữ permit (bắt exception từ `Thread.sleep()`) đều gọi `Thread.currentThread().interrupt()` trước khi kết thúc, và `assertTrue(t.isInterrupted())` sau khi thread đã chết vẫn đọc đúng cờ đã khôi phục — chứng minh việc "restore flag" hoạt động và có thể kiểm chứng được.

## Câu hỏi tự kiểm tra
- Vì sao `MySemaphore` (dùng `Condition`) không cần một bản "broken" để tái hiện bug interrupt-vs-notify, còn bản `wait/notify()` thô thì luôn tiềm ẩn nguy cơ đó?
- Nếu đổi `notify()` trong `MySemaphoreBroken.release()` thành `notifyAll()`, bug "wakeup bị lãng phí" còn khả năng xảy ra không? Đánh đổi hiệu năng nào phải trả khi dùng `notifyAll()` thay vì `notify()` cho một semaphore có nhiều permit?
