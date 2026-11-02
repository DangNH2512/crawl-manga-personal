package org.example.fetch_manga.service.site;

import com.google.gson.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * MangaFire - Tải ảnh từ mangafire.to
 *
 * Cách dùng:
 *   MangaFire mf = new MangaFire();
 *   mf.fetch("https://mangafire.to/read/senryuu-shoujoo.2o1l5/en/chapter-155");
 *
 * URL format: https://mangafire.to/read/{manga-slug}/en/chapter-{N}
 *
 * Cơ chế:
 *   1. Dùng curl để tải HTML trang đọc (bypass Cloudflare UA check).
 *   2. Parse HTML tìm data-id = chapter ID (hoặc tìm trong JS vars).
 *   3. Gọi /ajax/read/chapter/{chapterId} → JSON có result.images.
 *   4. Mỗi ảnh: [url, width, scrambleKey]. Nếu scrambleKey > 0 → unscramble tiles.
 *   5. Lưu file theo thứ tự.
 */
public class MangaFire extends Manga {

    private static final String BASE_URL  = "https://mangafire.to";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";

    // ──────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetch một chapter từ URL reader của MangaFire.
     * VD: https://mangafire.to/read/senryuu-shoujoo.2o1l5/en/chapter-155
     */
    public void fetchChapter(String readerUrl) throws Exception {
        fetchChapter(readerUrl, null);
    }

    public void fetchChapter(String readerUrl, String saveFolder) throws Exception {
        System.out.println("🔍 URL: " + readerUrl);

        // ── Bước 1: Lấy HTML trang đọc bằng curl ────────────────────────────
        String html = curlGet(readerUrl);
        if (html.isEmpty()) throw new RuntimeException("Không tải được trang reader!");

        // ── Bước 2: Tìm chapter ID trong HTML ────────────────────────────────
        String chapterId = parseChapterId(html, readerUrl);
        System.out.println("📖 Chapter ID: " + chapterId);

        // ── Bước 3: Gọi AJAX API để lấy danh sách ảnh ────────────────────────
        String ajaxUrl = BASE_URL + "/ajax/read/chapter/" + chapterId;
        System.out.println("🌐 Gọi API: " + ajaxUrl);
        String jsonStr = curlGet(ajaxUrl, readerUrl); // Referer = reader page

        if (jsonStr.isEmpty() || !jsonStr.trim().startsWith("{")) {
            throw new RuntimeException("API trả về dữ liệu không hợp lệ:\n" + jsonStr.substring(0, Math.min(200, jsonStr.length())));
        }

        // ── Bước 4: Parse JSON ────────────────────────────────────────────────
        JsonObject root    = JsonParser.parseString(jsonStr).getAsJsonObject();
        JsonObject result  = root.getAsJsonObject("result");
        JsonArray  images  = result.getAsJsonArray("images");

        System.out.println("📦 Tìm thấy " + images.size() + " ảnh");

        // ── Bước 5: Xác định thư mục lưu ─────────────────────────────────────
        String chapNum = extractChapNumber(readerUrl);
        Path folder;
        if (saveFolder != null && !saveFolder.isEmpty()) {
            folder = Paths.get(saveFolder);
        } else {
            // Tự tạo từ slug + chapter
            String mangaSlug = parseMangaSlug(readerUrl);
            folder = Paths.get(basePath, "mangafire", mangaSlug, "chapter-" + chapNum);
        }
        Files.createDirectories(folder);
        System.out.println("📁 Lưu vào: " + folder.toAbsolutePath());

        // ── Bước 6: Download & unscramble mỗi ảnh ────────────────────────────
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futs = new ArrayList<>();

        for (int i = 0; i < images.size(); i++) {
            final int idx = i;
            final JsonArray imgArr = images.get(i).getAsJsonArray();

            futs.add(pool.submit(() -> {
                try {
                    String imgUrl     = imgArr.get(0).getAsString();
                    int    scramble   = imgArr.size() > 2 ? imgArr.get(2).getAsInt() : 0;
                    downloadAndSave(imgUrl, folder, idx + 1, scramble, readerUrl);
                } catch (Exception e) {
                    System.err.println("❌ Lỗi ảnh [" + (idx + 1) + "]: " + e.getMessage());
                }
                return null;
            }));
        }

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.MINUTES);
        System.out.println("✅ Hoàn thành " + images.size() + " ảnh!");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tải + lưu ảnh (có unscramble nếu cần)
    // ──────────────────────────────────────────────────────────────────────────

