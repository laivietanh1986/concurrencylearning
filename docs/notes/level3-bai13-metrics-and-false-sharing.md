# Bài 13 — Metrics không được trở thành nút nghẽn

## Ý tưởng cốt lõi

Một thread pool thật cần expose các chỉ số vận hành (`activeWorkers`, `queueDepth`, `submitted/completed/failed/rejected`) để quan sát được — nhưng bản thân việc *đo lường* không được phép làm chậm hệ thống đang được đo. Bài này có hai phần: (1) gắn `PoolStats` vào `NanoPool`, và (2) hai thí nghiệm phần cứng lý giải vì sao chọn sai kiểu counter (hoặc sai cách bố trí bộ nhớ) có thể biến chính công cụ đo lường thành nút nghẽn.

## `PoolStats` — snapshot bất biến

```java
public record PoolStats(int activeWorkers, int queueDepth, long submitted, long completed, long failed, long rejected) {}
```

Hai loại chỉ số khác nhau về bản chất:
- **Gauge** (`activeWorkers`, `queueDepth`) — giá trị tức thời, đọc trực tiếp từ `AtomicInteger`/`queue.size()` tại thời điểm gọi `stats()`, có thể thay đổi ngay sau đó.
- **Counter cộng dồn** (`submitted`, `completed`, `failed`, `rejected`) — chỉ tăng, không bao giờ giảm, dùng `LongAdder` thay vì `AtomicLong`.

`submitted` được thiết kế để đếm **mọi lần gọi `execute()`**, bất kể kết quả sau đó — nhờ vậy luôn có bất biến: `submitted == completed + failed + rejected + (số task đang chờ hoặc đang chạy dở)`. Test `PoolStatsTest` đo được đúng: sau 4 lần `execute()` (1 task chặn, 1 task rỗng, 1 task ném exception, 1 lần bị `ABORT`), `submitted=4`; sau khi mọi thứ chạy xong: `completed=2, failed=1, rejected=1` — cộng lại đúng bằng 4.

## Thí nghiệm 1 — `AtomicLong` vs `LongAdder` ở 1/4/16/32 thread

Cả hai đều "đúng" về mặt kết quả (không mất phép cộng nào). Khác biệt là **cách chúng chịu tải tranh chấp (contention)**:
- `AtomicLong.incrementAndGet()` — một biến `long` volatile duy nhất, mọi thread CAS trực tiếp lên cùng một địa chỉ. Khi nhiều thread cùng lúc, phần lớn các lần CAS thất bại (giá trị đã bị thread khác thay đổi) và phải retry — tranh chấp tăng tuyến tính (thậm chí siêu tuyến tính) theo số thread.
- `LongAdder` — bên trong giữ một mảng các "Cell" (mỗi Cell là một `long` có padding riêng để tránh false sharing — xem thí nghiệm 2). Khi tranh chấp cao, các thread khác nhau tự động được phân tán (striping) sang các Cell khác nhau thay vì cùng CAS một chỗ; `sum()` chỉ cộng dồn tất cả Cell lại khi cần đọc.

Đo được thực tế (3 triệu lần increment/thread):

| Số thread | `AtomicLong` | `LongAdder` |
|---|---|---|
| 1  | 17–23ms    | 28–34ms    |
| 4  | 162–192ms  | 65–77ms    |
| 16 | 1139–1251ms| 79–97ms    |
| 32 | 2123–2379ms| 147–213ms  |

Ba điều đáng chú ý:
1. **Ở 1 thread, `LongAdder` chậm hơn `AtomicLong`** (34ms so với 22ms) — đúng như lý thuyết dự đoán: không có tranh chấp thì cơ chế Cell/striping của `LongAdder` chỉ thêm một lớp indirection thừa, không mang lại lợi ích gì.
2. **Từ 4 thread trở lên, `LongAdder` đã nhanh hơn rõ rệt** (65–97ms so với 162–1251ms) — và khoảng cách **nới rộng theo cấp số nhân** khi số thread tăng, vì `AtomicLong` degrade gần như tuyến tính theo tranh chấp trong khi `LongAdder` gần như không đổi.
3. **Ở 32 thread, chênh lệch lên tới ~14–16 lần** (2123–2379ms so với 147–213ms) — đây chính xác là kịch bản `PoolStats` của một pool thật: nhiều worker thread đồng thời gọi `completedCount.increment()` mỗi khi xong một task — càng nhiều worker, `AtomicLong` càng trở thành nút nghẽn thật sự, trong khi `LongAdder` gần như "vô hình" về mặt chi phí.

