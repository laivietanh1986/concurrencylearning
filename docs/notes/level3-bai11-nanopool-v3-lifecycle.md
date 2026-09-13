# Bài 11 — NanoPool v3: vòng đời (`shutdown`/`shutdownNow`/`awaitTermination`)

## Ý tưởng cốt lõi

Một pool "sống mãi mãi" (như v1, v2) không dùng được cho một ứng dụng thật — phải có cách để nói "dừng lại" và biết chắc "đã dừng hẳn chưa". Ba câu hỏi cần trả lời rành mạch:
1. **`shutdown()`** — ngừng nhận task mới, nhưng phải chạy **hết** những task đã có trong hàng đợi trước khi dừng hẳn (graceful).
2. **`shutdownNow()`** — dừng ngay lập tức: interrupt cả task đang chạy, không xử lý gì thêm từ hàng đợi, và trả về danh sách task chưa kịp chạy để người gọi tự quyết định (thử lại, log, v.v.) — **không được để chúng biến mất âm thầm**.
3. **`awaitTermination(timeout, unit)`** — chờ (có giới hạn thời gian) cho tới khi pool thật sự dừng hẳn (mọi worker đã thoát).

## State machine: `RUNNING → SHUTDOWN → STOP → TERMINATED`

```
RUNNING ──shutdown()───────► SHUTDOWN ──(hang doi rong + worker cuoi thoat)──► TERMINATED
RUNNING ──shutdownNow()──────────────────────► STOP ──(worker cuoi thoat)────► TERMINATED
SHUTDOWN ──shutdownNow()─────────────────────► STOP
```

Trạng thái chỉ đi **một chiều về phía trước**, không bao giờ lùi lại — gọi `shutdown()` sau khi đã `shutdownNow()` là vô tác dụng (đã ở `STOP`, không lùi về `SHUTDOWN` được).

## Kỹ thuật `ctl`: gộp `runState` và `workerCount` vào một `AtomicInteger`

Đây là kỹ thuật thật của `ThreadPoolExecutor` trong JDK, không phải một giản lược cho bài học. Vấn đề nó giải quyết: nếu `runState` và `workerCount` là hai field riêng, một luồng có thể đọc `runState == SHUTDOWN` (đúng) rồi đọc `workerCount` **sau khi** một worker khác vừa giảm nó xuống 0 — kết quả là "biết state là SHUTDOWN và biết count là 0" nhưng đó là hai lần đọc tại hai thời điểm khác nhau, có thể không bao giờ **cùng đúng tại một thời điểm duy nhất**. Ghép cả hai vào một `int` rồi CAS trên toàn bộ `int` đó biến "kiểm tra cả state lẫn count" thành một phép đọc nguyên tử duy nhất.

```java
private static final int COUNT_BITS = Integer.SIZE - 3;      // 29 bit cho workerCount
private static final int COUNT_MASK = (1 << COUNT_BITS) - 1;

private static final int RUNNING    = -1 << COUNT_BITS;
private static final int SHUTDOWN   =  0 << COUNT_BITS;
private static final int STOP       =  1 << COUNT_BITS;
private static final int TERMINATED =  2 << COUNT_BITS;
```

Chọn `RUNNING = -1 << 29` không phải ngẫu nhiên: về mặt số nguyên có dấu, `RUNNING` (âm) `< SHUTDOWN (0) < STOP < TERMINATED` — nên có thể so sánh trạng thái bằng `<`, `>=` bình thường như so sánh số, không cần switch-case cồng kềnh (`advanceRunState` dùng đúng tính chất này: `runStateOf(c) >= targetState` để biết "đã đi xa hơn đích rồi, không cần làm gì nữa").

`decrementWorkerCount()` chỉ cần `ctl.compareAndSet(c, c - 1)` — trừ thẳng 1 vào toàn bộ `int` là an toàn vì `workerCount` nằm ở 29 bit thấp nhất và luôn `> 0` trước khi trừ, nên phép trừ không bao giờ "mượn" sang vùng bit của `runState`.

