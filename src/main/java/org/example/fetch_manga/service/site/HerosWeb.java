package org.example.fetch_manga.service.site;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HerosWeb - Tải truyện từ heros-web.com (và các site dùng viewer comici.jp).
 *
 * Ảnh trên CDN bị xáo trộn (scrambled) theo lưới 4x4; viewer ghép lại bằng mảng
 * "scramble" lấy từ API /api/book/contentsInfo. Chương trả phí thì API này cần
 * đăng nhập, nên cách dùng: mở chương đã mua trong trình duyệt đã login →
 * F12 → Network → lọc "contentsInfo" → chuột phải → Copy as cURL, rồi dán vào.
 *
 * Toàn bộ thông tin (comici-viewer-id, user-id, authorization, cookie, referer)
 * đều được bóc thẳng từ đoạn cURL đó.
 */
public class HerosWeb {

    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";
    private static final String API = "https://heros-web.com/api/book/contentsInfo";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private float quality = 0.92f;
    public void setQuality(float q) { this.quality = q; }

    // ── Đã bóc từ cURL ────────────────────────────────────────────────────────
    private String auth, cookie, userId, referer, ua;

    /**
     * Tải 1 chương từ đoạn cURL (copy từ DevTools) và lưu ảnh JPG vào saveFolder.
     */
    public void fetchFromCurl(String curlText, String saveFolder) throws Exception {
        auth     = unesc(group(curlText, "authorization:\\s*([A-Za-z0-9._\\-]+)"));
        cookie   = unesc(group(curlText, "-b\\s+\\^?[\"']([\\s\\S]*?)\\^?[\"']"));
        if (cookie == null) cookie = unesc(group(curlText, "[Cc]ookie:\\s*\\^?[\"']?([^\"'^\\r\\n]*)"));
        userId   = unesc(group(curlText, "user-id=([0-9A-Za-z]+)"));
        String viewerId = group(curlText, "comici-viewer-id=([0-9a-fA-F]+)");
        referer  = unescOr(group(curlText, "referer:\\s*(https?://[^\\s\"'^\\\\]+)"), "https://heros-web.com/");
        ua       = unescOr(group(curlText, "user-agent:\\s*([^\\^\"'\\r\\n]+)"), DEFAULT_UA);

        if (auth == null || cookie == null || viewerId == null) {
            throw new RuntimeException("Không bóc được token/cookie/viewer-id từ cURL. "
                    + "Hãy copy đúng request 'contentsInfo' bằng 'Copy as cURL' (đầy đủ) rồi dán lại.");
        }
        System.out.println("🔍 viewer-id=" + viewerId + "  user-id=" + userId
                + "  (token…" + auth.substring(Math.max(0, auth.length() - 6)) + ")");

        String base = API + "?user-id=" + (userId == null ? "" : userId) + "&comici-viewer-id=" + viewerId;

        // Lấy tổng số trang
        String probe = apiGet(base + "&page-from=0&page-to=0");
        if (!probe.trim().startsWith("{")) {
            throw new RuntimeException("API không trả JSON — có thể CHƯA SỞ HỮU chương này hoặc token đã hết hạn "
                    + "(lấy lại cURL mới). Đầu response: "
                    + probe.substring(0, Math.min(100, probe.length())).replaceAll("\\s+", " "));
        }
        int total = Integer.parseInt(group(probe, "\"totalPages\"\\s*:\\s*(\\d+)"));
        System.out.println("📄 totalPages=" + total);

        List<Page> pages = parsePages(apiGet(base + "&page-from=0&page-to=" + (total - 1)));
        if (pages.isEmpty()) throw new RuntimeException("Không parse được trang nào từ API.");

        Path folder = Paths.get(saveFolder);
        Files.createDirectories(folder);
        System.out.println("📁 Lưu vào: " + folder);
        System.out.println("📦 " + pages.size() + " trang — đang tải + ghép (descramble)...");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (Page p : pages) {
            futures.add(pool.submit(() -> {
                String name = String.format("%03d.jpg", p.sort + 1);
                try {
                    BufferedImage scrambled = ImageIO.read(new ByteArrayInputStream(imgGet(p.imageUrl)));
                    if (scrambled == null) throw new RuntimeException("decode lỗi");
                    writeJpg(descramble(scrambled, p.scramble), folder.resolve(name).toFile());
                    System.out.println("  ✔ " + name);
                } catch (Exception e) {
                    System.err.println("  ❌ " + name + " : " + e.getMessage());
                }
                return null;
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        pool.awaitTermination(3, TimeUnit.MINUTES);
        System.out.println("✅ Hoàn thành " + pages.size() + " trang → " + folder);
    }

    // ── Descramble: lưới 4x4, ô đích f (column-major) lấy từ ô nguồn scramble[f] ──
    private BufferedImage descramble(BufferedImage src, int[] scramble) {
        int br = 4, pr = 4, w = src.getWidth(), h = src.getHeight(), u = w / br, d = h / pr, f = 0;
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for (int p = 0; p < br; p++) {
            for (int hh = 0; hh < pr; hh++) {
                int s = scramble[f++], gx = s / pr, my = s % pr;
                g.drawImage(src, u * p, d * hh, u * p + u, d * hh + d, u * gx, d * my, u * gx + u, d * my + d, null);
            }
        }
        g.dispose();
        return dst;
    }

    private void writeJpg(BufferedImage img, File out) throws Exception {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(Math.max(0f, Math.min(1f, quality)));
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            w.setOutput(ios);
            w.write(null, new IIOImage(img, null, null), p);
        } finally {
            w.dispose();
        }
    }

    // ── JSON (phẳng, không object lồng nhau) ─────────────────────────────────
    private static final class Page {
        final String imageUrl; final int[] scramble; final int sort;
        Page(String imageUrl, int[] scramble, int sort) { this.imageUrl = imageUrl; this.scramble = scramble; this.sort = sort; }
    }

    private List<Page> parsePages(String json) {
        List<Page> out = new ArrayList<>();
        Matcher m = Pattern.compile(
                "\"imageUrl\":\"(.*?)\",\"scramble\":\"\\[(.*?)\\]\",\"sort\":(\\d+)").matcher(json);
        while (m.find()) {
            String[] ps = m.group(2).replaceAll("\\s+", "").split(",");
            int[] scr = new int[ps.length];
            for (int i = 0; i < ps.length; i++) scr[i] = Integer.parseInt(ps[i]);
            out.add(new Page(m.group(1).replace("\\/", "/"), scr, Integer.parseInt(m.group(3))));
        }
        out.sort((a, b) -> Integer.compare(a.sort, b.sort));
        return out;
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────
    private String apiGet(String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("accept", "*/*").header("authorization", auth).header("cookie", cookie)
                .header("referer", referer).header("user-agent", ua)
                .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new RuntimeException("contentsInfo HTTP " + r.statusCode()
                    + (r.statusCode() == 403 ? " (403: chưa sở hữu / token hết hạn)" : ""));
        return r.body();
    }

    private byte[] imgGet(String url) throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("user-agent", ua).header("referer", referer).header("accept", "*/*")
                .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() / 100 != 2) throw new RuntimeException("image HTTP " + r.statusCode());
        return r.body();
    }

    // ── Bóc & unescape cURL (hỗ trợ cả định dạng cmd '^' lẫn bash) ────────────
    private static String group(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : null;
    }
    private static String unesc(String s) { return s == null ? null : s.replace("^", ""); }
    private static String unescOr(String s, String def) { String v = unesc(s); return v == null ? def : v.trim(); }
}
