# Unroll — từ máy ảnh đến lúc đăng ảnh, trong vài giây

App iOS native (SwiftUI, iOS 17+). Gom toàn bộ luồng **rút ảnh → lọc → gắn GPS → công thức màu → đóng khung → recap** vào một app duy nhất, dùng được với **mọi hãng máy ảnh** qua cáp USB-C, đầu đọc thẻ SD, và Wi-Fi của Ricoh GR (bản beta).

```bash
cd Unroll && xcodegen generate && open Unroll.xcodeproj   # Run (⌘R) → bấm "Demo Roll" để thử ngay, không cần máy ảnh
```

---

## 1. Nỗi đau của người dùng → tính năng giải quyết

Xếp theo mức độ người dùng phàn nàn (nguồn: review App Store, DPReview, forum Leica/Canon, FAQ của hãng — 11/2026).

| # | Nỗi đau với app chính hãng | Unroll giải quyết thế nào |
|---|---|---|
| 1 | Ghép đôi BLE/Wi-Fi hay lỗi, rớt kết nối, phải xoá rồi ghép lại | **Cắm cáp USB-C là chạy.** Không cần ghép, không Bluetooth, không tài khoản. Dùng ImageCaptureCore (PTP) và chạy với mọi hãng. |
| 2 | Kết nối và tải chậm, thẻ đầy thì đứng hình | **Ảnh mới nhất hiện trước.** Tắt việc tự tải thumbnail/metadata của từng ảnh (`shouldGetThumbnailOf → false`). Header chỉ đọc 192 KB/file thay vì cả file 60 MB. Thumbnail tải theo phần đang hiển thị trên màn hình. Có cache để lần cắm sau hiện ngay. |
| 3 | Không có RAW, hoặc RAW làm rớt kết nối | **JPG+RAW thành một ảnh duy nhất trong Photos** (`.photo` + `.alternatePhoto`). Cũng hỗ trợ ảnh chỉ có RAW: RAF, ARW, NEF, DNG, CR2, CR3, ORF, RW2, PEF… |
| 4 | Ảnh bị thu nhỏ còn 2–3 MP | **Luôn lấy file gốc từng byte một.** Ảnh preview 2–8 MP được cắt ra từ chính file (MPF/RAF/TIFF/CR3 PRVW) để xem nhanh. |
| 5 | GPS qua Bluetooth hao pin, toạ độ sai hoặc mất | **Photo Walk** ghi lộ trình GPS nhẹ trên điện thoại. Nếu không ghi thì **mượn vị trí của ảnh iPhone** chụp trong khoảng ±20 phút. Không cần máy ảnh bật Bluetooth. |
| 6 | Đồng hồ máy ảnh bị lệch, sai múi giờ → GPS gắn sai chỗ | **Clock Sync bằng QR:** chụp mã QR thời gian trên màn hình điện thoại, app tự tính độ lệch của từng thân máy và sửa cho mọi lần import. |
| 7 | Xin quá nhiều quyền, bắt tạo tài khoản | Không tài khoản, không cloud, không analytics. Chỉ xin quyền khi cần đến. |
| 8 | Không lọc được ảnh đã gắn ★ / 🔒 trên máy | **Lọc ★ (1–5 sao) và 🔒 ngay khi import.** Đọc sao từ Fuji MakerNote 0x1431, Sony 0x2002, EXIF 0x4746 và XMP. Có nút "Import ★ & 🔒" một chạm, và tự động import khi cắm (Pro). |
| 9 | Mất công thức màu (film recipe) | **Giải mã Fujifilm recipe** đầy đủ: film sim, grain, CCE/CCB, WB và shift, DR, H/S, color, NR, clarity, mono WC/MG. Cũng đọc Nikon Picture Control và Sony Creative Style. Có **Recipe Book** tự gom công thức qua mỗi lần import và chia sẻ dạng thẻ. |
| 10 | Import trùng lặp | **Import Ledger** nhớ những ảnh đã import, nên nút "Import new" không bao giờ tạo bản trùng. |
| 11 | Phải nhảy qua 2–3 app (lấy ảnh → app khung EXIF → app recipe) | **Frame Studio** (6 kiểu khung, 4 tỉ lệ, xuất full-res) và **Recap card** 9:16 có bản đồ lộ trình, nằm ngay trong luồng import. |
| 12 | Vẫn chụp tiếp khi đang cắm cáp | **Import trực tiếp:** ảnh mới chụp lúc đang cắm cáp tự vào Photos (khi bật auto-import). |

## 2. Đối thủ và khoảng trống thị trường

| App | Điểm mạnh | Điểm còn thiếu mà Unroll lấp |
|---|---|---|
| Cascable ($30/năm) | Tethering nhiều hãng | Chỉ có iOS, thiên về điều khiển studio, Sony hay rớt kết nối |
| Shutter+ | Remote, lọc ảnh | Mỗi hãng một app riêng, đắt |
| TetherDrop ($30/năm) | USB-C, PTP/IP, lọc theo sao | Chỉ JPEG, không RAW |
| Better Camera Import / Capture (2026) | Import thẻ, RAW, geotag GPX | Không lọc theo sao, không có recipe, không có khung |
| FUJISTYLE / Fuji X Weekly | Cộng đồng recipe | Không lấy được ảnh từ máy |
| Cameramark ($6.99) | Khung EXIF | Chỉ dùng được với ảnh đã có trong Photos |

