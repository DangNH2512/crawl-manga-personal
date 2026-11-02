# Fetch Manga

Ứng dụng desktop tải chương truyện, viết bằng Java theo mô hình **MVC**, giao diện **Swing**,
đóng gói được thành **file .exe chạy độc lập** (máy đích không cần cài Java).

---

## 1. Chạy nhanh

| Cách | Lệnh |
|---|---|
| Bản exe (khuyên dùng) | Chạy `dist\FetchManga\FetchManga.exe` |
| Từ jar | `java -jar target\fetch-manga.jar` |
| Chế độ dòng lệnh | `java -jar target\fetch-manga.jar --cli` |

Đóng gói lại bản exe:

```powershell
.\build-exe.ps1
```

Script sẽ: build jar gộp → tạo icon → gọi `jpackage` → xuất ra
`dist\FetchManga\FetchManga.exe` và `dist\FetchManga-portable.zip`.
Yêu cầu máy build có **JDK 17+** (đã kèm sẵn `jpackage`).

---

## 2. Luồng thao tác trên giao diện

Cửa sổ chia đúng 3 bước, đọc từ trên xuống:

```
┌ Bước 1 — Dán link chương ─────────────────────────────────┐
│ Link chương: [ https://...                        ] [Dán] │
│ Nguồn:       [ Comic Walker ▾ ]   (tự nhận: Comic Walker) │
├ Bước 2 — Chọn nơi lưu ────────────────────────────────────┤
│ Thư mục đã ghim: [ E:\Manga\sakurairo ▾ ]                 │
│                  [Duyệt & ghim…] [Bỏ ghim] [Mở thư mục]   │
│ Thư mục con:  [ Chap 94 ] [Chọn thư mục con] [Tạo thư mục]│
│ → Lưu vào:  E:\Manga\sakurairo\Chap 94   (sẽ tạo mới)     │
├ Bước 3 — Thông tin thêm (nếu cần) ────────────────────────┤
│ Cookie: [ ... ]     ← chỉ hiện với nguồn thực sự cần      │
├───────────────────────────────────────────────────────────┤
│ [ Tải chương ]  [ Huỷ ]  [ Xoá form ]                     │
├ Nhật ký ──────────────────────────────────────────────────┤
│ 📄 totalPages=18 …                                        │
└───────────────────────────────────────────────────────────┘
```

Vài điểm đáng chú ý:

- **Dán link là tự chọn đúng nguồn.** Mỗi nguồn khai báo tên miền của mình, ứng dụng
  so khớp rồi chuyển combo giúp bạn. Không nhận ra thì tự chọn tay.
- **Không còn đường dẫn set cứng.** Bạn tự *ghim* các thư mục hay dùng trên máy mình;
  danh sách được lưu lại nên lần mở sau vẫn còn. Nút `Duyệt & ghim…` vừa chọn vừa ghim
  trong một thao tác.
- **Thư mục con chọn bằng menu duyệt cây.** Nút `Chọn thư mục con` liệt kê các thư mục
  con đang có; trỏ vào một mục là hiện tiếp cấp sâu hơn, bao nhiêu cấp cũng được
  (`Chap 11` → `11.2` → `phụ lục`…). Mỗi cấp chỉ được đọc khi bạn thực sự trỏ tới nên
  không bị khựng dù thư viện lớn. Tên thư mục sắp xếp kiểu tự nhiên: `Chap 2` đứng trước
  `Chap 10`. Ô này vẫn gõ tay được cho thư mục chưa tồn tại — khi đó dòng xem trước ghi
  rõ `(sẽ tạo mới)`.
- **Tên chương tự đoán từ link.** Dán link có số chương (`.../chapter-94`, `.../4-wa`)
  là ô thư mục con tự điền `Chap 94`. Gợi ý chỉ điền vào ô đang trống hoặc đang chứa
  đúng gợi ý cũ — thứ bạn tự gõ hay tự chọn trong menu không bao giờ bị đè.
  Nguồn nào chỉ có id trong URL (`/episode/2550912965546773158`) thì **không đoán bừa**,
  vì tên thư mục sinh ra sẽ vô nghĩa.
- **Không cần tạo thư mục trước** — phần tải ảnh tự tạo khi bắt đầu. Muốn tạo sẵn thì
  bấm `Tạo thư mục`, hộp thoại điền sẵn tên đoán được, gõ nhiều cấp cũng được
  (`Chap 11\11.2`).
