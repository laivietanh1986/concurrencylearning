# Bài 1 — Lost update: counter đua nhau (`RaceLab`)

## Kết quả đo được
8 thread, mỗi thread `++` 100.000 lần → kỳ vọng 800.000.

| Biến thể | Kết quả thực tế | Đúng? |
|---|---|---|
| `int` thường | 238.731 | ✗ mất gần 70% |
| `volatile int` | 393.078 | ✗ mất hơn 50% |
| `synchronized` | 800.000 | ✓ |
| `AtomicInteger` | 800.000 | ✓ |

## Vì sao `int` thường sai — atomicity

`counter++` không phải một phép toán duy nhất. CPU/JVM thực hiện 3 bước:

```
1. đọc counter vào thanh ghi   (read)
2. cộng 1 vào thanh ghi        (modify)
3. ghi thanh ghi lại vào counter (write)
```

Nếu 2 thread cùng đọc giá trị `5` gần như đồng thời, cả hai cùng tính ra `6` rồi cùng ghi `6` — một lần tăng bị **mất** dù cả hai thread đều đã chạy `++`. Đây gọi là **lost update**. Chuyện này xảy ra ở tầng CPU, compiler hoàn toàn không cần "cố tình" làm sai, chỉ cần hai thread chen lẫn vào giữa 3 bước đó là đủ.

## Vì sao `volatile` không cứu được — atomicity ≠ visibility

`volatile` chỉ giải quyết một vấn đề: **visibility** — đảm bảo khi thread A ghi giá trị, thread B đọc ngay sau đó sẽ thấy giá trị mới nhất (không bị cache riêng của CPU/thread che khuất, không bị compiler sắp xếp lại lệnh qua mặt).

`volatile` **không** biến 3 bước read-modify-write ở trên thành một khối không thể chen ngang (không atomic). Hai thread vẫn có thể cùng đọc, cùng cộng, cùng ghi đè lên nhau — chỉ là bây giờ giá trị đọc vào luôn "mới nhất tại thời điểm đọc", chứ không có gì ngăn hai thread đọc cùng lúc.

→ **Kết luận cốt lõi của bài 1:** atomicity (không bị chen ngang giữa chừng) và visibility (thấy được giá trị mới) là **hai trục hoàn toàn khác nhau**. Một biến có thể visible nhưng vẫn không atomic.

## Vì sao `synchronized` đúng

`synchronized` tạo ra một **mutual exclusion**: tại một thời điểm chỉ một thread được vào block, các thread khác phải đợi. Vậy nên toàn bộ chuỗi read-modify-write chạy trọn vẹn không bị chen ngang → atomic. Đồng thời `synchronized` cũng tự động có visibility (unlock của thread này happens-before lock của thread tiếp theo), nên nó giải quyết luôn cả hai vấn đề cùng lúc.

## Vì sao `AtomicInteger` đúng

`AtomicInteger.incrementAndGet()` dùng lệnh CPU đặc biệt (CAS — compare-and-swap, hoặc trên nhiều kiến trúc là lock xchg/faa) để làm read-modify-write thành **một bước phần cứng không thể chen ngang**, không cần lock ở tầng JVM. Nhanh hơn `synchronized` vì không có chuyện thread nào phải "ngủ" chờ — thread thua chỉ retry vòng lặp CAS.

## Câu hỏi tự kiểm tra (đừng nhìn lại bài — trả lời được mới qua bài 2)
- Vì sao `volatile` sửa được bug "vòng lặp không dừng" (bài 2) nhưng không sửa được lost update (bài 1)?
  - *Gợi ý:* bài 2 chỉ cần đọc thấy giá trị mới (1 lần đọc), không có read-modify-write.
