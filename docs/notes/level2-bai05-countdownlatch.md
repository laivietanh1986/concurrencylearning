# Bài 5 — `MyCountDownLatch`: guarded wait, spurious wakeup, `if` vs `while`

## So sánh hai cách viết

| | `synchronized` + `wait/notifyAll` | `ReentrantLock` + `Condition` |
|---|---|---|
| Khoá | intrinsic lock của `this` (ẩn) | `ReentrantLock` tường minh, có thể tách nhiều `Condition` trên cùng 1 lock |
| Chờ | `wait()` — phải gọi trong khối `synchronized` trên **đúng object đang wait** | `condition.await()` — phải gọi trong khối `lock()...unlock()`, và `Condition` phải sinh ra từ **đúng** `lock` đó (`lock.newCondition()`) |
| Đánh thức | `notifyAll()` đánh thức **mọi** thread đang wait trên cùng monitor, bất kể chúng chờ vì lý do gì | `signalAll()` chỉ đánh thức thread đang chờ trên **đúng `Condition` đó** — nếu một lock có nhiều `Condition` (vd `notFull`/`notEmpty` ở bài 7), bạn chỉ đánh thức đúng nhóm liên quan |
| Try/finally | không cần (`synchronized` tự nhả khoá kể cả khi exception) | **bắt buộc** `try { ... } finally { lock.unlock(); }` — quên `unlock()` là deadlock vĩnh viễn |
| Interrupt | `wait()` ném `InterruptedException`, tự động nhả khoá | `await()` cũng ném `InterruptedException`, tự động nhả khoá — hành vi tương đương |

Về bản chất hai cách viết logic giống hệt nhau (cùng dùng **guarded wait pattern**), khác nhau ở API. Điểm `Condition` hơn hẳn `wait/notifyAll`: tách được nhiều điều kiện chờ khác nhau trên cùng một lock, tránh phải đánh thức nhầm thread không liên quan (quan trọng ở bài 7 — `BoundedTaskQueue`).

## Bug cố tình gây ra: `if (count > 0) wait();`

Code này **trông như đúng**: nếu count còn dương thì đợi, đợi xong (được notify) thì chắc chắn count đã về 0 rồi, return luôn. Vấn đề là: **`wait()` (và `Condition.await()`) được phép trả về mà không có ai gọi `notify()` cả** — gọi là **spurious wakeup**, được cho phép rõ ràng trong Javadoc của `Object.wait()` lẫn `Condition.await()`. Ngoài ra, `notifyAll()` đánh thức **mọi** thread đang wait trên monitor đó — kể cả một wakeup không liên quan (ví dụ do lỗi lập trình ở chỗ khác gọi nhầm `notifyAll()` trên cùng object) cũng đủ để một `if` sai lầm cho thread đi tiếp trong khi điều kiện thật sự vẫn chưa thoả.

### Đo được thực tế (không cần chờ may rủi của JVM — tự tạo ra spurious wakeup)

Spurious wakeup thật của JVM cực kỳ hiếm gặp một cách tự nhiên (giống bug "torn object" ở bài 3 — JMM cho phép nhưng phần cứng/JVM hiện tại hiếm khi tự kích hoạt). Thay vì ngồi chờ may rủi, bài này **tự mô phỏng** nó bằng một hook test-only `debugForceSpuriousWakeup()` gọi thẳng `notifyAll()`/`signalAll()` mà không đụng vào `count`. Đây chính xác là điều một spurious wakeup thật sẽ gây ra: `wait()` trả về, `count` không đổi.

Kết quả đo được:

| Biến thể | `await()` có trả về khi bị "spurious wakeup" giả lập? | `getCount()` sau đó |
|---|---|---|
| `if` (broken) | **Có — bug xảy ra** | vẫn là 1 (chưa hề `countDown()`) |
| `while` (sync, đã sửa) | Không — tiếp tục `wait()` | vẫn là 1, đến khi `countDown()` thật mới trả về 0 |
| `while` (lock, `Condition`) | Không — tiếp tục `await()` | vẫn là 1, đến khi `countDown()` thật mới trả về 0 |

Với `if`: thread đi tiếp **dù `count` vẫn là 1** — vi phạm hoàn toàn hợp đồng của một latch ("chỉ mở khi count về 0"). Với `while`: sau khi bị đánh thức, vòng lặp **kiểm tra lại điều kiện** (`count > 0`), thấy vẫn đúng, quay lại `wait()`/`await()` ngủ tiếp — chỉ thoát khi điều kiện thực sự sai (`count == 0`).

## Vì sao guarded wait bắt buộc dùng `while`, không phải chỉ "để cho chắc"

Ba lý do độc lập, bất kỳ lý do nào cũng đủ để bắt buộc dùng `while`:

1. **Spurious wakeup được phép theo đặc tả** (JLS 17.2.1, Javadoc `Object.wait()`, `Condition.await()`) — không phải lỗi JVM, là hành vi hợp lệ bạn phải tự chống đỡ.
2. **`notifyAll()` đánh thức mọi thread**, không phân biệt "thread nào nên được đánh thức vì lý do nào" — nếu monitor được dùng cho nhiều mục đích (hoặc bị gọi nhầm), thread của bạn có thể tỉnh dậy vì tín hiệu không liên quan tới mình.
3. **Giữa lúc được đánh thức và lúc thực sự giành lại được lock để chạy tiếp, một thread khác có thể đã chen vào và làm điều kiện đổi lại** (race giữa nhiều waiter/notifier) — dù trường hợp `CountDownLatch` cụ thể này không rơi vào ca này (count chỉ giảm, không tăng lại), đây là lý do chung áp dụng cho các synchronizer khác (ví dụ `MySemaphore` ở bài 6, nơi permit có thể bị "cướp" giữa lúc được signal và lúc thực sự lấy được).

## `notify()` vs `notifyAll()`

- `notify()` chỉ đánh thức **một** thread đang wait trên monitor đó (JVM tự chọn, không đảm bảo công bằng/thứ tự nào).
- `notifyAll()` đánh thức **tất cả**.

Với `CountDownLatch`, bắt buộc dùng `notifyAll()`/`signalAll()`: khi count về 0, **mọi** waiter đều phải được mở, không phải chỉ một. Nếu lỡ dùng `notify()`, chỉ một waiter được đánh thức, các waiter còn lại **treo vĩnh viễn** dù `count` đã về 0 (vì không có ai gọi `countDown()` thêm để phát sinh notify mới — bug im lặng, chỉ lộ ra khi có ≥2 waiter, dễ sót nếu test chỉ có 1 thread chờ).

## Câu hỏi tự kiểm tra
- Nếu `debugForceSpuriousWakeup()` được gọi liên tục 1000 lần trên bản `while` đã sửa, `count` có bao giờ bị thay đổi không? Vì sao an toàn tuyệt đối bất kể gọi bao nhiêu lần?
- Vì sao `MyCountDownLatchLock` không cần một bản "broken" riêng để tái hiện lỗi — logic `if`/`while` ở đây có gì giống hệt bản `synchronized`?