    private void downloadAndSave(String imgUrl, Path folder, int index, int scramble, String referer) throws Exception {
        byte[] bytes = httpGet(imgUrl, referer);
        String ext   = imgUrl.contains(".png") ? "png" : "webp";

        // Detect webp / png
        if (imgUrl.contains(".webp")) ext = "webp";
        else if (imgUrl.contains(".png")) ext = "png";
        else ext = "jpg";

        String fileName = String.format("%03d.%s", index, ext);
        Path outPath    = folder.resolve(fileName);

        if (scramble > 0) {
            // Unscramble tiles rồi save
            BufferedImage orig     = ImageIO.read(new ByteArrayInputStream(bytes));
            if (orig == null) throw new RuntimeException("Không đọc được ảnh: " + imgUrl);
            BufferedImage unscram  = unscramble(orig, scramble);
            String saveExt = ext.equals("webp") ? "png" : ext; // Java không write webp natively
            Path finalPath = folder.resolve(String.format("%03d.%s", index, saveExt));
            ImageIO.write(unscram, saveExt.equals("png") ? "PNG" : "JPEG", finalPath.toFile());
            System.out.println("✔ [" + index + "] " + finalPath.getFileName() + " (unscrambled)");
        } else {
            Files.write(outPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("✔ [" + index + "] " + fileName);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Unscramble logic (port từ JS của HakuNeko/MangaFire)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * MangaFire chia ảnh thành lưới tile, đổi chỗ theo công thức:
     *   tileX_src  = (W - tileX_dst + scrambleKey) % W
     *   tileY_src  = (H - tileY_dst + scrambleKey) % H
     * (với tile cuối cùng hàng/cột - giữ nguyên)
     */
    private BufferedImage unscramble(BufferedImage src, int e) {
        int imgW = src.getWidth();
        int imgH = src.getHeight();

        int f = 5;
        int s = Math.min(200, (int) Math.ceil((double) imgW / f));
        int h = Math.min(200, (int) Math.ceil((double) imgH / f));
        int W = (int) Math.ceil((double) imgW / s) - 1;
        int d = (int) Math.ceil((double) imgH / h) - 1;

        BufferedImage dst = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = dst.createGraphics();

        for (int y = 0; y <= d; y++) {
            for (int m = 0; m <= W; m++) {
                int x = m;
                int l = y;
                if (m < W) {
                    x = (W - m + e) % W;
                }
                if (y < d) {
                    l = (d - y + e) % d;
                }

                int tileW = Math.min(s, imgW - m * s);
                int tileH = Math.min(h, imgH - y * h);

                // src tile tại (x*s, l*h) → dst tại (m*s, y*h)
                g2.drawImage(src,
                        m * s, y * h, m * s + tileW, y * h + tileH,
                        x * s, l * h, x * s + tileW, l * h + tileH,
                        null);
            }
        }
        g2.dispose();
        return dst;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Parse chapter ID từ HTML trang reader
    // ──────────────────────────────────────────────────────────────────────────

    private String parseChapterId(String html, String readerUrl) throws Exception {
        // Cách 1: Tìm data-id trên thẻ #reading-content hoặc .reading-content
        Document doc = Jsoup.parse(html, BASE_URL);
        
        // Thử các selector phổ biến của MangaFire
        String[] selectors = {
            "#reading-content[data-id]",
            "[data-reading-id]",
            "div[data-id]",
            ".reading[data-id]",
            "section[data-id]"
        };
        for (String sel : selectors) {
            var el = doc.selectFirst(sel);
            if (el != null) {
                String id = el.attr("data-id");
                if (!id.isEmpty()) return id;
                id = el.attr("data-reading-id");
                if (!id.isEmpty()) return id;
            }
        }

        // Cách 2: Tìm trong JS - pattern: "chapter":{"id":123456
        Pattern p2 = Pattern.compile("\"chapter\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*(\\d+)");
        Matcher m2 = p2.matcher(html);
        if (m2.find()) return m2.group(1);

        // Cách 3: Tìm chapId trong script vars
        Pattern p3 = Pattern.compile("chapId\\s*[=:]\\s*['\"]?(\\d+)");
        Matcher m3 = p3.matcher(html);
        if (m3.find()) return m3.group(1);

        // Cách 4: Tìm trong URL của AJAX call trong JS
        Pattern p4 = Pattern.compile("ajax/read/chapter/(\\d+)");
        Matcher m4 = p4.matcher(html);
        if (m4.find()) return m4.group(1);

        // Cách 5: Tìm bất kỳ data-id nào chứa số lớn (chapter ID thường > 1000)
        Pattern p5 = Pattern.compile("data-id=[\"'](\\d{4,})[\"']");
        Matcher m5 = p5.matcher(html);
        if (m5.find()) return m5.group(1);

        // Cách 6: Tìm reading-id trong JSON script
        Pattern p6 = Pattern.compile("\"reading_id\"\\s*:\\s*(\\d+)");
        Matcher m6 = p6.matcher(html);
        if (m6.find()) return m6.group(1);

        throw new RuntimeException(
            "Không tìm thấy chapter ID trong HTML!\n"
            + "Hãy mở trang " + readerUrl + " trên trình duyệt, F12 → Network → lọc XHR\n"
            + "tìm request '/ajax/read/chapter/XXXXX' và lấy số XXXXX, rồi dùng fetchById()."
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Fetch trực tiếp bằng chapter numeric ID (khi biết trước)
    // ──────────────────────────────────────────────────────────────────────────

    public void fetchById(String chapterId, String saveFolder) throws Exception {
        System.out.println("📖 Fetch chapter ID: " + chapterId);

        String ajaxUrl = BASE_URL + "/ajax/read/chapter/" + chapterId;
        System.out.println("🌐 Gọi API: " + ajaxUrl);
        String jsonStr = curlGet(ajaxUrl, BASE_URL + "/");

        if (jsonStr.isEmpty() || !jsonStr.trim().startsWith("{")) {
            throw new RuntimeException("API trả về:\n" + jsonStr.substring(0, Math.min(300, jsonStr.length())));
        }

        JsonObject root   = JsonParser.parseString(jsonStr).getAsJsonObject();
        JsonObject result = root.getAsJsonObject("result");
        JsonArray  images = result.getAsJsonArray("images");

        System.out.println("📦 Tìm thấy " + images.size() + " ảnh");

        Path folder = Paths.get(saveFolder);
        Files.createDirectories(folder);
        System.out.println("📁 Lưu vào: " + folder.toAbsolutePath());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futs = new ArrayList<>();

        for (int i = 0; i < images.size(); i++) {
            final int idx = i;
            final JsonArray arr = images.get(i).getAsJsonArray();
            futs.add(pool.submit(() -> {
                try {
                    String imgUrl   = arr.get(0).getAsString();
                    int    scramble = arr.size() > 2 ? arr.get(2).getAsInt() : 0;
                    downloadAndSave(imgUrl, folder, idx + 1, scramble, BASE_URL + "/");
                } catch (Exception e) {
                    System.err.println("❌ Lỗi ảnh [" + (idx + 1) + "]: " + e.getMessage());
                }
                return null;
            }));
        }

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.MINUTES);
        System.out.println("✅ Hoàn thành " + images.size() + " ảnh!");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HTTP helpers (dùng curl để bypass Cloudflare)
    // ──────────────────────────────────────────────────────────────────────────

    /** Gọi curl GET, trả về body dạng String (UTF-8). */
    private String curlGet(String url) throws Exception {
        return curlGet(url, BASE_URL + "/");
    }

    private String curlGet(String url, String referer) throws Exception {
        List<String> cmd = new ArrayList<>(Arrays.asList(
            "curl", "-s", "-L",
            "--max-time", "30",
            "-H", "User-Agent: " + UA,
            "-H", "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "-H", "Accept-Language: en-US,en;q=0.9",
            "-H", "Referer: " + referer,
            "-H", "X-Requested-With: XMLHttpRequest"
        ));
        cmd.add(url);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String body = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        proc.waitFor();
        return body.trim();
    }

    /** Tải ảnh qua HttpURLConnection (ảnh CDN thường không cần bypass Cloudflare). */
    private byte[] httpGet(String imgUrl, String referer) throws Exception {
        int retry = 3;
        Exception last = null;
        for (int i = 0; i < retry; i++) {
            try {
                URL url = new URL(imgUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Referer", referer);
                conn.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
                int code = conn.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code + " for " + imgUrl);
                try (InputStream in = conn.getInputStream()) {
                    return in.readAllBytes();
                }
            } catch (Exception e) {
                last = e;
                Thread.sleep(500L * (i + 1));
            }
        }
        throw last;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private String parseMangaSlug(String url) {
        // https://mangafire.to/read/senryuu-shoujoo.2o1l5/en/chapter-155
        Pattern p = Pattern.compile("/read/([^/]+)/");
        Matcher m = p.matcher(url);
        return m.find() ? m.group(1) : "unknown";
    }

    @Override
    protected String extractChapNumber(String url) {
        // chapter-155 → 155
        Pattern p = Pattern.compile("chapter-([\\d.]+)");
        Matcher m = p.matcher(url);
        return m.find() ? m.group(1) : "unknown";
    }

    @Override
    protected void parseAndDownload(String url, String chapNumber) throws Exception {
        fetchChapter(url);
    }
}
