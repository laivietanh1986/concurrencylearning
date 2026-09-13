# Bài 12 — NanoPool v4: co giãn kích thước (elastic sizing) + rejection policy

## Ý tưởng cốt lõi

Một pool cố định N thread (v1–v3) không phù hợp với tải không đều: quá nhỏ thì phí thời gian rảnh lúc ít việc, quá lớn thì lãng phí thread lúc ít việc. v4 thêm 4 tham số để pool tự co giãn:

- **`coreSize`** — số thread luôn tồn tại (được tạo sẵn ngay lúc khởi tạo pool, không bao giờ tự chết dù rảnh).
- **`maxSize`** — số thread tối đa được phép tồn tại cùng lúc.
- **`queueCapacity`** — sức chứa hàng đợi (như các bài trước).
- **`keepAliveMs`** — thời gian một thread "vượt core" (extra) được phép rảnh trước khi tự kết thúc.

## Thứ tự xử lý task trong `execute()` — và vì sao thứ tự đó quan trọng

```java
public void execute(Runnable task) {
    if (isShutdown()) throw new RejectedExecutionException(...);

    if (isRunning() && offerToQueue(task)) {   // (1) uu tien bo vao hang doi
        ...
        return;
    }
    if (tryAddWorker(task, false)) {           // (2) hang doi day -> thu tao them extra thread
        return;
    }
    applyRejectionPolicy(task);                // (3) da het ca hai -> ap dung chinh sach
}
```

Đây **đúng thứ tự** mà `ThreadPoolExecutor` thật của JDK dùng (core → queue → extra thread → reject, ở đây core threads được tạo sẵn từ lúc khởi tạo pool nên bước "tạo core" không còn xuất hiện trong `execute()` nữa — chỉ còn bước (1) và (2) là quan trọng để học). Điểm mấu chốt của thứ tự này: **pool chỉ tạo thêm thread mới KHI hàng đợi đã đầy**, không phải khi có "nhiều task đang chờ" nói chung.

### Vì sao thứ tự này có thể là lựa chọn sai — đo được bằng số liệu thật

Đây chính là cái bẫy nổi tiếng của `ThreadPoolExecutor` mà rất nhiều lập trình viên Java bị dính: **nếu `queueCapacity` lớn (hoặc không giới hạn), pool gần như không bao giờ vượt quá `coreSize`, dù `maxSize` được đặt cao hơn rất nhiều** — vì điều kiện duy nhất để tạo thread vượt core là "hàng đợi đã đầy", mà một hàng đợi lớn thì rất khó đầy.

Đo được (`largeQueueCapacityPreventsGrowingPastCoreSizeEvenUnderSustainedLoad`): pool với `coreSize=2, maxSize=8, queueCapacity=100`; nộp 20 task chạy dài (chặn vô hạn) — nhiều gấp 10 lần `coreSize`, ít hơn `maxSize` × các mức nhưng vẫn nằm gọn trong `queueCapacity`. Kết quả đo: `currentPoolSize()` vẫn giữ nguyên **2** trong suốt — pool **không bao giờ** chạm tới `maxSize=8` dù có 18 task đang xếp hàng chờ, vì cả 18 task đó đều "vừa" trong hàng đợi 100 chỗ, không có task nào từng thấy hàng đợi đầy để kích hoạt việc tạo extra thread.

**Bài học thực tế**: nếu mục tiêu là "pool phải mở rộng để xử lý tải tăng đột biến", đặt `queueCapacity` lớn là phản tác dụng — nó khiến task xếp hàng chờ lâu hơn thay vì kích hoạt thêm thread. Cách khắc phục kinh điển (dùng trong thực tế Java): dùng hàng đợi rất nhỏ gần như "chuyển giao trực tiếp" (như `SynchronousQueue`, capacity xem như 0) để hàng đợi gần như luôn "đầy" ngay khi core bận, buộc pool phải quyết định tạo thread mới hoặc reject ngay lập tức, phản ánh đúng tải thực tế thay vì che giấu nó sau một hàng đợi lớn.

## Extra thread phải **chết thật** sau `keepAliveMs`

```java
private Runnable getTask() {
    boolean timedOut = false;
    while (true) {
        ...
        boolean isExtra = workerCountOf(ctl.get()) > coreSize;
        if (isExtra && timedOut) {
            if (ctl.compareAndSet(c, c - 1)) return null;  // that su ket thuc thread
            continue;
        }
        Runnable task = queue.poll(isExtra ? keepAliveMs : IDLE_POLL_MS, TimeUnit.MILLISECONDS);
        if (task != null) return task;
        timedOut = isExtra;
    }
}
```

Điểm quan trọng: **không có thread nào được "gắn nhãn" cố định là core hay extra** — tính "extra" chỉ là một câu hỏi tại-thời-điểm-hiện-tại: "tổng số thread đang sống có nhiều hơn `coreSize` không?". Bất kỳ thread nào (kể cả một trong những thread được tạo từ đầu) cũng có thể là thread "thừa ra" và tự kết thúc, miễn tổng số hiện tại đang vượt `coreSize`. Đây đúng là kỹ thuật thật của `ThreadPoolExecutor.getTask()`.

