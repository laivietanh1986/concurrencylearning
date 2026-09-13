# Bài 4 — Vẽ happens-before cho bài 1, 2, 3

## Nhắc lại: các happens-before (HB) edge có sẵn trong JMM

Chỉ có chừng này loại edge cơ bản (mọi guarantee khác đều suy ra từ chúng + **tính bắc cầu**: nếu A→B và B→C thì A→C):

| Edge | Ý nghĩa |
|---|---|
| **Program order (po)** | Trong CÙNG một thread, lệnh đứng trước happens-before lệnh đứng sau. |
| **Monitor lock/unlock (mo)** | `unlock` một monitor happens-before `lock` **kế tiếp** trên **cùng monitor đó** (bởi thread bất kỳ). |
| **Volatile write/read (vo)** | Ghi một biến `volatile` happens-before lần đọc **kế tiếp** biến đó thấy được giá trị vừa ghi. |
| **Thread.start()** | Lệnh gọi `start()` happens-before hành động đầu tiên trong thread mới. |
| **Thread.join()** | Hành động cuối cùng trong thread con happens-before chỗ gọi `join()` trả về ở thread cha. |

Chú ý: nếu giữa hai action **không** có đường nối nào (trực tiếp hoặc bắc cầu) qua các edge này → JMM **không đảm bảo gì cả**, kể cả thứ tự lẫn visibility. Không có edge = hợp pháp để thấy giá trị cũ, thấy reorder, thấy torn object.

---

## Bài 1 — `RaceLab` (lost update)

```
Thread T1                          Thread T2
----------                          ----------
r1 = counter   (read)               r2 = counter   (read)
r1 = r1 + 1                         r2 = r2 + 1
counter = r1   (write)              counter = r2   (write)
```

### `int` thường / `volatile int`
Không có edge nào nối "T1 write" với "T2 read" theo đúng nghĩa loại trừ lẫn nhau — kể cả với `volatile`, mỗi thao tác đọc/ghi RIÊNG LẺ có edge (vo), nhưng **cụm 3 bước đọc-sửa-ghi không được bọc trong một edge duy nhất**. T2 có thể đọc counter **giữa lúc** T1 đã đọc nhưng chưa ghi:
```
T1: r1 = counter (=5)
                    T2: r2 = counter (=5)   <-- không có edge nào ngăn điều này
T1: counter = 6
                    T2: counter = 6         <-- mất 1 lần tăng
```
→ Đây là lý do `volatile` không cứu được: **nó thêm edge cho từng read/write đơn lẻ, nhưng bài toán cần edge cho cả cụm 3 bước.**

### `synchronized`
```
T1: lock(m) -> r=counter -> counter=r+1 -> unlock(m)
                                              │ mo
                                              ▼
                              T2: lock(m) -> r=counter -> counter=r+1 -> unlock(m)
```
`unlock` của T1 **happens-before** `lock` kế tiếp của T2 trên cùng monitor `m`. Vì mọi thread đều tranh cùng một `m`, JMM tạo ra **một chuỗi tổng thứ tự** (total order) xuyên suốt tất cả 8×100.000 lần tăng — không có khoảng hở nào giữa đọc và ghi của bất kỳ ai, vì toàn bộ cụm đọc-sửa-ghi nằm trọn trong một khối `lock…unlock`.

### `AtomicInteger`
Không dùng `synchronized`, nhưng `compareAndSet`/`incrementAndGet` thành công có **hiệu ứng bộ nhớ tương đương một cặp volatile write + volatile read trên chính giá trị đó** (đặc tả `java.util.concurrent.atomic`). Vòng lặp CAS bên trong đảm bảo: nếu retry, nó đọc lại giá trị **mới nhất** (nhờ hiệu ứng volatile-like) trước khi thử ghi lại — nên không bao giờ ghi đè lên một update đã bị bỏ lỡ.

---

## Bài 2 — `VisibilityLoop` (vòng lặp không dừng)

```
Main thread                         Worker thread
------------                         ------------
worker.start()  ─────ts─────────▶   (vòng lặp: while (!stop) {})
Thread.sleep(1000)
stop = true      (??? edge nào ???)
```

### `stopPlain` (thường)
`worker.start()` chỉ tạo edge với **hành động đầu tiên** trong worker (tức thời điểm worker bắt đầu chạy) — nó **không** che phủ việc main ghi `stop = true` **sau đó** (sau khi `start()` đã return và main ngủ thêm 1 giây). Giữa "main ghi stop=true" và "worker đọc stop" **không có edge nào** → JMM cho phép worker không bao giờ thấy write này → JIT tự do hoist → treo mãi mãi (đã đo được ở bài 2).

### `stopPlain` + `println`
Vẫn **không có HB edge nào** giữa main và worker được thêm vào! `println` chỉ ngăn JIT tối ưu (compiler không dám hoist qua một lời gọi method không minh bạch), đây là hiệu ứng "tình cờ" của cách HotSpot cài đặt hiện tại — **không phải một guarantee của đặc tả JMM**. Nói cách khác: bug vẫn tồn tại về mặt lý thuyết, chỉ là bản build HotSpot hiện tại không kích hoạt nó khi có `println`.