- Dòng `→ Lưu vào:` cho xem trước đường dẫn cuối trước khi bấm tải, kèm chú thích
  `(sẽ tạo mới)` nếu thư mục chưa tồn tại.
- **Hiển thị được cả tiếng Nhật.** Giao diện dùng phông logic của Java (`Dialog`,
  `Monospaced`) chứ không phải "Segoe UI", vì chỉ phông logic mới có cơ chế dò phông
  dự phòng — nhờ vậy tên thư mục kiểu `ヨーコー` không bị hiện thành ô vuông. Ngược lại
  cũng không dùng phông Nhật (Yu Gothic UI, Meiryo UI...) vì chúng thiếu nguyên âm
  tiếng Việt như "ươ" và vẽ dấu `\` thành `¥`.
- Để trống phần thư mục thì mỗi nguồn tự áp quy ước riêng của nó, tính từ **thư mục gốc**
  (đổi trong menu `Cấu hình → Đổi thư mục gốc…`).
- **Cookie / token được nhớ lại** giữa các lần chạy, khỏi phải dán lại.
  Xoá bằng `Cấu hình → Quên cookie / token đã lưu`.
- Ô **Nhật ký** hiển thị đúng những dòng tiến độ mà phần tải ảnh in ra.
- **Tạo kèm file .psd** — xem mục 3 bên dưới.

Cấu hình lưu tại `%USERPROFILE%\.fetch_manga\settings.properties`.
⚠️ Cookie nằm trong file này ở **dạng văn bản thuần**, đừng chia sẻ file đó cho ai.

---

## 3. Tạo file .psd hai lớp

Mỗi ảnh có thể được kèm một file `.psd` riêng: `001.jpg` sẽ có thêm `001.psd` nằm ngay
cạnh, bên trong gồm lớp **Background** (khoá) và lớp **Layer 1** nhân bản ở trên — đúng
kết quả của thao tác Duplicate Layer trong Photoshop.

Hai cách dùng:

| Cách | Thao tác |
|---|---|
| Tự động | Bật ô `Tạo kèm file .psd cho mỗi ảnh` cạnh nút Tải. Tải xong là sinh luôn. |
| Thủ công | `Công cụ → Tạo .psd cho thư mục...` cho thư mục ảnh có sẵn, chọn được cả thư mục con. |

- Ảnh nào **đã có** `.psd` thì bỏ qua, nên chạy lại nhiều lần không tốn công.
- Đọc được jpg, png, webp, bmp, gif, tif.
- Cách tự động cần **đã chọn thư mục lưu**; để trống thì nguồn tự quyết chỗ lưu, ứng dụng
  không biết ảnh nằm ở đâu nên sẽ ghi chú vào Nhật ký thay vì đoán mò — lúc đó dùng cách
  thủ công.

⚠️ **File .psd rất nặng.** Đo trên trang thật 1440×2048:

```
001.jpg  0.61 MB  ->  001.psd  12.22 MB   (gấp 20x)
9 trang  4.9 MB   ->           105.3 MB   (mất 0.5 giây)
```

Đây là bản chất của định dạng chứ không phải lỗi: PSD lưu điểm ảnh gần như không nén
(chỉ RLE), và cùng một ảnh phải nằm **ba bản** — lớp nền, lớp nhân bản, và ảnh hợp nhất
cho phần mềm không đọc được lớp. Photoshop tự lưu cũng ra cỡ tương đương. Một chương 20
trang sẽ chiếm khoảng 240 MB, nên chỉ bật khi thật sự cần chỉnh sửa.

### Cách kiểm chứng

`PsdWriter` tự ghi định dạng theo tài liệu *Adobe Photoshop File Formats Specification*
(không dùng thư viện ngoài). File xuất ra được kiểm bằng một bộ đọc PSD **độc lập**
(TwelveMonkeys imageio-psd — chỉ dùng lúc kiểm thử, không nằm trong bản phát hành):
đọc đúng 3 ảnh trong file (1 hợp nhất + 2 lớp), điểm ảnh khớp tuyệt đối với ảnh gốc ở cả
ba, tên lớp đúng, và chạy được với cả kích thước lẻ lẫn ảnh 1×1.

---

## 4. Upscale ảnh bằng AI

Phóng to trang truyện 2x/3x/4x bằng AI, chạy **ngay trên GPU của máy** (không gửi ảnh lên
mạng). Ảnh upscale **ghi đè lên ảnh gốc**, giữ nguyên tên và định dạng.

| Cách | Thao tác |
|---|---|
| Thủ công | `Công cụ → Upscale ảnh (AI)...` — chọn thư mục / ảnh lẻ (hoặc kéo thả vào), chọn mô hình, bấm `Xem thử` để so sánh trước/sau, rồi `Upscale & ghi đè`. |
| Tự động | Bật ô `Upscale AI sau khi tải (ghi đè ảnh)`. Dùng mô hình đã chọn lần cuối trong cửa sổ upscale. |

### Chọn kích thước

| Cách | Ví dụ | Cách làm |
|---|---|---|
| Theo tỉ lệ | x2, x3, x4 | AI phóng đúng tỉ lệ đó |
| Theo chiều rộng / chiều cao | rộng 960px (100–2000px) | Mỗi ảnh chạy AI ở tỉ lệ nhỏ nhất vượt mục tiêu (vd 690px → 2x = 1380px) rồi thu về đúng 960px, giữ tỉ lệ khung. Ảnh **đã đủ** 960px thì giữ nguyên, nên chạy lại bao nhiêu lần cũng không phóng thêm. |

Ở chế độ theo pixel, AI được phóng lên **khoảng gấp đôi** mục tiêu (vd 720px → 3x = 2160px)
rồi thu về đúng cỡ bằng bộ lọc **Lanczos-3** tự viết (`Resampler`): nét mực sau khi thu mượt và
sắc hơn hẳn so với phóng vừa đủ, và không dùng `Graphics2D.drawImage` vì bộ vẽ của Java không
gom điểm ảnh khi thu nhỏ nên nét mảnh bị răng cưa. Ảnh trung gian bị giới hạn cạnh dài 6000px
để không ngốn bộ nhớ. Mức khử nhiễu chỉ cho chọn những mức có ở mọi tỉ lệ của mô hình.

### Trang trắng đen không bị lẫn màu

Mạng AI chạy trên RGB nên hay tô nhẹ màu (vàng/xanh) quanh nét mực và tram, dù trang gốc là
trắng đen thuần. Đo trên trang thật: gốc lệch màu tối đa 1 mức, ảnh engine xuất ra lệch tới 62–96.
Vì vậy sau khi upscale, ứng dụng **tự nhận diện trang trắng đen** (lấy mẫu độ lệch R/G/B) và ép
về R=G=B; **file vẫn là RGB** (Photoshop mở ở chế độ RGB như thường), còn trang màu thật (bìa,
trang có mảng màu) được giữ nguyên màu.

### Khử nhiễu

Mặc định là **Bảo toàn nét (conservative)**: dọn vết nén JPEG/WebP của ảnh web (viền lốm đốm,
nét vỡ) mà không xoá tram. Ảnh nguồn đã sạch (raw chất lượng cao) có thể chọn *Không khử nhiễu*;
ảnh nén rất nặng thì *Mạnh*, nhưng mức này có thể làm tram chấm thành mảng xám. Chọn ngay ở
cửa sổ chính (dòng `Cỡ:` dưới ô tick) hoặc trong cửa sổ upscale. Riêng mô hình 4x của Real-CUGAN
làm nền trắng tụt xuống 253 nên chế độ theo pixel ưu tiên 2x/3x.

Cửa sổ xem thử có thanh kéo so sánh như các trang upscale: kéo vạch giữa để dời ranh giới,
lăn chuột để phóng to quanh con trỏ, kéo để di chuyển, nhấp đúp để vừa khung.

### Mô hình

| Mô hình | Phóng to | Hợp với |
|---|---|---|
| **Real-CUGAN** (mặc định) | 2x 3x 4x | Manga đen trắng lẫn tranh màu — nét mực sạch, ít làm nhoè tram |
| Real-CUGAN Pro | 2x 3x | Như trên, giữ chi tiết nhỏ tốt hơn chút |
| Real-ESRGAN AnimeVideo v3 | 2x 3x 4x | Nhanh nhất, làm nét mạnh tay |
| Real-ESRGAN x4plus anime | 4x | Tranh màu rất mượt, dễ xoá mất tram manga |
| Real-ESRGAN x4plus | 4x | Ảnh chụp đời thực |
| waifu2x CUnet | 2x 4x | Kinh điển, giữ nét gốc, khử nhiễu JPEG theo mức |

Đo trên RTX 4060, trang 1440×2048 → 2880×4096: Real-CUGAN 2x khoảng **1 giây/trang** khi
chạy cả loạt (xem thử một trang mất ~2.5 giây vì phải nạp mô hình).

### Engine

Lõi AI là các bản dựng **ncnn + Vulkan** chính chủ trên GitHub (realcugan / realesrgan /
waifu2x-ncnn-vulkan) — chạy được trên card NVIDIA, AMD lẫn Intel mà không cần Python hay CUDA.
Engine **không nằm trong bản exe**; lần đầu dùng engine nào thì ứng dụng tự tải engine đó
(35–45 MB) về `%USERPROFILE%\.fetch_manga\upscalers\`. File tải về được so với mã SHA-256
ghim sẵn trong `UpscaleEngine`, sai là từ chối cài.

### An toàn khi ghi đè

- Ảnh gốc chỉ bị thay **sau khi** bản upscale của chính nó đã ghi xong ra file tạm, bằng một
  lần đổi tên. Engine lỗi, bấm Huỷ hay tắt máy giữa chừng thì ảnh chưa xử lý vẫn nguyên vẹn.
- Một ảnh hỏng chỉ bị báo lỗi riêng, không làm dừng cả loạt.
- Chế độ tự động chỉ upscale **ảnh vừa tải trong lượt đó**, ảnh cũ trong cùng thư mục không bị
  phóng to thêm lần nữa. Còn công cụ thủ công thì chạy lại trên cùng ảnh **sẽ** phóng to tiếp.
- Upscale chạy **trước** bước đổi định dạng: nếu chọn PNG/JPG thì ảnh được ghi thẳng ra định
  dạng đó, chỉ nén một lần.
- jpg/webp ghi lại ở chất lượng 92%; gif/bmp/tif được lưu thành png.

---

## 5. Kiến trúc MVC

```
                 ┌──────────────────────────────┐
   người dùng ──▶│  view/                       │  Swing + menu dòng lệnh
                 │  MainWindow, FolderPanel,    │
                 │  SourceFormPanel, LogPanel,  │
                 │  ConsoleView, UiTheme        │
                 └──────────────┬───────────────┘
                                │ gọi Controller, nhận lại JobListener
                 ┌──────────────▼───────────────┐
                 │  controller/                 │  kiểm tra dữ liệu, chạy luồng nền
                 │  FetchController             │
                 └──────┬────────────────┬──────┘
                        │                │
        ┌───────────────▼──────┐  ┌──────▼────────────────────────┐
        │  model/              │  │  service/                     │
        │  SourceDescriptor    │  │  MangaFetcher (interface)     │
        │  FieldSpec/FieldType │  │  AbstractMangaFetcher         │
        │  FetchRequest        │  │  FetcherRegistry              │
        │  FetchOutcome        │  │  SettingsStore, ConsoleBridge │
        │  AppSettings         │  │  ChapterNameGuesser           │
        │  PinnedFolder        │  │  fetcher/  ← 14 adapter       │
        │                      │  │  site/     ← mã tải ảnh gốc   │
        │                      │  │  psd/      ← ghi file .psd    │
        │                      │  │  upscale/  ← upscale AI       │
        └──────────────────────┘  └───────────────────────────────┘