**Kết luận thực dụng**: dùng `LongAdder` cho các counter bị ghi bởi nhiều thread đồng thời với tần suất cao (như `submitted`/`completed`/`failed`/`rejected` của một pool bận rộn); dùng `AtomicLong` khi chỉ có 1 hoặc rất ít thread ghi, hoặc khi cần đọc giá trị chính xác tại một thời điểm với chi phí thấp và không cần cộng dồn nhiều Cell.

## Thí nghiệm 2 (phụ) — false sharing: `long[16]` sát nhau vs. có padding

Đây là một hiện tượng **hoàn toàn không phải bug logic** — mỗi thread chỉ ghi vào đúng một ô nó sở hữu trong mảng, không có race condition nào về mặt giá trị. Vấn đề nằm ở **phần cứng**: CPU không nạp/ghi từng byte riêng lẻ vào cache, mà theo từng khối 64 byte gọi là **cache line**. Nếu 8 số `long` (8 byte mỗi số) của 8 thread khác nhau nằm gọn trong cùng một cache line 64 byte, thì mỗi lần một thread ghi vào ô của nó, cache line đó bị đánh dấu "dơ" (invalidated) trên **mọi core khác** đang giữ bản sao của chính cache line đó — kể cả khi các core đó chỉ đang đọc/ghi những ô hoàn toàn khác trong cùng line. Đây gọi là **false sharing**: các thread "chia sẻ" một cache line một cách giả tạo, dù dữ liệu logic của chúng độc lập hoàn toàn.

```java
long[] counters = new long[8];       // 8 thread, moi thread ghi counters[i] - sat nhau, cung it nhat 1 cache line
long[] padded   = new long[8 * 8];   // moi thread ghi padded[i * 8] - cach nhau 64 byte = 1 cache line rieng
```

Đo được (8 thread, 200 triệu lần tăng mỗi thread):

| Cách bố trí | Thời gian |
|---|---|
| Không padding (`counters[i]`, sát nhau) | 892–1848ms |
| Có padding (`padded[i*8]`, mỗi ô 1 cache line riêng) | 483–580ms |

Chênh lệch **1.7–3.5 lần** chỉ bằng cách thay đổi khoảng cách giữa các ô nhớ trong một mảng — không đổi thuật toán, không đổi số phép tính, không thêm đồng bộ hoá nào. Toàn bộ chi phí phụ trội đến từ giao thức nhất quán cache (cache coherency protocol, ví dụ MESI) phải liên tục đồng bộ lại cùng một cache line giữa các core, dù về logic chương trình chẳng có gì cần đồng bộ cả.

## Vì sao `LongAdder` không bị false sharing (mà một mảng `AtomicLong[]` tự viết thì có thể)

Đây là mối liên hệ trực tiếp giữa hai thí nghiệm: bên trong `LongAdder`, mỗi `Cell` trong mảng striping **tự nó đã được padding** (annotated `@sun.misc.Contended`, hoặc trong các phiên bản JDK khác nhau là một class có các field đệm thủ công) để đảm bảo mỗi `Cell` chiếm trọn một cache line riêng — chính xác là kỹ thuật thí nghiệm 2 vừa đo được. Đây là lý do vì sao `LongAdder` không chỉ thắng nhờ "phân tán CAS ra nhiều biến" mà còn thắng nhờ đã tự khử false sharing giữa các biến đó — nếu tự viết một mảng "striped counter" mà không padding từng phần tử, phần lớn lợi ích của striping sẽ bị chính false sharing giữa các phần tử liền kề ăn mất.

## Câu hỏi tự kiểm tra
- Nếu `PoolStats` chỉ cần đọc chính xác 1 lần duy nhất khi debug (không phải liên tục hàng nghìn lần/giây bởi nhiều worker), việc đổi `LongAdder` thành `AtomicLong` có còn hợp lý không? Đánh đổi nằm ở đâu?
- Tại sao thí nghiệm false sharing vẫn cho kết quả rõ rệt ngay cả khi số thread (8) có thể vượt quá số core vật lý của máy đang chạy test? (Gợi ý: hiện tượng này có phụ thuộc vào việc các thread chạy đúng lúc trên các core khác nhau hay không, hay chỉ cần luân phiên chạy trên các core khác nhau theo thời gian?)
