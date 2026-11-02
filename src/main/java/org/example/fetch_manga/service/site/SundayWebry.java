package org.example.fetch_manga.service.site;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Fetches manga images from Sunday Webry (sunday-webry.com) — nền tảng GigaViewer
 * (data-env="giga_production2"), giống hệt Shonen Jump+ / Comic Days.
 *
 * Hỗ trợ:
 *  - Episode miễn phí: https://www.sunday-webry.com/episode/XXXXX  (không cần cookie)
 *  - Episode trả phí (đã mua bằng point): dán cookie tài khoản đã đăng nhập.
 *
 * Flow:
 *  1. Fetch HTML page bằng Jsoup (kèm cookie nếu có).
 *  2. Parse JSON trong <script id="episode-json" data-value="...">.
 *  3. Lấy pages type="main" trong readableProduct.pageStructure.pages.
 *  4. Ảnh là JPEG hợp lệ nhưng bị xáo trộn theo lưới 4×4 (16 ô) — descramble bằng
 *     hoán vị chuyển vị (transpose) của GigaViewer.
 *  5. Tải + descramble + lưu PNG (mặc định) hoặc JPG.
 *
 * Về chất lượng: descramble buộc phải giải nén ra pixel rồi xếp lại ô, nên không
 * tránh được một lần mã hoá lại. Ghi PNG thì lần đó là lossless; ghi JPEG sẽ chồng
 * đời nén thứ hai lên ảnh vốn đã lossy. Ảnh không scramble được ghi thẳng byte gốc,
 * không qua ImageIO, nên hoàn toàn không mất mát.
 *
 * ⚠ LƯU Ý QUAN TRỌNG (đã kiểm chứng bằng ảnh thật 2026-07-22):
 *   Với Sunday Webry, choJuGiga="baku" LÀ CÓ SCRAMBLE và PHẢI descramble.
 *   (Khác với giả định cũ trong ShonenJumpPlus/ComicDays coi baku = không scramble.)
 *   → Ở đây mặc định descramble cho mọi mode trừ "usagi".
 */
public class SundayWebry extends Manga {

    /** Cookie từ browser (cần cho episode trả phí). Để null nếu episode free. */
    private String cookie = null;

    public void setCookie(String cookie) {
        this.cookie = (cookie != null && !cookie.isBlank()) ? cookie.trim() : null;
    }

    /** Tên folder do người dùng chỉ định (Enter = tự lấy theo tên series). */
    private String folderOverride = null;

    public void setFolderOverride(String name) {
        this.folderOverride = (name != null && !name.isBlank()) ? name.trim() : null;
    }

    /** Số chương do người dùng ép (Enter = tự lấy từ JSON "number"). */
    private String chapOverride = null;

    public void setChapOverride(String chap) {
        this.chapOverride = (chap != null && !chap.isBlank()) ? chap.trim() : null;
    }

    /** Thư mục lưu tuyệt đối. Nếu set, bỏ qua quy ước basePath\series\Chap N. */
    private String outputFolder = null;

    public void setOutputFolder(String path) {
        this.outputFolder = (path != null && !path.isBlank()) ? path.trim() : null;
    }

    /**
     * Định dạng ghi ảnh <b>đã descramble</b>: {@code "png"} (mặc định) hoặc {@code "jpg"}.
     *
     * <p>Descramble buộc phải giải nén JPEG ra pixel rồi xếp lại 16 ô, nên không
     * tránh được việc mã hoá lại một lần nữa. Ghi PNG giữ đúng từng pixel sau khi
     * xếp; ghi JPEG sẽ chồng thêm một đời nén lossy lên ảnh vốn đã lossy.</p>
     *
     * <p>Ảnh <b>không</b> scramble không bị ảnh hưởng — luôn ghi thẳng byte gốc của
     * server, không qua ImageIO. Cờ {@code scrambled} là của cả episode chứ không
     * phải từng trang, nên trong một thư mục chương mọi file luôn cùng đuôi.</p>
     */
    private String imageFormat = "png";

