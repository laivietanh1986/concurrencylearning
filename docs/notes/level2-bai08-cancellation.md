# Bài 8 — Ba cách dừng một task

## Ba kỹ thuật, ba loại "task bị chặn" khác nhau

| Kỹ thuật | Áp dụng cho | Vì sao |
|---|---|---|
| Cờ `volatile boolean` | Task CPU-bound, chạy vòng lặp thuần tuý, không block ở đâu cả | Task tự kiểm tra cờ ở đầu mỗi vòng lặp — không cần ai "đánh thức" nó, nó luôn đang chạy và có thể tự nhìn thấy cờ (nhờ `volatile`, xem lại bài 2) |
| `Thread.interrupt()` | Task đang block trong một API "biết interrupt" (`sleep`, `wait`, `Condition.await`, `join`, I/O trong NIO...) | Những API này chủ động kiểm tra interrupt status và ném `InterruptedException`/dừng block ngay khi bị interrupt |
| Đóng resource | Task block trong một API **không** biết interrupt (I/O chặn kiểu cũ, ví dụ `Socket.getInputStream().read()` trên blocking socket) | Không có cách nào "đánh thức" cuộc gọi đó từ bên ngoài bằng interrupt — chỉ có cách làm cho chính cuộc gọi đó thất bại, bằng cách đóng resource nó đang đọc |

Ba kỹ thuật này **không thay thế nhau được** — chọn nhầm kỹ thuật cho loại task sai sẽ khiến việc hủy task hoàn toàn vô tác dụng, như thí nghiệm dưới đây cho thấy.

## Kỹ thuật 1 — cờ `volatile boolean`

```java
while (!cancelled) { ... }
```
Đo được: gọi `cancel()` sau 100ms, thread dừng trong vòng 2 giây (thực tế gần như ngay lập tức nhờ `volatile` — xem lại bài 2). Đây là kỹ thuật đơn giản nhất nhưng **chỉ hoạt động nếu task không bao giờ block** — nếu vòng lặp có một bước gọi `Thread.sleep()`, `queue.take()`, hay đọc socket, task vẫn phải chờ hết bước block đó rồi mới quay lại kiểm tra cờ, có thể trễ rất lâu (hoặc mãi mãi nếu bước block đó không có gì đánh thức nó).

## Kỹ thuật 2 — `Thread.interrupt()` — và cái bẫy "nuốt exception"

### Bản sai cố tình (`InterruptibleTaskBroken`)
```java
while (true) {
    try {
        Thread.sleep(10);
    } catch (InterruptedException e) {
        // trống - khong lam gi
    }
}
```
Đo được: gọi `interrupt()` sau 100ms, thread **vẫn còn sống** sau 500ms, và vẫn tiếp tục tăng biến đếm `iterations` bình thường như chưa hề có chuyện gì xảy ra. `interrupt()` hoàn toàn vô tác dụng.

**Vì sao**: `Thread.sleep()` khi bị interrupt sẽ ném `InterruptedException` **và tự xoá cờ interrupt** (giống `wait()`/`Condition.await()` đã học ở bài 5–6). Catch block trống bắt exception đó, không kiểm tra gì, không làm gì, vòng lặp `while(true)` (không điều kiện thoát) quay lại gọi `sleep()` tiếp ở vòng kế — mọi dấu vết của yêu cầu hủy đã biến mất hoàn toàn ngay từ dòng `catch` đó.

### Bản đã sửa (`InterruptibleTask`)
```java
while (!Thread.currentThread().isInterrupted()) {
    try {
        Thread.sleep(10);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();  // khoi phuc co
        break;                                // thoat ngay, khong doi vong lap sau
    }
}
```
Hai lớp phòng thủ: `isInterrupted()` ở đầu vòng lặp bắt trường hợp interrupt đến **giữa hai lần gọi `sleep()`** (không nằm trong `try`); `catch` + `restore` + `break` bắt trường hợp interrupt đến **trong lúc đang `sleep()`**. Đo được: thread dừng trong vòng 2 giây sau `interrupt()`.

## Kỹ thuật 3 — đóng resource: khi interrupt không có tác dụng gì cả

`BlockingResource.read()` mô phỏng một cuộc gọi blocking kiểu cũ (như blocking socket read thật trong Java — đây không phải chuyện bịa, đó là hành vi thật của `java.net.Socket`): nó block trong `Object.wait()` nhưng **cố tình nuốt `InterruptedException` và tiếp tục chờ** — mô phỏng đúng thực tế: một số API blocking ở tầng native (I/O hệ điều hành) hoàn toàn không biết Java có khái niệm "interrupt status", nên dù bạn gọi `Thread.interrupt()` bao nhiêu lần, cuộc gọi vẫn treo y nguyên.

Đo được:
1. Gọi `interrupt()` trên thread đang kẹt trong `read()` → thread **vẫn sống** sau 300ms — interrupt hoàn toàn vô dụng.
2. Gọi `resource.close()` → thread dừng trong vòng 2 giây, và `wasStoppedByClose()==true` (dừng đúng vì `ResourceClosedException`, không phải chết bất thường).

**Chính sách hủy (cancellation policy) đúng đắn** ở đây không phải "cố interrupt mạnh hơn" — không có API nào làm được điều đó cho loại blocking call này — mà là: **thiết kế lại API để có một đường thoát khác** (`close()`), khiến cuộc gọi blocking tự thất bại bằng một exception mà task có thể bắt được. Đây chính xác là lý do các thư viện HTTP/socket hiện đại (`SocketChannel` của NIO, HttpClient...) được thiết kế lại để tương thích với interrupt hoặc cung cấp `close()`/timeout thay vì dựa vào cuộc gọi blocking kiểu cũ.

## Cancellation policy — chọn kỹ thuật nào cho task nào

Trước khi viết một task "có thể hủy", phải trả lời trước: **task này block ở đâu, nếu có?**
- Không block ở đâu cả (thuần CPU) → cờ `volatile` là đủ, đơn giản nhất.
- Block trong API chuẩn của JDK biết interrupt → dùng `Thread.interrupt()`, luôn nhớ **kiểm tra ở đầu vòng lặp VÀ bắt exception đúng cách** (không được nuốt).
- Block trong API không biết interrupt (I/O cũ, thư viện native, JNI) → phải có một cơ chế đóng/huỷ tường minh (`close()`, `cancel()` của chính resource đó), và task phải bắt được lỗi phát sinh từ việc đóng đó để biết mình bị hủy (không phải một lỗi thật).

## Câu hỏi tự kiểm tra
- Nếu `InterruptibleTaskBroken` gọi `Thread.sleep()` bên trong một vòng `for` có điều kiện dừng rõ ràng (ví dụ chạy đúng 1000 lần) thay vì `while(true)`, task có tự dừng được không? Điều đó có thay thế được việc xử lý `InterruptedException` đúng cách không?
- `Thread.interrupt()` gọi trên một thread đang kẹt trong `BlockingResource.read()` — cờ interrupt của thread đó cuối cùng có được set hay không? Việc `catch` rồi bỏ qua đó có vi phạm quy tắc "phải restore cờ" học ở bài 6 không? (Gợi ý: quy tắc ở bài 6 áp dụng khi **bạn viết code cấp cao xử lý** exception; ở đây bài học khác — chính API blocking tầng thấp mới là nơi có vấn đề, không phải task gọi nó.)
