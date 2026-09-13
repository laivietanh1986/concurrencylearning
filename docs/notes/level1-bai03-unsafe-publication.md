# Bài 3 — Publish object chưa khởi tạo xong (`UnsafePublication`)

## Kết quả đo được (1000 vòng lặp mỗi biến thể, trên máy này — x86/Windows)

| Biến thể | Không thấy publish kịp | Thấy & đúng (1,2,"ready") | Thấy & **rách** (torn) |
|---|---|---|---|
| `unsafeInstance` (reference thường + field thường) | 946 / 1000 | 54 / 1000 | **0** |
| `finalFieldInstance` (reference thường + field `final`) | 897 / 1000 | 103 / 1000 | **0** |
| `volatileInstance` (reference `volatile`) | 0 / 500 | 500 / 500 | **0** |
| `syncInstance` (publish/read trong `synchronized`) | 0 / 500 | 500 / 500 | **0** |

Hai điều đáng chú ý ngay từ bảng số liệu, và cả hai đều là bài học thật chứ không phải nhiễu:

## 1. "Không thấy publish kịp" áp đảo ở 2 biến thể dùng reference thường — đây chính là bug bài 2 quay lại

`unsafeInstance` và `finalFieldInstance` đều được publish qua một **field tham chiếu thường** (không `volatile`). Reader thread busy-loop đọc field đó y hệt cấu trúc bài 2 (`while (ref == null) {}`) — và đúng như bài 2 đã chứng minh, sau khi JIT compile vòng lặp này, nó **hoist việc đọc field ra khỏi vòng lặp**, nên phần lớn các vòng lặp không bao giờ thấy giá trị mới trong ngân sách 2 triệu lần đọc. Đây không phải là một bug khác — đây là **bằng chứng sống rằng bug bài 2 và bug bài 3 luôn đi kèm nhau**: publish một object qua reference thường thì bạn vừa có nguy cơ *không bao giờ thấy được reference* (bài 2), vừa có nguy cơ *thấy object chưa hoàn chỉnh nếu có thấy* (bài 3).

## 2. `torn = 0` ở mọi biến thể — vì sao "không thấy bug" ≠ "code đúng"

Java Memory Model (JMM) **cho phép về mặt lý thuyết** trường hợp: thread B thấy `unsafeInstance != null` nhưng `h.a == 0` (chưa thấy write trong constructor), vì không có quan hệ happens-before nào ràng buộc thứ tự các write đó phải "cùng xuất hiện" với reader. Nhưng trên thực tế **CPU x86/AMD64 dùng mô hình bộ nhớ TSO (Total Store Order)**, trong đó **store-store không bao giờ bị đảo thứ tự** — nếu writer ghi `a`, `b`, `label` rồi mới ghi reference, phần cứng x86 đảm bảo thread khác thấy reference **sau khi** đã thấy các field (nếu thấy reference thì cũng thấy field, vì chúng "khoá" theo đúng thứ tự đó khi rời khỏi CPU). Đây là lý do `torn` luôn bằng 0 trên máy Windows/x86 dù ta hoàn toàn không dùng `volatile`/`final`/`synchronized`.

**Đây chính là bẫy nguy hiểm nhất của bài này:** code chạy đúng trên x86 không có nghĩa là code đúng theo JMM. Trên kiến trúc bộ nhớ yếu hơn (ARM, kể cả Apple Silicon; PowerPC), hoặc khi JIT tương lai áp dụng tối ưu hoá mạnh hơn (store reordering, scalar replacement tách object ra rồi ghép lại không đúng thứ tự), `torn` có thể xuất hiện thật. Không có gì trong ngôn ngữ Java bảo vệ bạn — chỉ có **happens-before** mới bảo vệ được, không phải "may mắn phần cứng."

## Vì sao `final` field cứu được (final field freeze semantics — JLS 17.5)

Khi một field được khai báo `final`, JLS đặt ra một quy tắc đặc biệt: **write vào final field trong constructor được "đóng băng" (freeze) tại thời điểm constructor kết thúc**, và **mọi thread nhìn thấy reference tới object đó (bằng bất kỳ cách nào, kể cả qua một field thường không đồng bộ) đều được đảm bảo thấy giá trị final field đã freeze** — miễn là object không bị "thoát ra ngoài" (escape) qua `this` trước khi constructor chạy xong.