```

**Chiều phụ thuộc chỉ đi một hướng:** View → Controller → Service → Model.
Service không hề biết Swing là gì, nên cùng một bộ mã chạy được cả cửa sổ đồ hoạ
lẫn menu dòng lệnh (`--cli`).

### Vai trò từng tầng

| Gói | Trách nhiệm |
|---|---|
| `model` | Dữ liệu thuần: mô tả nguồn, mô tả ô nhập, yêu cầu tải, kết quả, cấu hình. Không import Swing, không gọi mạng. |
| `view` | Dựng giao diện, thu thập dữ liệu người dùng, hiển thị tiến độ. Không biết cách tải ảnh. |
| `controller` | Kiểm tra dữ liệu bắt buộc, bơm cấu hình vào yêu cầu, chạy trên luồng nền, báo kết quả ngược lên. |
| `service` | Hợp đồng `MangaFetcher` + registry + lưu cấu hình. |
| `service.fetcher` | 14 adapter, mỗi lớp nối một trang web vào hợp đồng chung. |
| `service.site` | Mã tải và giải mã ảnh của từng trang — **giữ nguyên**, chỉ đổi tên gói. |
| `service.psd` | `PsdWriter` ghi file .psd hai lớp, `PsdBatchGenerator` chạy cho cả thư mục. |
| `service.upscale` | `ImageUpscaler` gọi engine AI và ghi đè ảnh, `EngineInstaller` tải + kiểm SHA-256, `UpscaleModel` liệt kê mô hình hợp lệ. |

### Những thay đổi chính so với bản cũ

1. **Xoá khối `switch` 15 nhánh trong `main`.** Trước đây mỗi nguồn là một `case`
   trộn lẫn việc in menu, đọc bàn phím và gọi scraper. Giờ mỗi nguồn là một lớp
   `*Fetcher` cài `MangaFetcher` (mẫu **Strategy**), Controller chỉ gọi
   `fetcher.fetch(request)`.
2. **Form sinh tự động từ mô tả.** Mỗi nguồn khai báo `SourceDescriptor` gồm các
   `FieldSpec`; View đọc mô tả đó rồi tự dựng ô nhập. Thêm nguồn mới **không phải
   sửa View hay Controller**, chỉ cần viết lớp adapter và đăng ký một dòng trong
   `FetcherRegistry`.
3. **Bỏ đường dẫn set cứng.** `Manga.basePath` giờ có setter, Controller bơm giá trị
   từ `AppSettings`. `MangaPill` và `ComixTo` trước đây ghép thẳng
   `Paths.get(basePath, "comic", …)` nên bỏ qua thư mục người dùng chọn — đã sửa để
   dùng `createFolder(...)` như các nguồn khác. `SundayWebry` được bổ sung
   `setOutputFolder(...)` cho đồng bộ.
4. **Bỏ Spring Boot.** Dự án khai báo `spring-boot-starter-parent` nhưng chưa từng gọi
   `SpringApplication.run` — không dùng một tính năng nào của Spring. Bỏ đi giúp jar nhẹ
   hơn, khởi động nhanh hơn và `jpackage` đóng gói được mà không phải xử lý jar lồng nhau.
5. **Tách giao diện khỏi mã tải ảnh** bằng `JobListener` và `ConsoleBridge`.
   Các lớp trong `service.site` vẫn in bằng `System.out.println` như cũ; `ConsoleBridge`
   bắc cầu những dòng đó sang ô Nhật ký, nên gần 4000 dòng mã tải ảnh đang chạy ổn định
   **không phải sửa một dòng nào**.

---

## 6. Thêm một nguồn truyện mới

Ba bước, không đụng tới View/Controller:

```java
// 1. src/main/java/.../service/site/TrangMoi.java  — mã tải ảnh (kế thừa Manga)

