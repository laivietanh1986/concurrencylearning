# Bài 2 — Vòng lặp không bao giờ dừng (`VisibilityLoop`)

## Kết quả đo được (chạy thực tế trên máy này)

| Biến thể | Kết quả |
|---|---|
| `stopPlain` (boolean thường) | **treo mãi mãi** — không bao giờ in "loop stopped" |
| `stopPlain` + `println` mỗi 200 triệu vòng | dừng ngay sau khi main set flag |
| `stopVolatile` (`volatile boolean`) | dừng ngay sau khi main set flag (~880 triệu vòng đã chạy trong lúc chờ) |

## Vì sao `boolean` thường bị treo — JIT hoisting

Thread worker chạy:
```java
while (!stopPlain) { iterations++; }
```

Lúc đầu JVM chạy bằng interpreter (đọc field mỗi vòng, chậm nhưng đúng). Nhưng vòng lặp này chạy hàng trăm triệu lần chỉ trong vài mili-giây, nên JIT (C1 rồi C2) nhanh chóng biên dịch nó thành native code. Khi biên dịch, JIT nhìn vào thân vòng lặp và thấy: **không có instruction nào trong loop này có thể thay đổi `stopPlain`, không có lock, không có volatile, không có method call nào có thể có side-effect ra ngoài tầm nhìn của nó**. Vì vậy JIT coi `stopPlain` là bất biến trong suốt vòng lặp, tối ưu bằng cách **đọc field một lần, giữ giá trị đó trong thanh ghi CPU (register), rồi lặp mãi trên giá trị trong thanh ghi** thay vì đọc lại từ RAM mỗi vòng. Đây gọi là **hoisting** (đẩy việc đọc field ra ngoài vòng lặp).

Hệ quả: khi main thread ghi `stopPlain = true` vào RAM, worker thread **không bao giờ đọc lại RAM nữa** — nó chỉ nhìn vào thanh ghi đã cache từ đầu. Về mặt logic, compiler không sai: nó chỉ tối ưu dựa trên thứ nó nhìn thấy được (single-threaded semantics), và Java Memory Model cho phép việc này *trừ khi* có một quan hệ **happens-before** ràng buộc.

## Vì sao thêm `println` làm bug biến mất

`System.out.println(...)` bên trong là một method `synchronized` (khoá trên object `PrintStream`). Việc gọi vào một method `synchronized` là một **điểm mà JIT không thể tối ưu xuyên qua được** — compiler không được phép giả định trạng thái bộ nhớ trước và sau lock/unlock là giống nhau, vì lock/unlock tạo ra memory barrier thật (theo JMM, monitor exit của thread khác **happens-before** monitor enter kế tiếp). Vì có method call "không trong suốt" đó nằm trong thân vòng lặp, JIT không dám hoist việc đọc `stopPlain` ra ngoài nữa — nó phải đọc lại field ở mỗi lần quay vòng (hoặc ít nhất là đủ thường xuyên để thấy giá trị mới). Bug biến mất không phải vì `println` "chậm hơn nên có thời gian nhận flag" — mà vì nó **phá vỡ điều kiện tối ưu hoá** mà JIT cần để hoist.

## Vì sao `volatile` sửa đúng gốc rễ

`volatile` là tín hiệu chính thức nói với JIT: "biến này có thể bị thread khác ghi bất cứ lúc nào, không được cache trong register, và **mọi lần đọc phải là một bộ nhớ đọc thật (memory load), mọi lần ghi phải flush ngay** (memory store, không được reorder qua nó)". Cụ thể theo Java Memory Model: volatile write **happens-before** mọi volatile read sau đó thấy được write ấy. Đây chính là memory barrier ẩn mà `synchronized` cũng có, nhưng `volatile` cho chỉ với chi phí một biến, không cần mutual exclusion (không ai phải đợi ai — khác với bài 1, ở đây không có read-modify-write nên không cần atomicity, chỉ cần visibility).

## So sánh với bài 1 — vì sao cùng là "sửa bằng volatile" nhưng khác bản chất

- Bài 1 (`counter++`): `volatile` **không đủ** vì bug là do **read-modify-write không atomic** — hai thread chen vào giữa 3 bước đọc/sửa/ghi.
- Bài 2 (`while(!stop)`): `volatile` **đủ** vì bug thuần tuý là **visibility** — chỉ có một lần đọc, một lần ghi, không có "modify dựa trên giá trị cũ".

→ Quy tắc: `volatile` giải quyết visibility cho **một thao tác đọc hoặc một thao tác ghi đơn lẻ**. Nếu logic của bạn là "đọc rồi tính rồi ghi lại dựa trên giá trị vừa đọc" (read-modify-write), `volatile` không cứu được — phải cần atomicity thật (`synchronized`, `Atomic*`, CAS).

## Câu hỏi tự kiểm tra
- Nếu thay `println` bằng một biến `local` không liên quan (không đụng gì tới field/lock) trong loop, bug có biến mất không? (Gợi ý: không — vì compiler vẫn chứng minh được biến local đó không ảnh hưởng tới việc hoist `stopPlain`.)
- `Thread.sleep()` trong loop có sửa được bug không? Vì sao có/không?
