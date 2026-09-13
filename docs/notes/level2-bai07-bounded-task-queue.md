# Bài 7 — `BoundedTaskQueue`: circular buffer, hai `Condition`, thundering herd

## Cấu trúc circular buffer

Mảng cố định `Object[] buffer`, ba biến trạng thái bảo vệ bởi `ReentrantLock`:
- `head` — vị trí phần tử **sắp bị lấy ra** (`take()`).
- `tail` — vị trí **sắp được ghi vào** (`put()`).
- `count` — số phần tử hiện có (dùng để phân biệt buffer đầy hay rỗng, vì `head == tail` xảy ra ở cả hai trạng thái nếu chỉ dựa vào 2 chỉ số).

`put()` ghi vào `tail` rồi `tail = (tail + 1) % capacity`; `take()` đọc từ `head` rồi `head = (head + 1) % capacity`. Phép `% capacity` biến mảng thành một vòng tròn — đây là lý do gọi là "circular buffer": không cần dịch chuyển phần tử, chỉ cần index chạy vòng.

## SPSC trước, MPMC sau — vì sao thứ tự này quan trọng

- **SPSC** (1 producer, 1 consumer): chỉ cần đúng 2 thread tranh nhau lock, dễ suy luận, dễ debug nếu sai (chỉ có 1 nguồn ghi, 1 nguồn đọc). Test SPSC ở đây còn kiểm tra được **thứ tự FIFO tuyệt đối** (`consumed.get(i) == i` với mọi `i`) — điều không thể assert được nữa khi có nhiều producer (không ai đảm bảo producer nào chạy trước).
- **MPMC** (4 producer, 3 consumer): cùng một lock, nhưng giờ có nhiều thread cùng cạnh tranh **cả hai phía** (nhiều producer tranh nhau `enqueue`, nhiều consumer tranh nhau `dequeue`) — đây là lúc các bug về `signal()` chọn nhầm thread, hay tranh chấp index, mới có cơ hội lộ diện. Nếu nhảy thẳng vào MPMC mà không qua SPSC, một bug ở tầng "logic cơ bản" (off-by-one trong `% capacity`, thứ tự lock/unlock sai) rất dễ bị nhầm lẫn với bug ở tầng "tranh chấp nhiều thread".

## Test "xương sống": 4 producer + 3 consumer, 100.000 item

Thay vì dùng một `AtomicInteger` chung để sinh ID (dễ tạo thêm một điểm tranh chấp không cần thiết), mỗi producer được giao một **dải số cố định, không giao nhau** (producer thứ *p* tạo đúng các số `[p×25000, (p+1)×25000)`), nên tổng cộng biết chính xác tập `{0, 1, ..., 99999}` phải xuất hiện đúng một lần.

Phía consumer: 3 thread cùng gọi `queue.take()`, nhưng cần biết khi nào dừng — dùng một `AtomicInteger reserved` để **mỗi thread tự "đặt chỗ" trước** bằng `reserved.getAndIncrement()`; nếu chỗ đặt được ≥ 100.000 thì dừng, ngược lại chắc chắn sẽ có một `take()` tương ứng thành công (vì tổng số `put()` đúng bằng 100.000). Cách này chia việc công bằng giữa 3 consumer mà không cần biết trước ai nhanh ai chậm.

Ghi nhận kết quả bằng `AtomicIntegerArray` (kích thước 100.000), `incrementAndGet(value)` mỗi khi nhận được `value`. Cuối cùng assert **mọi ô đều đúng bằng 1** — không phải chỉ "khác 0" (kiểm tra "không mất") mà còn "không được lớn hơn 1" (kiểm tra "không trùng"). Dùng `AtomicIntegerArray` thay vì `int[]` là bắt buộc: nhiều consumer có thể tình cờ lấy trùng cùng một `value` (nếu buffer có bug), và khi đó nhiều thread `++` vào cùng một ô — chính là bug bài 1 (lost update) nếu dùng mảng thường.

## Vì sao cần hai `Condition` riêng, không chỉ một

`notFull` chỉ có ý nghĩa với **producer** (đang chờ chỗ trống); `notEmpty` chỉ có ý nghĩa với **consumer** (đang chờ có hàng). Khi `put()` thành công, chỉ cần đánh thức **một consumer** (không cần đụng tới producer nào khác đang chờ chỗ trống, vì hành động `put()` không tạo thêm chỗ trống cho ai cả). Tương tự `take()` thành công chỉ cần đánh thức **một producer**. Vì `notFull` và `notEmpty` là hai wait-set tách biệt, `signal()` gọi trên đúng Condition sẽ **chỉ** đánh thức đúng nhóm thread có khả năng thực sự tận dụng được sự thay đổi đó.

## Vì sao một `Condition` + `signalAll()` vẫn ĐÚNG nhưng TỆ hơn — đo được bằng số liệu thật

Bản `BoundedTaskQueueSingleCondition` dùng đúng một `Condition stateChanged` cho cả hai loại chờ, và gọi `signalAll()` mỗi khi `put()`/`take()` thành công. Về mặt **đúng đắn**, không có gì sai: mọi thread bị đánh thức đều tự kiểm tra lại điều kiện của mình (`while (count == capacity)` hoặc `while (count == 0)`) trước khi làm gì tiếp — nếu điều kiện chưa thoả, nó lại `await()` tiếp, không có gì bị vi phạm (đây chính là kỷ luật `while` học từ bài 5, áp dụng lại ở đây).

Nhưng về **hiệu năng**, đây là **thundering herd**: mỗi lần `signalAll()`, **toàn bộ** thread đang chờ — cả producer lẫn consumer — đều bị đánh thức, tranh nhau giành lại `lock`, rồi phần lớn trong số đó phát hiện điều kiện của mình vẫn chưa thoả và quay lại ngủ ngay — lãng phí một lượt context switch hoàn toàn vô ích cho mỗi thread "bị đánh thức nhầm".

Đo trực tiếp bằng benchmark riêng (8 producer + 8 consumer, capacity chỉ 2 phần tử để ép tranh chấp cao, 32.000 item):

| Biến thể | wakeup / operation |
|---|---|
| Hai `Condition` (`signal()`) | **0,93** — gần 1:1, hầu như mỗi lần thức dậy đều làm được việc ngay |
| Một `Condition` (`signalAll()`) | **1,85–1,97** — gấp đôi: cứ mỗi thao tác put/take thành công, có xấp xỉ một lượt "đánh thức nhầm" đi kèm |

Với 8+8=16 thread cùng chờ trên một lock, mỗi `signalAll()` có thể đánh thức tới 15 thread khác — hầu hết trong số đó (những thread chờ *cùng loại điều kiện* với thread vừa được phục vụ) sẽ ngủ lại ngay. Con số 1,85–1,97 phản ánh đúng phần "lãng phí" đó tăng gấp đôi so với thiết kế hai `Condition`. Với hệ thống có hàng trăm/nghìn thread tranh chấp một queue (điển hình trong thread pool ở Cấp 3), tỷ lệ lãng phí này càng khuếch đại theo số lượng thread đang chờ.

## Câu hỏi tự kiểm tra
- Nếu `capacity` rất lớn (ví dụ 1.000.000) so với số item cần xử lý, sự khác biệt giữa hai thiết kế Condition có còn đáng kể không? Vì sao?
- Tại sao dùng `AtomicIntegerArray` (không phải `int[]` thường) mới đúng khi nhiều consumer có thể ghi cùng một ô? Liên hệ với bug ở bài 1.