// 2. src/main/java/.../service/fetcher/TrangMoiFetcher.java
public class TrangMoiFetcher extends AbstractMangaFetcher {

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("trangmoi", "Trang Mới")
                .example("https://trangmoi.com/chapter/123")
                .hosts("trangmoi.com")            // để tự nhận diện khi dán link
                .field(CommonFields.cookie())     // ô nhập sẽ tự hiện trên giao diện
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        TrangMoi scraper = prepare(new TrangMoi(), request);
        scraper.setCookie(request.get(CommonFields.COOKIE));
        scraper.setFolderName(folderOrNull(request));
        scraper.fetch(request.getUrl());
    }
}

// 3. FetcherRegistry: thêm một dòng
register(new TrangMoiFetcher());
```

---

## 7. Các nguồn đang hỗ trợ

Mangarawjp · MangaPlus · Comic Days · MangaPill · Comix.to · CuuTruyen ·
MangaFire (theo link) · MangaFire (nhập Chapter ID) · NicoManga · Shonen Jump+ ·
Heros-Web / Comici · Sunday Webry · pixiv Comic · Comic Walker / カドコミ

Với nguồn trả phí, ứng dụng cần cookie của **tài khoản đã mua chương đó** — nó chỉ tải
về những gì tài khoản của bạn có quyền đọc.

---

## 8. Ghi chú kỹ thuật

- **Java 17**, không dùng framework nào ngoài các thư viện tải/giải mã ảnh
  (jsoup, OkHttp, Gson, protobuf, Conscrypt, webp-imageio).
- Bản exe gói kèm Java runtime nên nặng khoảng **135 MB** (file zip ~57 MB).
  Muốn nhẹ hơn thì thêm `--add-modules` vào `build-exe.ps1`, nhưng phải thử kỹ vì
  Conscrypt và webp-imageio có phần native.
- `jpackage` xuất dạng `app-image` (một thư mục kèm exe). Muốn ra **file cài đặt**
  `.exe`/`.msi` thì cần cài thêm **WiX Toolset 3.x** rồi đổi `--type app-image`
  thành `--type exe` trong `build-exe.ps1`.
- `tools/MakeIcon.java` sinh icon ứng dụng, chạy bằng
  `java tools/MakeIcon.java tools/app.ico`.
