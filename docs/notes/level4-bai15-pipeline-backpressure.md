# Bài 15 — Pipeline nhiều tầng + backpressure thật

## Kiến trúc: 3 tầng, mỗi tầng một `NanoPool` + hàng đợi riêng

```
nguồn (String "0".."999999")
   │  submit() -> BLOCK neu day
   ▼
[parse]  Integer.parseInt        BoundedTaskQueue riêng + NanoPool riêng
   │  submit() -> BLOCK neu day
   ▼
[transform]  x * 2               BoundedTaskQueue riêng + NanoPool riêng
   │  submit() -> BLOCK neu day
   ▼
[sink]  ghi ket qua              BoundedTaskQueue riêng + NanoPool riêng
```

Khác với `NanoPool.execute()` trước giờ (mỗi lần một task ngắn), mỗi worker của một tầng ở đây chạy **một vòng lặp tiêu thụ vô hạn** (`consumeLoop`): `take()` một item từ hàng đợi riêng của tầng, xử lý, rồi `submit()` (tức `put()` — **block khi đầy**) kết quả sang hàng đợi của tầng kế tiếp. Xem [`PipelineStage.java`](../../src/main/java/com/nanopool/level4/PipelineStage.java).

## Backpressure lan ngược về tận nguồn

Vì mỗi `submit()` giữa các tầng là `BoundedTaskQueue.put()` (chặn khi đầy, không phải "ném đi nếu đầy"), một khi tầng cuối (`sink`) xử lý chậm hơn tốc độ đưa vào, chuyện xảy ra là:

1. Hàng đợi của `sink` đầy.
2. Worker của `transform` bị **chặn** ngay trong lúc gọi `sink.submit(...)` — nó không rảnh để lấy item tiếp theo từ hàng đợi của chính `transform` nữa.
3. Hàng đợi của `transform` vì thế cũng đầy dần.
4. Worker của `parse` bị chặn khi gọi `transform.submit(...)`.
5. Hàng đợi của `parse` đầy — và **chính thread nguồn** (thread đang gọi `parse.submit()` trong vòng lặp) bị chặn.

Không có tầng nào "biết" về sink — áp lực tự lan ngược thuần túy qua việc mỗi `put()` chặn khi đầy. Đo được (`PipelineBackpressureTest`, 40 item, sink cố tình chậm 15ms/item, mỗi hàng đợi chỉ chứa 2 item):

```
MEASURED backpressure: submit 40 item mất 479-493ms trong khi sink chậm 15ms/item (buffer mỗi tầng chỉ 2)
```

Nếu hàng đợi vô hạn (không có backpressure thật), việc submit 40 item từ nguồn sẽ gần như tức thời (vài mili-giây), bất kể sink chậm cỡ nào — nguồn cứ "đẩy" và bộ nhớ cứ phình ra. Ở đây thì ngược lại: **thời gian submit đo được tại nguồn tỉ lệ thuận với tốc độ tầng chậm nhất**, dù nguồn không hề gọi gì tới sink cả. Đó chính là ý nghĩa của "backpressure lan ngược" — hệ thống tự động làm chậm đầu vào để khớp với khả năng xử lý ở đầu ra, mà không cần một cơ chế điều phối trung tâm nào.

## Vì sao hàng đợi không giới hạn (unbounded) ở bất kỳ tầng nào cũng là lỗi

Nếu một trong ba `BoundedTaskQueue` ở trên được thay bằng hàng đợi không giới hạn dung lượng:

- **Backpressure biến mất khỏi tầng đó trở lên** — tầng phía trước hàng đợi không giới hạn sẽ không bao giờ bị chặn nữa, dù tầng phía sau chậm cỡ nào. Áp lực thực sự vẫn còn (tầng cuối vẫn chậm), nhưng nó bị "giấu" trong bộ nhớ dưới dạng một hàng đợi ngày càng phình to, thay vì lộ ra ở nguồn nơi người vận hành có thể quan sát và phản ứng (chậm nguồn lại, cảnh báo, scale thêm sink...).
- **Rủi ro `OutOfMemoryError`** — nếu tải đầu vào duy trì cao hơn tốc độ xử lý trong một khoảng thời gian đủ dài, hàng đợi không giới hạn sẽ tăng vô hạn cho tới khi hết heap, sập toàn bộ tiến trình thay vì chỉ làm chậm một cách có kiểm soát.
- **Latency mất kiểm soát** — mỗi item mới vào cuối một hàng đợi dài sẽ phải chờ xử lý hết toàn bộ hàng đợi phía trước nó; hàng đợi càng phình, độ trễ mỗi item càng tăng không giới hạn, dù throughput trông vẫn "ổn" trên biểu đồ tổng.