### `stopVolatile`
```
Main:   stop = true      (volatile write)
                              │ vo
                              ▼
Worker: r = stop  (volatile read, thấy true)
```
Đây là edge thật, được JMM đảm bảo trên **mọi** JVM/kiến trúc, không phụ thuộc cách JIT cài đặt.

---

## Bài 3 — `UnsafePublication`

### `unsafeInstance` (reference thường + field thường)
```
Writer:  h.a=1 (po) → h.b=2 (po) → h.label="ready" (po) → unsafeInstance = h   (write thường)
Reader:                                                     unsafeInstance      (read thường, ??? )
```
Không một edge nào nối 2 cột. Hai bug chồng lên nhau:
- Có thể reader không bao giờ thấy `unsafeInstance` đổi (giống hệt bài 2 — đo được 946-897/1000 lần "not observed").
- **Nếu có thấy**, JMM cho phép thấy `h.a=0` (chưa thấy write) — chỉ là x86 TSO tình cờ không cho tái sắp xếp store-store nên ta đo được `torn=0`.

### `finalFieldInstance` (reference thường + field `final`)
```
Writer:  h.a=1(final) → h.b=2(final) → h.label="ready"(final) → [constructor kết thúc: FREEZE]
Reader:  finalFieldInstance  (read thường, có thể không thấy — vẫn là bug bài 2)
         nếu thấy reference → h.a, h.b, h.label ĐẢM BẢO đúng (JLS 17.5, không cần HB edge cổ điển)
```
Đây là một loại guarantee **khác họ** với 5 edge liệt kê ở đầu bài — nó không phải "action A happens-before action B", mà là một **rào cản đóng băng (freeze barrier)** gắn liền với việc constructor kết thúc: bất kỳ thread nào nhìn thấy reference (dù bằng cách nào, kể cả một read thường) đều thấy field final đã freeze. Cái nó KHÔNG giải quyết là việc reference có được nhìn thấy hay không — đó vẫn là bug bài 2 nếu reference publish qua field thường.

### `volatileInstance`
```
Writer:  h.a=1(po) → h.b=2(po) → h.label="ready"(po) → volatileInstance = h   (volatile write)
                                                              │ vo
                                                              ▼
Reader:                                                 volatileInstance      (volatile read, thấy h)
                                                              │ po (sau khi đọc xong biến volatile)
                                                              ▼
                                                         h.a, h.b, h.label     (đọc field bên trong)
```
Bắc cầu: `h.a=1` **po→** `volatileInstance=h` **vo→** `read volatileInstance` **po→** `read h.a`. Toàn bộ chuỗi này là HB thật, đảm bảo trên mọi kiến trúc → không bao giờ torn, không bao giờ miss (đo được 500/500).

### `syncInstance`
```
Writer:  lock(m) → h.a=1,h.b=2,h.label="ready" → syncInstance=h → unlock(m)
                                                                      │ mo
                                                                      ▼
Reader:                                              lock(m) → read syncInstance → unlock(m)
```
Giống bài 1's biến thể `synchronized`: `unlock` của writer **mo→** `lock` của reader trên cùng `m`, cộng với `po` hai đầu → toàn bộ write trong constructor được nhìn thấy.

---

## Bảng tổng hợp: edge nào có, edge nào thiếu

| Bài | Biến thể | Edge nối 2 thread | Kết quả |
|---|---|---|---|
| 1 | `int`/`volatile int` | không có edge cho cả cụm read-modify-write | mất update |
| 1 | `synchronized`/`Atomic` | mo-chain / CAS volatile-like | đúng tuyệt đối |
| 2 | `plain`/`plain+println` | không có edge nào (println chỉ chặn optimization, không tạo HB) | treo (hoặc "may mắn" hết treo) |
| 2 | `volatile` | vo (write→read) | đúng tuyệt đối |
| 3 | `unsafeInstance` | không có edge nào | có thể miss + có thể torn (JMM cho phép, x86 chưa gặp) |
| 3 | `finalFieldInstance` | freeze guarantee (không phải edge cổ điển) — chỉ bảo vệ nội dung, không bảo vệ việc thấy reference | có thể miss, không bao giờ torn |
| 3 | `volatileInstance` | po + vo + po (bắc cầu) | đúng tuyệt đối |
| 3 | `syncInstance` | po + mo + po (bắc cầu) | đúng tuyệt đối |

## Trả lời câu hỏi tự kiểm tra Cấp 1 (không nhìn tài liệu)

**Vì sao `volatile` sửa được bài 2 nhưng không sửa được bài 1?**

Bài 2 chỉ cần **một** hành động ghi và **một** hành động đọc có edge nối chúng — `volatile` cho đúng một edge như vậy (vo), thế là đủ. Bài 1 cần bảo vệ **một cụm 3 hành động** (đọc-sửa-ghi) khỏi bị một thread khác chen vào giữa chừng — `volatile` chỉ tạo edge cho từng đọc/ghi đơn lẻ, không tạo được một "vùng cấm chen ngang" bao trọn cả cụm. Nói ngắn gọn: **volatile giải quyết visibility của một thời điểm, không giải quyết atomicity của một chuỗi thao tác.**