Đây là lý do bảng trên cho thấy `finalFieldInstance`: dù reference vẫn có thể "không thấy kịp" (946→897, cùng bug bài 2), nhưng **một khi đã thấy reference thì field không bao giờ rách** (`torn = 0`, được `assertEquals` xác nhận cứng trong test — đây là guarantee thật của JMM, không phải may mắn phần cứng như 2 biến thể kia).

→ Immutable object (mọi field `final`, không để `this` thoát ra ngoài trong constructor) là **an toàn để publish qua BẤT KỲ cơ chế nào**, kể cả không đồng bộ — đây là 1 trong 4 "safe publication idiom" kinh điển (JCiP).

## Vì sao `volatile` reference giải quyết cả hai vấn đề cùng lúc

Viết `volatile Holder volatileInstance` khiến câu lệnh `volatileInstance = new Holder(...)` trở thành một **volatile write**. Theo JMM, volatile write đóng vai trò **StoreStore + StoreLoad barrier**: mọi write thường (a, b, label bên trong constructor) nằm **trước** nó theo program order **không được phép trôi qua nó** (roach-motel semantics — "vào được nhưng không ra được"). Vì vậy:
- **Bài 2's vấn đề** (reader không bao giờ thấy reference đổi) biến mất, vì volatile buộc mỗi lần đọc phải là một load thật từ bộ nhớ.
- **Bài 3's vấn đề** (thấy reference nhưng field rách) cũng biến mất, vì mọi field-write trước đó bị "khoá" phải xuất hiện trước volatile write trong mắt mọi thread khác.

Đó là lý do bảng trên: `volatileInstance` đạt 500/500 thấy đúng, 0 rách, 0 miss — an toàn tuyệt đối, không phụ thuộc kiến trúc phần cứng.

## Vì sao `synchronized` cũng an toàn tuyệt đối

`publishSync`/`readSync` đều bọc trong cùng một `synchronized (lock)`. Monitor exit (cuối khối `synchronized` của writer) **happens-before** monitor enter tiếp theo (đầu khối `synchronized` của reader) trên **cùng một lock**. Đây là quan hệ happens-before mạnh nhất trong JMM — nó kéo theo mọi write trước đó của writer (kể cả field bên trong constructor) chắc chắn visible cho reader. Cái giá phải trả là mutual exclusion thật (reader/writer phải xếp hàng), trong khi `volatile` chỉ cần visibility, không cần loại trừ lẫn nhau.

## Vì sao double-checked locking (DCL) bắt buộc cần `volatile`

DCL kinh điển (sai) trông như sau:
```java
if (instance == null) {              // đọc 1: không lock
    synchronized (this) {
        if (instance == null) {
            instance = new Singleton();   // publish qua reference thường
        }
    }
}
return instance;
```
Nhánh "đọc 1: không lock" đọc `instance` **ngoài** khối `synchronized`, y hệt biến thể `unsafeInstance` ở trên: reference thường, không có happens-before nào ràng buộc. Hai bug xảy ra đồng thời:
1. **Bài 2's bug:** thread khác có thể không bao giờ thấy `instance` đổi từ `null` (hoặc thấy trễ) nếu bị hoist.
2. **Bài 3's bug:** thread khác có thể thấy `instance != null` nhưng `Singleton` chưa khởi tạo xong (trên kiến trúc bộ nhớ yếu hơn x86).

Khai báo `private volatile Singleton instance;` sửa **cả hai cùng lúc** — đúng như phần trên đã giải thích: volatile vừa ép đọc lại bộ nhớ thật (sửa bài 2), vừa chặn field-write trôi qua sau publish (sửa bài 3). Đây chính là "Safe Double-Checked Locking" — DCL chỉ đúng **nếu và chỉ nếu** field có `volatile`.

## Câu hỏi tự kiểm tra
- Vì sao final field vẫn "không thấy publish kịp" giống hệt biến thể unsafe, dù nó vốn được coi là "an toàn"? (Gợi ý: final field freeze chỉ đảm bảo *nội dung* object đúng khi *đã* thấy reference — nó không giúp bạn thấy reference sớm hơn.)
- Nếu bỏ `volatile` khỏi reference nhưng object publish là *immutable hoàn toàn* (mọi field final), DCL còn đúng không? Đúng ở khía cạnh nào, sai ở khía cạnh nào?