Vì vậy, việc hàng đợi bị **chặn khi đầy** (thay vì phình ra) không phải là một khiếm khuyết cần khắc phục — đó chính là cơ chế duy nhất khiến "chỗ chậm nhất" của hệ thống trở nên hữu hình và có thể xử lý.

## Little's Law: `L = λ × W` để chọn queue depth có căn cứ

Little's Law là một định luật bảo toàn cho hệ thống ở trạng thái ổn định (dài hạn): **số item trung bình đang nằm trong một tầng** (`L`, gồm cả đang chờ trong hàng đợi lẫn đang được xử lý) **bằng** **tốc độ đến trung bình** (`λ`, item/giây) **nhân với thời gian trung bình một item nằm trong tầng đó** (`W`, giây).

`LittlesLawTest` đo cả ba đại lượng này **độc lập với nhau** trên một tầng `sink` bị bão hòa cố tình (service = 5ms/item, `queueCapacity=20`, nguồn đẩy nhanh hết mức trong 3 giây):

- `L` đo bằng cách **lấy mẫu** `queueDepth()` mỗi 1ms rồi lấy trung bình.
- `λ` đo bằng `processed / thời_gian_chạy` (thông lượng thực tế đạt được, bị chặn bởi tốc độ service chứ không phải tốc độ nguồn).
- `W` đo bằng cách gắn timestamp lúc `submit()` vào từng item, rồi tính `thời_điểm_xử_lý_xong − submitNanos`, lấy trung bình.

Đo được:

```
MEASURED Little's Law: L=19.95 lambda=174-177 item/s W=0.122-0.124s lambda*W=21.6-21.7 tỷ lệ L/(lambda*W)=0.92-0.93
```

`L` đo trực tiếp (~19.95, gần sát `queueCapacity=20` vì sink luôn bão hòa) khớp với `λ × W` tính từ hai đại lượng đo độc lập khác, sai số chỉ ~7-8% (test chấp nhận sai số 30% để không flaky, nhưng thực đo ổn định quanh 0.92). Ba đại lượng này được đo bằng ba cơ chế hoàn toàn khác nhau (lấy mẫu định kỳ / đếm cộng dồn / timestamp), nên việc chúng khớp nhau không phải trùng hợp — đó là bằng chứng thực nghiệm cho định luật.

**Ứng dụng ngược lại — chọn `queueCapacity`:** nếu biết trước `λ` (tải dự kiến, item/giây) và muốn giới hạn thời gian một item phải chờ trong hàng đợi không quá `W_max` giây (một SLA latency), Little's Law cho công thức trực tiếp: `queueCapacity ≈ λ × W_max`. Ví dụ ở trên: muốn `W_max = 0.1s` với `λ = 175` item/s thì `queueCapacity ≈ 17.5 ≈ 20` — đúng bằng giá trị đã chọn để đo. Chọn `queueCapacity` lớn hơn nhiều so với con số này không giúp tăng throughput (throughput vẫn bị chặn bởi tốc độ service `1/serviceTime`), mà chỉ kéo dài `W` — biến hàng đợi thành một "unbounded buffer trá hình" ở quy mô nhỏ hơn.

## Shutdown có thứ tự bằng poison pill — so với shutdown bằng interrupt

### Poison pill: đi theo đúng con đường của dữ liệu thật

Để dừng một tầng có `N` worker, cần đúng `N` "viên thuốc độc" (`PipelineStage.POISON_PILL`, một sentinel `Object` dùng chung, so sánh bằng `==`) — mỗi worker `take()` được đúng 1 viên rồi tự thoát vòng lặp. Vì viên thuốc độc được `put()` vào **cùng hàng đợi FIFO** với dữ liệu thật, nó chỉ "tới lượt" **sau khi mọi item thật đứng trước nó trong hàng đợi đã được xử lý xong**. Worker cuối cùng trong số `N` worker nhận đủ thuốc độc (đếm bằng `AtomicInteger`, kỹ thuật "người cuối tắt đèn") mới là người chuyển tiếp thuốc độc xuống tầng kế (đúng số lượng bằng concurrency của tầng kế, có thể khác `N`) và gọi `shutdown()` (không phải `shutdownNow()`) trên `NanoPool` lưu trữ chính nó.

