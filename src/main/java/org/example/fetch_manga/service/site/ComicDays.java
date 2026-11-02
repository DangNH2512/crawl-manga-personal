package org.example.fetch_manga.service.site;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Fetches manga images from Comic Days (comic-days.com) — uses GigaViewer platform.
 *
 * Flow:
 *  1. Fetch HTML page with Jsoup
 *  2. Parse JSON from <script id="episode-json" data-value="...">
 *  3. Extract page list from readableProduct.pageStructure.pages (type === "main")
 *  4. Check choJuGiga: chỉ "usagi" là KHÔNG scramble, còn lại → scramble (4×4 block)
 *  5. Download each image and descramble if needed, save to folder
 *
 * ⚠ ĐÃ SỬA 2026-07-29: trước đây code coi choJuGiga="baku" là KHÔNG scramble →
 *   ảnh xuất ra bị xáo trộn. Kiểm chứng bằng ảnh thật (episode 2551460909616541540,
 *   choJuGiga="baku") cho thấy ảnh raw CÓ scramble và phải descramble.
 *   Giống kết luận đã áp dụng cho Sunday Webry.
 */
public class ComicDays extends Manga {

    /** Cookie từ browser (cần cho chapter trả phí). Để null nếu là chapter free. */
    private String cookie = null;

    public void setCookie(String cookie) {
        this.cookie = (cookie != null && !cookie.isBlank()) ? cookie.trim() : null;
    }

    /** Thư mục lưu tuyệt đối. Nếu set, bỏ qua quy ước basePath\ComicDays\Chap N. */
    private String outputFolder = null;

    public void setOutputFolder(String path) {
        this.outputFolder = (path != null && !path.isBlank()) ? path.trim() : null;
    }

    /** Chất lượng JPEG khi ghi ảnh đã descramble. */
    private float quality = 0.92f;

    public void setQuality(float q) { this.quality = q; }

    private static final String SITE_URL = "https://comic-days.com";
    private static final String REFERER  = "https://comic-days.com/";