`ctl.compareAndSet(c, c - 1)` là bước quyết định: nếu hai thread cùng lúc "hết hạn keep-alive" và cùng muốn tự kết thúc, chỉ một trong hai thắng CAS — thread thua phải `continue` để đọc lại `ctl` mới nhất và tự quyết định lại (có thể lúc này pool đã không còn "thừa" nữa, không cần thoát).

Đo được (`extraThreadsBeyondCoreActuallyDieAfterKeepAliveExpires`): pool `coreSize=1, maxSize=4, keepAliveMs=150`; ép pool tăng lên đúng 4 thread bằng cách khoá chặt 1 thread core và làm đầy hàng đợi 2 chỗ rồi nộp thêm 3 task chặn (buộc tạo 3 extra thread, tổng = 4 = `maxSize`) — thử nộp thêm một task nữa lập tức bị `RejectedExecutionException` (đã bão hoà thật sự). Sau khi giải phóng tất cả và đợi qua `keepAliveMs`, `currentPoolSize()` giảm dần và hội tụ đúng về **1** (`coreSize`) — chứng minh extra thread không chỉ "ngừng nhận task" mà **thật sự chấm dứt** (không phải một trạng thái "ngủ đông" giả).

## Ba rejection policy — khi pool đã bão hoà thật sự (hàng đợi đầy VÀ đã đạt `maxSize`)

```java
public enum RejectionPolicy { ABORT, CALLER_RUNS, BLOCK_UNTIL_SPACE }
```

- **`ABORT`** — ném `RejectedExecutionException` ngay. Đơn giản, tường minh, nhưng đẩy toàn bộ trách nhiệm xử lý lỗi cho người gọi.
- **`CALLER_RUNS`** — chạy task **ngay trên thread đang gọi `execute()`/`submit()`**, không đưa vào pool nữa. Đo được (`callerRunsPolicyExecutesOnTheCallingThreadAsBackpressure`): tên thread mà task thực thi trên đó trùng khớp chính xác với tên thread gọi `execute()` (thread test, không phải bất kỳ `nanopool-*` nào).
- **`BLOCK_UNTIL_SPACE`** — chặn thread gọi bằng `queue.put()` (blocking) cho tới khi có chỗ trống. Đo được (`blockUntilSpacePolicyBlocksTheCallerUntilRoomIsAvailable`): thread gọi `execute()` bị treo thật sự (`producer.isAlive()==true` sau 300ms chờ) cho tới khi một task khác hoàn thành và nhường chỗ, sau đó `execute()` mới trả về bình thường.

### Vì sao `CALLER_RUNS` (và `BLOCK_UNTIL_SPACE`) là một dạng backpressure

Cả hai chính sách này có chung một đặc điểm: **chúng làm chậm chính "nguồn phát sinh task"**, không phải bằng cách từ chối, mà bằng cách buộc bên gửi task phải "trả giá" trực tiếp cho việc hệ thống đang quá tải:
- `CALLER_RUNS`: bên gửi bị "đánh thuế" đúng bằng thời gian chạy của task đó — nếu bên gửi đang gửi task trong một vòng lặp chặt (như một producer liên tục), vòng lặp đó tự động chậm lại đúng bằng tốc độ pool có thể tiêu thụ, không cần một cơ chế điều tiết (throttling) riêng biệt nào khác.
- `BLOCK_UNTIL_SPACE`: tương tự nhưng "đánh thuế" bằng thời gian chờ thay vì thời gian chạy — bên gửi không làm gì cả (không tốn CPU chạy task lạ), chỉ đơn giản không được phép gửi nhanh hơn tốc độ tiêu thụ của pool.

Ngược lại, `ABORT` **không phải** backpressure — nó không làm chậm bên gửi lại, chỉ đơn thuần từ chối và để bên gửi tự quyết định (thử lại, bỏ qua, ghi log...). Đây là lý do vì sao trong các hệ thống thực tế xử lý luồng dữ liệu liên tục (streaming, ingestion), `CALLER_RUNS`/`BLOCK_UNTIL_SPACE` thường được ưu tiên hơn `ABORT` khi mục tiêu là "không để hệ thống sụp đổ dưới tải cao" thay vì "phát hiện và báo lỗi ngay khi quá tải".

## Câu hỏi tự kiểm tra
- Nếu `queueCapacity` được đặt bằng 1 thay vì 100 trong bài học "hàng đợi lớn ngăn pool mở rộng", pool có còn giữ nguyên ở `coreSize` khi nộp 20 task chạy dài không? Thử tính tay xem tại task thứ mấy pool bắt đầu tạo extra thread.
- `CALLER_RUNS` có an toàn để dùng khi `coreSize=0` và task đang chạy trên thread gọi lại tiếp tục gọi `pool.execute()` một lần nữa (đệ quy) không? Điều gì có thể xảy ra nếu pool luôn bão hoà? (Đây là tiền đề trực tiếp cho bài học về deadlock ở bài 14.)