Đo được (`PoisonPillShutdownTest`, 500 item qua đủ 3 tầng rồi mới `poisonAll()` ở tầng `parse`):

```
parse.processedCount() == 500
transform.processedCount() == 500
sink.processedCount() == 500, sinkResults.size() == 500  (không trùng, không thiếu)
```

Mọi item đều tới đích đúng một lần, dù ta ra lệnh dừng ngay khi vẫn còn item trong pipeline — vì lệnh dừng "chờ lượt" giống hệt một item thật.

### Interrupt: dừng ngay lập tức, không quan tâm gì đang dở dang

`shutdownNowAbandoningInFlightWork()` gọi thẳng `NanoPool.shutdownNow()`, tức `interrupt()` vào toàn bộ worker thread ngay lập tức — bất kể worker đang `take()` chờ item mới, đang kẹt trong `Thread.sleep()` mô phỏng I/O chậm, hay đang `submit()` (chặn) sang tầng kế. Khác hẳn thuốc độc (phải xếp hàng), interrupt **không đi qua hàng đợi** — nó cắt ngang bất kể vị trí.

Đo được (`InterruptShutdownLosesItemsTest`, 200 item, sink chậm 20ms/item, chờ đúng lúc sink bắt đầu xử lý item đầu tiên rồi interrupt ngay ba tầng):

```
MEASURED interrupt-shutdown item loss: sink nhận được 2/200 item
```

198/200 item bị mất vĩnh viễn — không phải do lỗi logic, mà là hệ quả tất yếu của việc interrupt không cho bất kỳ item nào đang "dở dang" (trong hàng đợi hoặc đang xử lý) cơ hội hoàn tất hay được ghi nhận là "chưa xử lý xong, cần xử lý lại". Đây chính là lý do các hệ thống nhắn tin thật (Kafka, JMS...) không bao giờ dùng interrupt để dừng consumer một cách "sạch" — chúng luôn có một cơ chế tương đương thuốc độc hoặc một điểm đánh dấu offset đã xử lý.

## Throughput end-to-end thật với 1.000.000 item

`PipelineThroughputBenchmarkTest` chạy đúng 1.000.000 item qua cả 3 tầng, mỗi tầng 4 worker, **không có độ trễ giả lập** (đo tốc độ tối đa mà kiến trúc này đạt được):

```
MEASURED pipeline stage=parse     count=1000000  p50=0us  p99=0us
MEASURED pipeline stage=transform count=1000000  p50=0us  p99=0us
MEASURED pipeline stage=sink      count=1000000  p50=0us  p99=0us
MEASURED pipeline end-to-end: 1.000.000 item trong 787-916ms = ~1.09-1.27 triệu item/s
```

`p50`/`p99` ở đây đo thời gian **thực thi hàm xử lý** (`processFn.apply`) của từng item — gần như bằng 0 vì `parseInt`/nhân đôi/cộng dồn là các phép tính cực rẻ; chúng **không** đo thời gian item phải chờ trong hàng đợi (độ trễ do chờ hàng đợi đã được đo riêng, trực tiếp, ở phần Little's Law bên trên qua `W`). Đúng đắn cũng được xác nhận: tổng tất cả kết quả ở sink khớp chính xác với tổng kỳ vọng tính độc lập bằng vòng lặp thường — không có item nào bị tính sai hay xử lý trùng dù chạy song song 4 worker/tầng.

## Câu hỏi tự kiểm tra
- Nếu tăng `queueCapacity` của `sink` trong `PipelineBackpressureTest` từ 2 lên 1000 mà giữ nguyên `sinkDelayMs=15`, thời gian submit 40 item đo được ở nguồn sẽ thay đổi thế nào? Việc "thời gian submit không đổi nhiều" hay "giảm mạnh" nói lên điều gì về việc buffer lớn có thực sự giải quyết được vấn đề sink chậm hay không?
- `LittlesLawTest` đo `L` bằng lấy mẫu `queueDepth()` mỗi 1ms. Nếu tăng chu kỳ lấy mẫu lên 50ms (thưa hơn nhiều so với `serviceDelayMs=5ms`), kết quả `L` đo được có còn đáng tin không? Tại sao chu kỳ lấy mẫu cần nhỏ hơn nhiều so với thời gian một item ở lại trong hệ thống?