## `shutdown()` — vì sao worker phải tự "nhận ra" thay vì bị đánh thức

`shutdown()` **không được interrupt bất kỳ ai** — nếu nó vô tình interrupt một worker đang thực thi task (không phải đang rảnh), nó sẽ làm hỏng task đó, vi phạm đúng hợp đồng "graceful". Nhưng JDK thật giải quyết việc này bằng cách chỉ interrupt **những worker đang thật sự rảnh** (cần một khoá per-worker để biết chắc ai đang rảnh — khá phức tạp cho một bài học). Ở đây dùng một cách đơn giản hơn nhưng vẫn đúng: **worker không `queue.take()` (block vô hạn) nữa, mà `queue.poll(50, TimeUnit.MILLISECONDS)`** — nghĩa là cứ mỗi 50ms, một worker đang rảnh sẽ tự thức dậy và tự kiểm tra `runState` một lần, hoàn toàn không cần ai đánh thức nó bằng interrupt:

```java
private Runnable getTask() {
    while (true) {
        int c = ctl.get();
        int rs = runStateOf(c);
        if (rs >= STOP) return null;                        // STOP: bo task con lai, thoat ngay
        if (rs == SHUTDOWN && queue.size() == 0) return null; // SHUTDOWN + het viec: thoat
        Runnable task = queue.poll(50, TimeUnit.MILLISECONDS);
        if (task != null) return task;
        // timeout - quay lai kiem tra runState
    }
}
```

**Đánh đổi có chủ đích**: cách này đơn giản và luôn đúng (không có worker nào bị interrupt sai chỗ), nhưng nếu một worker đang rảnh, đúng lúc `shutdown()` được gọi khi hàng đợi rỗng, worker đó có thể mất tới ~50ms mới nhận ra. Đo được thực tế (`terminationLatencyWhenTheWorkerIsIdlyPollingAnEmptyQueue`): **37–51ms** — đúng như dự đoán, giới hạn trên bởi chu kỳ poll. Đây là cái giá chấp nhận được để đổi lấy một thiết kế đơn giản, không cần khoá per-worker phức tạp như JDK thật.

## `shutdownNow()` — dừng ngay, interrupt tất cả, trả lại phần còn dang dở

```java
public List<Runnable> shutdownNow() {
    advanceRunState(STOP);
    for (Thread w : workers) w.interrupt();   // interrupt TAT CA, ke ca worker dang chay task
    List<Runnable> remaining = drainQueue();  // rut sach hang doi, KHONG cho worker nao chay tiep
    tryTerminate();
    return remaining;
}
```

Khác với `shutdown()`, ở đây interrupt **mọi** worker kể cả đang chạy task — đúng ý nghĩa "cố hết sức để dừng ngay", chấp nhận việc một task đang chạy có thể bị cắt ngang (nếu task đó tôn trọng interrupt, như đã học ở bài 8). Đo được (`shutdownNowInterruptsTheRunningTaskAndReturnsUnexecutedOnes`): task đang `Thread.sleep(5000)` nhận `InterruptedException` gần như ngay lập tức thay vì phải chờ hết 5 giây; 5 task còn nằm trong hàng đợi được trả về đúng cả 5, và biến đếm đo được các task đó **chưa từng chạy** (`neverRanCount == 0`).

## Vì sao `execute()` phải kiểm tra lại **sau khi** đã enqueue

Đây là cái bẫy tinh vi nhất của bài này. Nếu `execute()` chỉ kiểm tra `runState == RUNNING` **trước khi** gọi `queue.put()`, vẫn còn một khe hở: `shutdown()`/`shutdownNow()` có thể xảy ra đúng vào khoảng giữa hai thao tác đó. Với `shutdownNow()`, nếu tất cả worker đã thoát trước khi task này kịp lọt vào hàng đợi, task đó sẽ nằm im vĩnh viễn — bị "mất âm thầm", đúng thứ đề bài yêu cầu tránh.