    // ── GigaViewer descramble constants ─────────────────────────────────────
    private static final int DIVIDE_NUM = 4;
    private static final int MULTIPLE   = 8;

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
        // e.g. https://comic-days.com/episode/2550912965546773158 → 2550912965546773158
        String[] parts = url.split("/");
        return parts[parts.length - 1].trim();
    }

    // ── parseAndDownload ─────────────────────────────────────────────────────
    @Override
    protected void parseAndDownload(String url, String chapNumber) throws Exception {

        System.out.println("🌐 Fetch HTML trang Comic Days...");

        // 1. Lấy HTML — truyền cookie nếu có (cần cho chapter trả phí)
        var jsoupConn = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .referrer(REFERER)
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .maxBodySize(0)
                .timeout(15000);

        if (cookie != null) {
            jsoupConn.header("Cookie", cookie);
        }

        Document doc = jsoupConn.get();

        // 2. Lấy JSON từ <script id="episode-json" data-value="...">
        Element episodeJson = doc.selectFirst("#episode-json");
        if (episodeJson == null) {
            throw new RuntimeException("Không tìm thấy #episode-json. " +
                    "Chapter này có thể cần đăng nhập hoặc mua ticket.");
        }

        String jsonStr = episodeJson.attr("data-value");
        if (jsonStr.isEmpty()) {
            throw new RuntimeException("data-value rỗng trong #episode-json.");
        }

        // 3. Parse JSON
        JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();
        JsonObject readableProduct = root.getAsJsonObject("readableProduct");

        boolean isPublic = readableProduct.get("isPublic").getAsBoolean();
        boolean hasPurchased = false;
        if (readableProduct.has("hasPurchased") && !readableProduct.get("hasPurchased").isJsonNull()) {
            hasPurchased = readableProduct.get("hasPurchased").getAsBoolean();
        }

        if (!isPublic && !hasPurchased) {
            throw new RuntimeException("Chapter này chưa được mua hoặc không miễn phí. " +
                    "Cần đăng nhập + mua ticket để download.");
        }

        JsonObject pageStructure = readableProduct.getAsJsonObject("pageStructure");
        String choJuGiga = pageStructure.has("choJuGiga")
                ? pageStructure.get("choJuGiga").getAsString()
                : "";

        // ⚠ Chỉ "usagi" = không scramble. "baku" VẪN CÓ scramble (đã kiểm chứng bằng ảnh thật).
        boolean scrambled = !choJuGiga.equals("usagi");

        System.out.println("🔧 Chế độ ảnh: choJuGiga=\"" + choJuGiga + "\" → "
                + (scrambled ? "CÓ scramble (4×4 block)" : "KHÔNG scramble"));

        // 4. Lọc pages type="main"
        JsonArray pages = pageStructure.getAsJsonArray("pages");
        List<PageInfo> pageInfos = new ArrayList<>();

        for (int i = 0; i < pages.size(); i++) {
            JsonObject page = pages.get(i).getAsJsonObject();
            if (!page.has("type")) continue;
            String type = page.get("type").getAsString();
            if (!"main".equals(type)) continue;
            if (!page.has("src")) continue;
            String src = page.get("src").getAsString();
            if (src.isEmpty()) continue;
            pageInfos.add(new PageInfo(src, scrambled));
        }

        if (pageInfos.isEmpty()) {
            throw new RuntimeException("Không tìm thấy ảnh nào (type=main) trong pageStructure.");
        }

        System.out.println("🖼  Tìm thấy " + pageInfos.size() + " trang, bắt đầu download...");

        // 5. Tạo folder và download song song
        Path folder;
        if (outputFolder != null) {
            folder = java.nio.file.Paths.get(outputFolder);
            Files.createDirectories(folder);
        } else {
            folder = createFolder("ComicDays", chapNumber);
        }
        System.out.println("📁 Lưu vào: " + folder);

        for (int i = 0; i < pageInfos.size(); i++) {
            final int index = i + 1;
            final PageInfo info = pageInfos.get(i);
            final String referer = url;

            Future<?> future = executor.submit(() -> downloadAndProcess(info, folder, index, referer));
            futures.add(future);
        }
    }

    // ── Download + Descramble + Save ─────────────────────────────────────────
    private void downloadAndProcess(PageInfo page, Path folder, int index, String referer) {
        int retry = 3;
        int[] delays = {0, 500, 1000};

        while (retry > 0) {
            int attempt = 3 - retry;
            try {
                if (attempt > 0) Thread.sleep(delays[attempt]);

                // 1. Download raw bytes
                byte[] raw = downloadBytes(page.src, referer);

                byte[] finalBytes;
                if (page.scrambled) {
                    // 2. Descramble
                    finalBytes = descramble(raw);
                } else {
                    finalBytes = raw;
                }

                // 3. Save
                String fileName = String.format("%03d.jpg", index);
                Path output = folder.resolve(fileName);
                Files.copy(new ByteArrayInputStream(finalBytes), output, StandardCopyOption.REPLACE_EXISTING);

                System.out.println("✔ [" + index + "] " + fileName
                        + (page.scrambled ? " (descrambled)" : ""));
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
        conn.setReadTimeout(15_000);
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

    // ── GigaViewer 4×4 Block Descramble ──────────────────────────────────────
    //
    // Thuật toán từ CoreView.mjs (hakuneko):
    //   DIVIDE_NUM=4, MULTIPLE=8
    //   cell_w = floor(width  / (4*8)) * 8
    //   cell_h = floor(height / (4*8)) * 8
    //
    //   Với mỗi e trong [0..15]:
    //     src_x = (e % 4) * cell_w
    //     src_y = (e / 4) * cell_h
    //     i     = (e % 4) * 4 + (e / 4)   ← hoán vị chéo
    //     dst_x = (i % 4) * cell_w
    //     dst_y = (i / 4) * cell_h
    //   Copy block từ src → dst trong ảnh mới
    //
    private byte[] descramble(byte[] rawJpeg) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(rawJpeg));
        if (src == null) throw new RuntimeException("Không đọc được ảnh JPEG");

        int width  = src.getWidth();
        int height = src.getHeight();

        int cellW = (int) Math.floor((double) width  / (DIVIDE_NUM * MULTIPLE)) * MULTIPLE;
        int cellH = (int) Math.floor((double) height / (DIVIDE_NUM * MULTIPLE)) * MULTIPLE;

        BufferedImage dst = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();

        // Vẽ toàn bộ ảnh gốc lên dst trước (xử lý phần không thuộc cells)
        g.drawImage(src, 0, 0, null);

        for (int e = 0; e < DIVIDE_NUM * DIVIDE_NUM; e++) {
            int srcX = (e % DIVIDE_NUM) * cellW;
            int srcY = (e / DIVIDE_NUM) * cellH;

            int i    = (e % DIVIDE_NUM) * DIVIDE_NUM + (e / DIVIDE_NUM);
            int dstX = (i % DIVIDE_NUM) * cellW;
            int dstY = (i / DIVIDE_NUM) * cellH;

            // drawImage(src, dstX, dstY, dstX+w, dstY+h, srcX, srcY, srcX+w, srcY+h, null)
            g.drawImage(src, dstX, dstY, dstX + cellW, dstY + cellH,
                        srcX, srcY, srcX + cellW, srcY + cellH, null);
        }
        g.dispose();

        return writeJpg(dst);
    }

    /** Ghi JPEG với chất lượng chỉ định (mặc định 0.92, giống HerosWeb/SundayWebry). */
    private byte[] writeJpg(BufferedImage img) throws Exception {
        javax.imageio.ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(Math.max(0f, Math.min(1f, quality)));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            w.setOutput(ios);
            w.write(null, new javax.imageio.IIOImage(img, null, null), p);
        } finally {
            w.dispose();
        }
        return bos.toByteArray();
    }
}
