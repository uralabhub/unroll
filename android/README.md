# Unroll cho Android

Bản Android của Unroll (Kotlin, Jetpack Compose, minSdk 29, Gradle Kotlin DSL). Luồng chính giống bản iOS trong `../Unroll/`: cắm máy ảnh hoặc mở thẻ SD → ảnh mới nhất hiện trước → lọc ★/🔒/RAW → xem EXIF + công thức màu → import (giữ nguyên file gốc) → đóng khung / recap.

```bash
cd android
./gradlew test            # 42 test của parser (module core, chạy trên JVM, không cần điện thoại)
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

Cần JDK 17 và Android SDK (compileSdk 37). Nếu `java` mặc định không phải bản 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

---

## Cấu trúc

```
android/
├─ core/   Kotlin thuần (JVM), không phụ thuộc Android — test được trên máy tính
│  ├─ Tiff.kt            ByteView + đọc IFD có kiểm tra giới hạn (chịu được header bị cắt cụt)
│  ├─ HeaderParser.kt    JPEG/MPF, RAF, TIFF-RAW (ARW/NEF/DNG/CR2/ORF/RW2/PEF), CR3, HEIF
│  ├─ Heif.kt            Tự đọc box meta/iinf/iloc/iprp để tìm item Exif (bản iOS dùng ImageIO)
│  ├─ FujiMakerNote.kt   Bảng giải mã recipe theo ExifTool, có thêm D-Range Priority
│  ├─ ShotMeta.kt        ShotMeta, FilmRecipe (rows, shortLine, signature), ShotFormat
│  ├─ TiffBuilder.kt     Ghi EXIF + MakerNote (cho demo roll và test)
│  ├─ GpsInjector.kt     Thêm khối GPS vào JPEG bằng cách nối thêm vào cuối, không dời byte nào
│  ├─ CardScan.kt        Ghép RAW+JPEG theo thư mục + tên file, phát hiện 🔒, quét thư mục
│  ├─ ClockSync.kt       Tạo và đọc mã QR thời gian (ZXing)
│  ├─ FrameLayout.kt     Hình học của khung (dùng chung cho renderer và test)
│  ├─ Trail.kt           Photo Walk: nội suy vị trí theo thời gian
│  └─ DemoRoll.kt        Nội dung EXIF/recipe của 42 ảnh demo
└─ app/    Ứng dụng Android
   ├─ source/    MtpSource (USB Host + android.mtp), SafSource (thẻ SD), DemoRollWriter, RicohSource
   ├─ importer/  ImportEngine + ImportService (foreground service), MediaStoreSaver, PhoneLocationLookup
   ├─ media/     Previews: thumbnail và ảnh xem trước bằng cách đọc một đoạn byte
   ├─ render/    FrameRenderer, RecapRenderer, RecipeCard (Canvas)
   ├─ billing/   ProStore (Google Play Billing 8.3)
   ├─ location/  WalkRecorder + WalkService (GPS log)
   ├─ data/      MetaCache, ImportLedger, RecipeBook, Settings, Quota, WalkStore
   └─ ui/        Mono.kt (hệ thiết kế đơn sắc), Home, Library, Shot detail, Frame Studio,
                 Recap, Recipe Book, Clock Sync, Settings, Paywall, Walks