    public void setImageFormat(String fmt) {
        if (fmt == null || fmt.isBlank()) return;
        String f = fmt.trim().toLowerCase();
        if (f.equals("jpeg")) f = "jpg";
        if (f.equals("png") || f.equals("jpg")) this.imageFormat = f;
    }

    /** Chất lượng JPEG — chỉ dùng khi {@link #imageFormat} là {@code "jpg"}. */
    private float quality = 0.92f;

    public void setQuality(float q) { this.quality = q; }

    private static final String REFERER = "https://www.sunday-webry.com/";

    // ── GigaViewer descramble constants ─────────────────────────────────────
    private static final int DIVIDE_NUM = 4;
    private static final int MULTIPLE = 8;

    // ── Data class ──────────────────────────────────────────────────────────
    private static class PageInfo {
        final String src;
        final boolean scrambled;

        PageInfo(String src, boolean scrambled) {
            this.src = src;
            this.scrambled = scrambled;
        }
    }

    // ── extractChapNumber ───────────────────────────────────────────────────
    @Override
    protected String extractChapNumber(String url) {
        // https://www.sunday-webry.com/episode/3269754496548997914 → 3269754496548997914
        // (chỉ dùng làm fallback; số chương thật lấy từ JSON "number")
        String[] parts = url.split("[/?#]");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank()) return parts[i].trim();
        }
        return url.trim();
    }

    // ── parseAndDownload ─────────────────────────────────────────────────────
    @Override
    protected void parseAndDownload(String url, String chapNumber) throws Exception {

        System.out.println("🌐 Fetch HTML trang Sunday Webry...");

        // 1. Lấy HTML — kèm cookie nếu có (cần cho episode trả phí)
        var jsoupConn = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .referrer(REFERER)
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .maxBodySize(0)
                .timeout(20000);

        if (cookie != null) {
            jsoupConn.header("Cookie", cookie);
        }

        Document doc = jsoupConn.get();

        // 2. Lấy JSON từ <script id="episode-json" data-value="...">
        Element episodeJson = doc.selectFirst("#episode-json");
        if (episodeJson == null) {
            throw new RuntimeException("Không tìm thấy #episode-json. " +
                    "Episode này có thể cần đăng nhập hoặc mua point.\n" +
                    "→ Đăng nhập trên browser, mở episode, F12 → Network → request episode " +
                    "→ Copy Cookie header rồi nhập lại.");
        }

        String jsonStr = episodeJson.attr("data-value");
        if (jsonStr.isEmpty()) {
            throw new RuntimeException("data-value rỗng trong #episode-json.");
        }

        // 3. Parse JSON
        JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();
        JsonObject readableProduct = root.getAsJsonObject("readableProduct");

        boolean isPublic = readableProduct.has("isPublic")
                && !readableProduct.get("isPublic").isJsonNull()
                && readableProduct.get("isPublic").getAsBoolean();

        boolean hasPurchased = readableProduct.has("hasPurchased")
                && !readableProduct.get("hasPurchased").isJsonNull()
                && readableProduct.get("hasPurchased").getAsBoolean();

        if (!isPublic && !hasPurchased) {
            throw new RuntimeException("Episode này chưa mua hoặc không miễn phí " +
                    "(isPublic=false, hasPurchased=false).\n" +
                    "→ Cần đăng nhập + mua point trên browser, rồi copy cookie để tải.");
        }

        // Title episode + series (để log và đặt tên folder)
        String episodeTitle = getStr(readableProduct, "title");
        String seriesTitle = "";
        if (readableProduct.has("series") && readableProduct.get("series").isJsonObject()) {
            seriesTitle = getStr(readableProduct.getAsJsonObject("series"), "title");
        }
        if (!episodeTitle.isEmpty()) System.out.println("📖 Episode: " + episodeTitle);
        if (!seriesTitle.isEmpty()) System.out.println("📚 Series : " + seriesTitle);

        // Số chương: ưu tiên chapOverride > "number" trong JSON > id trên URL
        String chapLabel = chapNumber;
        if (readableProduct.has("number") && !readableProduct.get("number").isJsonNull()) {
            try {
                chapLabel = String.valueOf(readableProduct.get("number").getAsInt());
            } catch (Exception ignore) {
            }
        }
        if (chapOverride != null) {
            chapLabel = chapOverride;
        }

        JsonObject pageStructure = readableProduct.getAsJsonObject("pageStructure");
        String choJuGiga = pageStructure.has("choJuGiga") && !pageStructure.get("choJuGiga").isJsonNull()
                ? pageStructure.get("choJuGiga").getAsString()
                : "";

        // ⚠ Với Sunday Webry: baku = CÓ scramble. Chỉ "usagi" là không scramble.
        boolean scrambled = !choJuGiga.equals("usagi");

        System.out.println("🔧 choJuGiga=\"" + choJuGiga + "\" → "
                + (scrambled ? "CÓ scramble (4×4 block) → sẽ descramble" : "KHÔNG scramble"));

        // 4. Lọc pages type="main"
        JsonArray pages = pageStructure.getAsJsonArray("pages");
        List<PageInfo> pageInfos = new ArrayList<>();

        for (int i = 0; i < pages.size(); i++) {
            JsonObject page = pages.get(i).getAsJsonObject();
            if (!page.has("type")) continue;
            if (!"main".equals(page.get("type").getAsString())) continue;
            if (!page.has("src")) continue;
            String src = page.get("src").getAsString();
            if (src.isEmpty()) continue;
            pageInfos.add(new PageInfo(src, scrambled));
        }

        if (pageInfos.isEmpty()) {
            throw new RuntimeException("Không tìm thấy ảnh nào (type=main) trong pageStructure.");
        }

        System.out.println("🖼  Tìm thấy " + pageInfos.size() + " trang, bắt đầu download...");

        // 5. Tạo folder: ưu tiên folderOverride > tên series > "SundayWebry"
        String folderName;
        if (folderOverride != null) {
            folderName = folderOverride;
        } else if (!seriesTitle.isEmpty()) {
            folderName = sanitize(seriesTitle);
        } else {
            folderName = "SundayWebry";
        }

        Path folder = (outputFolder != null)
                ? Paths.get(outputFolder)
                : Paths.get(basePath, folderName, "Chap " + chapLabel);
        Files.createDirectories(folder);
        System.out.println("📁 Lưu vào: " + folder);

        for (int i = 0; i < pageInfos.size(); i++) {
            final int index = i + 1;
            final PageInfo info = pageInfos.get(i);
            final String pageReferer = url;

            Future<?> future = executor.submit(() -> downloadAndProcess(info, folder, index, pageReferer));
            futures.add(future);
        }
    }

    // ── Download + Descramble + Save ─────────────────────────────────────────
    private void downloadAndProcess(PageInfo page, Path folder, int index, String referer) {
        int retry = 3;
        int[] delays = { 0, 500, 1000 };

        while (retry > 0) {
            int attempt = 3 - retry;
            try {
                if (attempt > 0) Thread.sleep(delays[attempt]);

                byte[] raw = downloadBytes(page.src, referer);

                byte[] finalBytes;
                String ext;
                if (page.scrambled) {
                    // Phải vẽ lại nên buộc mã hoá lại; PNG tránh nén chồng nén.
                    finalBytes = encode(descramble(raw));
                    ext = imageFormat;
                } else {
                    // Byte server trả về đã là ảnh hoàn chỉnh — ghi thẳng, không đụng vào.
                    finalBytes = raw;
                    ext = sniffExt(raw);
                }

                String fileName = String.format("%03d.%s", index, ext);
                Path output = folder.resolve(fileName);
                Files.copy(new ByteArrayInputStream(finalBytes), output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                System.out.println("✔ [" + index + "] " + fileName
                        + (page.scrambled ? " (descrambled)" : " (nguyên bản)"));
                return;

            } catch (Exception e) {
                retry--;
                if (retry == 0) {
                    System.err.println("❌ Fail [" + index + "]: " + page.src + " | " + e.getMessage());
                }
            }
        }
    }

    // ── HTTP Download ─────────────────────────────────────────────────────────
    private byte[] downloadBytes(String imageUrl, String referer) throws Exception {
        URL url = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
        if (cookie != null) {
            conn.setRequestProperty("Cookie", cookie);
        }

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("HTTP " + code);
        }

        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        }
    }

    // ── GigaViewer 4×4 Block Descramble (transpose) ──────────────────────────
    //   cell_w = floor(width  / (4*8)) * 8
    //   cell_h = floor(height / (4*8)) * 8
    //   ô nguồn e (0..15) → ô đích i = (e%4)*4 + (e/4)  (hoán vị chuyển vị)
    //   Phần lề dư (không chia hết cho cell) giữ nguyên nhờ vẽ full ảnh trước.
    private BufferedImage descramble(byte[] rawJpeg) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(rawJpeg));
        if (src == null) throw new RuntimeException("Không đọc được ảnh JPEG");

        int width = src.getWidth();
        int height = src.getHeight();

        int cellW = (int) Math.floor((double) width / (DIVIDE_NUM * MULTIPLE)) * MULTIPLE;
        int cellH = (int) Math.floor((double) height / (DIVIDE_NUM * MULTIPLE)) * MULTIPLE;

        // Trang manga phần lớn là grayscale. Ép lên INT_RGB sẽ khiến PNG nặng gấp
        // khoảng 3 lần mà không thêm một bit thông tin nào, nên giữ nguyên grayscale
        // khi ảnh nguồn vốn đã là grayscale.
        int type = (src.getType() == BufferedImage.TYPE_BYTE_GRAY)
                ? BufferedImage.TYPE_BYTE_GRAY
                : BufferedImage.TYPE_INT_RGB;

        BufferedImage dst = new BufferedImage(width, height, type);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, null); // giữ phần lề dư

        for (int e = 0; e < DIVIDE_NUM * DIVIDE_NUM; e++) {
            int srcX = (e % DIVIDE_NUM) * cellW;
            int srcY = (e / DIVIDE_NUM) * cellH;

            int i = (e % DIVIDE_NUM) * DIVIDE_NUM + (e / DIVIDE_NUM);
            int dstX = (i % DIVIDE_NUM) * cellW;
            int dstY = (i / DIVIDE_NUM) * cellH;

            g.drawImage(src, dstX, dstY, dstX + cellW, dstY + cellH,
                    srcX, srcY, srcX + cellW, srcY + cellH, null);
        }
        g.dispose();

        return dst;
    }

    /** Mã hoá ảnh đã descramble theo định dạng người dùng chọn. */
    private byte[] encode(BufferedImage img) throws Exception {
        if (imageFormat.equals("png")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        }
        return writeJpg(img);
    }

    /**
     * Đoán đuôi file từ magic bytes, dùng cho ảnh ghi thẳng không qua ImageIO.
     * GigaViewer hiện trả JPEG, nhưng sniff để khỏi đặt sai tên nếu server đổi sang
     * WebP — đặt sai đuôi sẽ làm pipeline upscale/redraw đọc hỏng về sau.
     */
    private static String sniffExt(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return "jpg";
    }

    // ── Ghi JPEG với chất lượng chỉ định (giống HerosWeb) ────────────────────
    private byte[] writeJpg(BufferedImage img) throws Exception {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(Math.max(0f, Math.min(1f, quality)));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            w.setOutput(ios);
            w.write(null, new IIOImage(img, null, null), p);
        } finally {
            w.dispose();
        }
        return bos.toByteArray();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private static String getStr(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : "";
    }

    /** Bỏ ký tự không hợp lệ cho tên folder Windows. */
    private static String sanitize(String s) {
        String cleaned = s.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        return cleaned.isEmpty() ? "SundayWebry" : cleaned;
    }
}