Chưa app nào gộp đủ: **import siêu nhanh + lọc sao/khoá ngay từ thân máy + recipe + GPS sửa theo đồng hồ + khung + recap**.

## 3. Kiến trúc

```
Unroll/
├─ Core/          Phần tách riêng để test, không phụ thuộc UI
│  ├─ TIFF.swift            Đọc IFD có kiểm tra giới hạn (chịu được header bị cắt cụt)
│  ├─ HeaderParser.swift    JPEG/MPF, RAF, TIFF-RAW (ARW/NEF/DNG/CR2/ORF…), CR3 → meta + vùng chứa preview
│  ├─ FujiMakerNote.swift   Bảng giải mã recipe (theo ExifTool)
│  ├─ TIFFBuilder.swift     Ghi EXIF + MakerNote (dùng cho demo và test)
│  └─ Shot.swift            Model + API đọc byte chung cho USB, file và HTTP
├─ Sources/       USBCameraService (ImageCaptureCore), FolderSource (thẻ SD/Files), RicohGRSource (HTTP), DemoRoll
├─ Services/      ImportEngine, Location (Photo Walk + mượn GPS từ iPhone), ClockSync, FrameRenderer,
│                 RecapRenderer, ProStore (StoreKit 2), Previews, Stores (cache/ledger/recipes/settings/quota)
└─ UI/            Home, Library (grid + bộ lọc), Shot pager, Frame Studio, Recap, Recipes, Clock Sync, Settings, Paywall
```

**Nguyên tắc chính:** không bao giờ sửa file gốc. Vị trí và ngày chụp được gắn vào asset trong Photos, nên MakerNote và recipe trong file vẫn còn nguyên.

## 4. Kiếm tiền và lan truyền

- **Free:** xem toàn bộ thẻ, EXIF và recipe; import 10 ảnh/ngày; 3 kiểu khung; có watermark nhỏ "Unroll".
- **Pro:** Yearly $14.99 (dùng thử 7 ngày) · **Founder Lifetime $29.99**. Mở khoá import không giới hạn, tự import ảnh ★/🔒, geotag tự động, mọi kiểu khung, bỏ watermark.
- **Paywall hiện đúng lúc người dùng thấy giá trị:** khi họ chọn import nhiều hơn số còn lại trong ngày.
- **Vòng lặp lan truyền:** recap card 9:16, khung EXIF và thẻ recipe đều có dòng "made with Unroll" (bản free).

Mỗi bài đăng của người dùng là một quảng cáo miễn phí cho app.

## 5. Đã kiểm chứng và chưa kiểm chứng

**Đã chạy thật:**
- 10/10 unit test pass: parser Fuji JPEG + recipe, RAF, TIFF-RAW, XMP rating, header bị cắt cụt, ghép RAW + phát hiện ảnh khoá trên thẻ, renderer khung, QR clock-sync.
- Trên Simulator, end-to-end với Demo Roll: grid, bộ lọc, chi tiết + recipe, Frame Studio, import vào Photos (cả ảnh khoá), paywall theo quota, recap card có bản đồ.

**Cần thử trên iPhone thật + máy ảnh thật** (Simulator không có USB):
- Kết nối USB-C qua ImageCaptureCore. iOS 18–26 có báo cáo `requestContentsAuthorization` trả trạng thái sai; code đã có đường dự phòng.
- Việc iOS có nhận đầu đọc thẻ SD là "camera device" hay không. Nếu không, dùng nút "SD Card" (Files) — đường này đã chạy.
- `ICCameraItem.isLocked` có đúng là cờ Protect của từng file hay không.
- Vị trí PRVW trong CR3, và tag sao của Sony.
- API Wi-Fi của Ricoh GR (do cộng đồng giải mã, chưa thử trên GR IV).
- StoreKit: chạy từ Xcode (scheme đã gắn `Unroll.storekit`) để test mua hàng.

## 6. Lộ trình

1. **Tuần 1–2:** test trên máy thật (Fuji / Sony / Canon / Ricoh), đo thời gian từ lúc cắm đến khi hiện ảnh đầu tiên, quay video 20 giây để làm marketing.
2. **TestFlight 200 người:** đăng r/fujifilm, r/ricohGR, r/SonyAlpha, Threads, TikTok.
3. **Wi-Fi PTP/IP** cho Sony và Canon (cổng 15740, đọc ngược `GetObjectHandles` + `GetPartialObject`), kết nối Wi-Fi bằng QR + `NEHotspotConfiguration`.
4. **Gửi recipe ngược vào máy Fuji** qua USB (giống Fuji X Weekly v1.15).
5. **Android** (Kotlin, `MtpDevice`): dùng lại cùng thuật toán parser.