```

## Khác biệt so với bản iOS (và lý do)

| Chủ đề | iOS | Android |
|---|---|---|
| Kết nối USB | ImageCaptureCore | `UsbManager` + `android.mtp.MtpDevice`. Header đọc bằng `getPartialObject` (192 KB). Chỉ dùng `getPartialObject64` khi offset vượt 4 GB, vì đa số máy ảnh không hỗ trợ lệnh mở rộng này. Thumbnail trong lưới lấy bằng `getThumbnail`. |
| Ảnh mới chụp khi đang cắm cáp | `cameraDevice(_:didAdd:)` | Nghe sự kiện PTP `ObjectAdded` qua `MtpDevice.readEvent`. Nếu máy ảnh không gửi sự kiện thì cứ 6 giây so lại danh sách handle một lần. |
| HEIF | ImageIO | Tự parse box ISO/IEC 23008-12. Đọc được EXIF và kích thước ảnh chính nếu box `meta` và item Exif nằm trong 192 KB đầu. Nếu không nằm trong đó thì lấy EXIF từ file RAW đi kèm (giống iOS). |
| Lưu vào thư viện | PhotoKit: JPG+RAW thành **một** asset | MediaStore không có khái niệm asset ghép. JPG và RAW được lưu cạnh nhau trong `DCIM/Unroll`, **cùng tên** (ví dụ `DSCF4120.JPG` + `DSCF4120.RAF`). Nếu MediaStore đổi tên JPG để tránh trùng, RAW sẽ đặt theo tên mới đó. |
| Vị trí | Gắn vào asset, file gốc giữ nguyên | Từ Android 10, cột vị trí của MediaStore chỉ đọc; Google Photos và các app gallery chỉ đọc GPS **bên trong file**. Vì vậy với JPEG cần gắn vị trí, `GpsInjector` *nối thêm* một bản IFD0 mới cùng GPS IFD vào cuối đoạn APP1, rồi sửa đúng 6 byte: con trỏ IFD0 (4 byte) và độ dài APP1 (2 byte). Dữ liệu ảnh, MakerNote, recipe và mọi tag khác giữ nguyên byte và vị trí (có test kiểm tra từng byte). RAW, HEIF và video luôn được chép nguyên vẹn từng byte. Tắt "Add location to imports" thì JPEG cũng được chép nguyên vẹn. |
| Ngày chụp | `creationDate` của asset | `DATE_TAKEN` của MediaStore (đã trừ độ lệch đồng hồ máy ảnh). |
| Bản đồ trong recap | MKMapSnapshotter | Không dùng tile bản đồ (cần API key và mạng). Lộ trình được vẽ thành đường trắng trên nền tối có lưới, đúng tinh thần đơn sắc. |
| Viền Film Strip | Chữ in mép màu hổ phách | Xám, để giữ quy tắc đơn sắc. |
| Thanh toán | StoreKit 2 | Play Billing: `com.uranashel.unroll.pro.yearly` (subscription $14.99/năm; trial 7 ngày phải được đặt làm offer trong Play Console, app sẽ tự chọn offer có giai đoạn giá 0) và `com.uranashel.unroll.pro.lifetime` (in-app $29.99). |

## D-Range Priority (mới, chỉ có trên nhánh này)

Theo bảng FujiFilm của ExifTool: `0x1443` = 0 Auto / 1 Fixed; `0x1444` (Auto) = 1 Weak, 2 Strong, 3 Plus; `0x1445` (Fixed) = 1 Weak, 2 Strong.

- Auto + `0x1444` ∈ {1, 2, 3} → hiển thị "Auto".
- Fixed + `0x1445` ∈ {1, 2} → hiển thị "Weak" hoặc "Strong".
- Mọi giá trị khác → coi là tắt.
- Khi bật: dòng "D-Range Priority" thay cho ba dòng Dynamic Range, Highlight và Shadow, và dòng tóm tắt thành `Classic Chrome · DRP Strong`.

Demo Roll có một nhóm ảnh (Nostalgic Neg.) dùng DRP Fixed/Strong để xem được dòng này.

## Đã kiểm chứng và chưa kiểm chứng

**Đã chạy thật (13/11/2026):**
- `./gradlew test`: **42/42 test pass** trong module `core`. Gồm toàn bộ test port từ `UnrollTests/` (HeaderParserTests, SpecConformanceTests), dùng cùng các file tự dựng theo spec, với `TIFFBuilder` port sang Kotlin:
  - Recipe Fuji JPEG, ngày chụp theo OffsetTime, Acros từ Saturation, RAF bọc JPEG (preview giải mã được), TIFF-RAW chọn preview lớn nhất, XMP rating, header bị cắt cụt, ghép RAW + 🔒, thẻ bị khoá ghi thì không biến mọi ảnh thành keeper.
  - Fuji WC/MG signed byte, grain gộp, WB shift ×20, film mode lạ không đoán bừa, header "FUJIFILM" của hãng khác bị bỏ qua.
  - CR2 (strip raw không phải preview), RW2 JpgFromRaw, ORF preview trong MakerNote Olympus, XMP trong TIFF, Nikon Picture Control v2/v3, Sony không header, CR3 CMT4 GPS + XMP rating.
  - Thứ tự Ricoh, khung rộng hơn ảnh và đúng tỉ lệ 9:16, QR clock sync khứ hồi.
  - Test mới: HEIF dựng theo spec (iloc v0/v1, Exif trong mdat hoặc idat, kích thước `ispe` qua `ipma`, bị cắt cụt), CR3 PRVW, Sony có header + Creative Style, EXIF 0x4746, ghép theo thư mục qua MTP, D-Range Priority (Auto/Fixed/các giá trị tắt, thay dòng, shortLine), GPS injection (giữ nguyên từng byte; hỗ trợ big-endian; cập nhật kích thước ảnh đầu trong MPF và preview vẫn giải mã được), nội suy Photo Walk, 42 ảnh demo parse như file máy ảnh thật, ShotMeta khứ hồi JSON.
  - Lưu ý: JPEG nền trong test được tạo bằng `javax.imageio` thay cho UIKit. Riêng test RW2 dùng ảnh có nhiễu, vì JPEG phẳng 64×48 của ImageIO chỉ ~600 byte, dưới ngưỡng 1024 byte mà parser dùng để coi là preview.
- `./gradlew assembleDebug` build thành công.
- Chạy trên emulator (API 35): xem phần "Emulator" bên dưới.

**Cần điện thoại Android thật + máy ảnh thật** (emulator không có USB Host):
- Toàn bộ đường MTP: xin quyền USB, `MtpDevice.open`, `getObjectHandles(storage, 0, 0)` ("mọi object") so với đường dự phòng duyệt từng thư mục, tốc độ `getPartialObject` 192 KB / 4 MB, `getThumbnail`, `readEvent` khi chụp lúc đang cắm cáp.
- Xung đột với chính Android: một số máy (Samsung, Pixel) để `MtpDocumentsProvider` / app Files hoặc Photos chiếm máy ảnh khi vừa cắm vào. Nếu `open` thất bại, app sẽ báo người dùng đóng app đó.
- Thứ tự "mới nhất trước" giả định handle MTP tăng theo thời gian tạo file. Điều này đúng với PTP thông thường, nhưng chưa thử trên từng hãng.
- Cờ Protect: qua MTP đọc từ `MtpObjectInfo.protectionStatus`; qua SAF đọc từ việc document thiếu `FLAG_SUPPORTS_WRITE`. Chưa rõ ExternalStorageProvider có phản ánh thuộc tính read-only của FAT trên thẻ SD hay không.
- MediaStore với từng loại RAW: phải xem MIME nào được nhận vào bảng Images trên từng phiên bản Android (CR3 có thể không có trong MimeTypeMap cũ). Loại nào bị từ chối sẽ rơi xuống `Download/Unroll`. Cũng cần xem MediaStore có ghi đè `DATE_TAKEN` bằng giá trị từ EXIF khi quét lại hay không.
- Google Photos có ghép JPG+RAW cùng tên thành một mục hay không (tuỳ app gallery).
- Mượn vị trí từ ảnh điện thoại: cần `READ_MEDIA_IMAGES` + `ACCESS_MEDIA_LOCATION`, chưa thử với thư viện ảnh thật.
- Photo Walk GPS trong foreground service (type location) khi tắt màn hình lâu.
- Google Play Billing: cần tạo hai sản phẩm trong Play Console và cài bản qua internal testing track mới mua thử được. Trên emulator, app chỉ hiện giá dự phòng ($29.99 / $14.99).
- API Wi-Fi Ricoh GR (beta, do cộng đồng giải mã). Request được gắn vào mạng Wi-Fi để Android không đẩy sang 4G.
- Những điểm chưa kiểm chứng ở bản iOS (hãng nào ghi sao vào 0x4746/XMP, ARW mới có header "SONY DSC" hay không…) vẫn còn nguyên.

### Đã chạy trên thiết bị (13/11/2026)

**Điện thoại thật: Samsung Galaxy A56 (SM-A566B), Android 17, chế độ tối.** Cài qua `adb install`, thao tác bằng `adb shell input`. Không có máy ảnh cắm vào, nên dùng Demo Roll.

| Màn hình / luồng | Kết quả |
|---|---|
| Home | Đúng giao diện đơn sắc và font Montserrat; nội dung không còn chạy dưới thanh trạng thái |
| Demo Roll → Library | 42 ảnh, 16 pick, 12 có sao, 6 khoá 🔒, cặp J+R được ghép; mở xong khoảng 2 giây; bộ lọc và menu lưới hoạt động |
| Shot detail | EXIF, ★, recipe mở rộng 10 dòng, dòng "Location from your walk" |
| Frame Studio | Gallery, Caption Bar, tỉ lệ 9:16, chip Place (Geocoder trả về "Thành phố Hồ Chí Minh"); Save ghi vào `Pictures/Unroll` |
| Recap card | 6 ảnh, lộ trình đơn sắc, số liệu (16 ảnh / 4,0 km / 6h19), chip film sim, địa danh |
| Import + quota + paywall | "Import picks 16": 10 ảnh vào `DCIM/Unroll` trong 0,7 s, cả 10 có vị trí; 6 ảnh còn lại bật paywall; ledger giữ ✓, lần sau nút còn "Import picks 6" |
| Paywall | Lifetime $29.99 / Yearly $14.99 kèm 7 ngày dùng thử (giá dự phòng, vì sản phẩm chưa có trên Play Console); không bấm mua |
| Recipe Book | 6 recipe gom từ lần import; "Nostalgic Neg. · DRP Strong"; trong chi tiết, dòng D-Range Priority thay cho DR/Highlight/Shadow |
| Clock Sync | Mã QR và đồng hồ đổi mỗi giây; màn hình giữ sáng |
| Settings | Các toggle, bộ chọn "When a camera connects" và "Save to" |
| Walks | Lộ trình demo 3,97 km, 380 điểm GPS |

**Lỗi tìm được trên máy thật và đã sửa:**
- Caption Bar ở tỉ lệ cao (9:16) đặt chữ giữa cả vùng trắng, xa khỏi ảnh (bản iOS cũng dùng công thức này). Đã chuyển chữ về dải 9u ngay dưới ảnh.
- Thẻ tiến độ import đè lên paywall. Đã cho paywall nằm trên.
- Nội dung Home cuộn lên nằm dưới thanh trạng thái. Đã chuyển inset ra ngoài vùng cuộn.
- Chú thích trong các ô và dòng tên hãng bị cụt vì Montserrat rộng hơn. Đã rút gọn chữ và cho dòng tên hãng xuống hàng.
- Panel thông tin ảnh đè lên thanh điều hướng khi cuộn. Đã sửa padding.

**Emulator (API 35, AVD chạy `-read-only` ở cổng 5590):** Home, Demo Roll, chi tiết + recipe và import một JPEG đều chạy. File kéo về từ MediaStore khác file gốc đúng 4 byte trong APP1 (độ dài APP1 và con trỏ IFD0); mọi byte sau APP1 giống hệt; GPS đúng vị trí trong walk demo. Máy Mac lúc đó quá tải (load trên 100), nên System UI của emulator treo nhiều lần. Đó là lỗi của máy ảo, không phải của app.

**Chưa chạy trên thiết bị:** Share sheet (chỉ mở được, không gửi), "Copy as text", Photo Walk thật (bấm ghi GPS), chọn SD card qua SAF, Ricoh Wi-Fi, mua hàng. Mọi thứ cần máy ảnh thật được liệt kê ở mục bên trên.

## Font, icon, chuyển động (cả Android và iOS)

- **Font:** Nunito (Regular, Medium, SemiBold, Bold, ExtraBold) cho mọi chữ, trên giao diện lẫn ảnh render ra (khung, recap, thẻ recipe). Số liệu dùng chữ số đều cột (`tnum` / `monospacedDigit`) thay cho font monospace. Giấy phép đã đóng gói: `android/licenses/Nunito-OFL.txt` (SIL OFL 1.1) và `Phosphor-MIT.txt`, kèm bản sao trong `app/src/main/assets/licenses/`.
- **Icon:** Phosphor Icons (giấy phép MIT). Android dùng vector drawable `ph_*`; iOS dùng imageset SVG dạng template `ph-*` trong Asset Catalog. Icon app (launcher) chưa đổi.
- **Chuyển động:**
  - Mở ảnh từ lưới: ảnh phóng to thành màn chi tiết (shared element trên Android, `navigationTransition(.zoom)` trên iOS 18).
  - Chuyển màn và mọi thao tác chạm dùng spring.
  - Bộ lọc có viên nền trượt.
  - Các khối xuất hiện lần lượt; ô ảnh bật vào; số liệu lăn khi đổi.
  - Nút Photo Walk biến hình giữa tròn và vuông; lỗ phim trên thẻ hero chạy liên tục; thẻ hero có parallax khi cuộn.
  - Paywall trượt lên theo spring.
  - Android: thẻ hero nghiêng tối đa 4° theo cảm biến trọng lực, đổ hai lớp bóng; rung phản hồi khi đổi bộ lọc, chọn ảnh, bắt đầu và xong import, bắt đầu và dừng Photo Walk. Khi bật "Xoá hiệu ứng" (animator scale = 0), các vòng lặp, độ nghiêng và hiệu ứng xuất hiện lần lượt đều tắt. Phần này chưa thử trên máy thật vì điện thoại đang khoá màn hình.
  - Đã kiểm tra bằng video quay màn hình trên Galaxy A56 và trên Simulator iOS 27.