```java
try {
    queue.put(task);
} catch (InterruptedException e) { ... }

if (runStateOf(ctl.get()) != RUNNING && queue.remove(task)) {
    throw new RejectedExecutionException(...);
}
```

Kỹ thuật "enqueue trước, kiểm tra lại sau, vét ra nếu cần" này lấy nguyên ý tưởng từ `ThreadPoolExecutor.execute()` thật của JDK. Nó đảm bảo một bất biến chắc chắn: **mọi task hoặc sẽ được chạy (hoặc đã chạy), hoặc bị `RejectedExecutionException` một cách tường minh — không có khả năng thứ ba là "biến mất không dấu vết"**. Vùng ranh giới thời gian chồng lấn với đúng lúc gọi `shutdown()` có thể bị từ chối dù về mặt logic "có vẻ" nộp trước khi shutdown — đó là sự mơ hồ chấp nhận được ở đúng lằn ranh, giống hệt các thread pool thật.

## `awaitTermination()` — từ `while(!terminated) sleep(10)` tới `Condition`

Bản năng đầu tiên khi cần "chờ cho tới khi X xảy ra" thường là:

```java
// ANTI-PATTERN - KHONG dung cach nay
while (!isTerminated()) {
    Thread.sleep(10);
}
```

Cách này **chạy được**, nhưng có hai vấn đề thật sự (không phải lý thuyết suông):
- **Lãng phí CPU**: thread chờ thức dậy đều đặn mỗi 10ms chỉ để kiểm tra một điều kiện gần như luôn luôn vẫn là `false`, hàng trăm lần một giây, dù chẳng có gì thay đổi.
- **Độ trễ không cần thiết**: dù pool đã terminate xong ngay tức khắc, thread chờ vẫn có thể mất tới gần 10ms mới "biết" — độ trễ bị giới hạn dưới bởi chu kỳ `sleep`, không phải bởi tốc độ xử lý thật.

Thay vào đó, `awaitTermination()` dùng đúng "guarded wait" pattern đã học từ bài 5–7: chờ trên một `Condition`, được `signalAll()` đánh thức chính xác vào khoảnh khắc worker cuối cùng thoát (trong `tryTerminate()`):

```java
private void tryTerminate() {
    ...
    if (ctl.compareAndSet(c, ctlOf(TERMINATED, 0))) {
        terminationLock.lock();
        try {
            terminationCondition.signalAll();
        } finally {
            terminationLock.unlock();
        }
        return;
    }
}
```

Đo được (`awaitTerminationWakesUpPromptlyRightAfterTheLastWorkerExits`): sau khi task cuối cùng được thả ra chạy xong, thread đang `awaitTermination()` tỉnh dậy trong **0ms** (làm tròn) — không phải "trong vòng 10ms tiếp theo" mà là ngay khi `signalAll()` được gọi, đúng bản chất của "được đánh thức" thay vì "tự hỏi lại theo chu kỳ".

## Câu hỏi tự kiểm tra
- Tại sao `tryTerminate()` phải kiểm tra `queue.size() != 0` chỉ khi `runState == SHUTDOWN`, mà không cần kiểm tra điều đó khi `runState == STOP`? (Gợi ý: `STOP` có bỏ qua task còn lại trong hàng đợi hay không?)
- Nếu pool được tạo với `nThreads = 0` (không có worker nào), `tryTerminate()` sẽ hành xử ra sao ngay từ constructor? Điều gì cần được gọi thêm để pool đó không bị kẹt mãi ở `RUNNING`? (Đây chỉ là câu hỏi tư duy — v3 không xử lý trường hợp này, giống hầu hết bài tập trước đó chỉ tập trung vào đúng cơ chế đang học.)
