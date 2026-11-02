package org.example.fetch_manga.service.site;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetch chương truyện từ pixiv Comic (comic.pixiv.net).
 *
 * URL viewer: https://comic.pixiv.net/viewer/stories/{episodeId}
 *
 * Flow (đã dựng ngược từ bundle Next.js của trang, 2026-07-29):
 *  1. GET trang viewer → lấy "salt" trong &lt;script id="__NEXT_DATA__"&gt;
 *     (props.pageProps.salt).
 *  2. Tính header chống bot:
 *        time = now theo format "yyyy-MM-dd'T'HH:mm:ssXXX"   (vd 2026-07-29T14:38:22+07:00)
 *        hash = SHA-256hex(time + salt)
 *     → gửi kèm "X-Client-Time" / "X-Client-Hash".
 *     ⚠ BẮT BUỘC phải có thêm header "X-Requested-With: pixivcomic",
 *       thiếu nó server trả 403 dù hash đúng.
 *  3. GET /api/app/episodes/{id}/read_v4 → data.reading_episode.pages[]
 *     mỗi page: { url, width, height, gridsize, key }.
 *  4. Tải ảnh từ img-comic.pximg.net (path có "q90_gridshuffle32:32") kèm header
 *     "X-Cobalt-Thumber-Parameter-GridShuffle-Key: {key}".
 *  5. Descramble: ảnh bị xáo trộn theo CỘT trong từng dải ngang cao gridsize px.
 *     Bảng hoán vị sinh từ PRNG xoshiro128** seed = SHA-256(SECRET + key),
 *     bỏ 100 giá trị đầu, rồi Fisher-Yates cho từng dải (state RNG chạy liên tục
 *     qua các dải). Xem {@link #buildTables} / {@link #descramble}.
 *  6. Ghi ra JPG chất lượng 0.92.
 */
public class PixivComic extends Manga {

    /** Secret nhúng cứng trong bundle JS của trang (tham số thứ 7 của hàm shuffle). */
    private static final String SHUFFLE_SECRET = "4wXCKprMMoxnyJ3PocJFs4CYbfnbazNe";

    private static final String ORIGIN = "https://comic.pixiv.net";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** Cookie tài khoản (chỉ cần cho chương trả phí / giới hạn tuổi). Null nếu free. */
    private String cookie = null;

    public void setCookie(String cookie) {
        this.cookie = (cookie != null && !cookie.isBlank()) ? cookie.trim() : null;
    }

    /** Tên folder series (Enter = tự lấy theo work_title). */
    private String folderOverride = null;

    public void setFolderOverride(String name) {
        this.folderOverride = (name != null && !name.isBlank()) ? name.trim() : null;
    }

    /** Tên folder chương (Enter = tự suy ra từ numbering_title / work_title). */
    private String chapOverride = null;

    public void setChapOverride(String chap) {
        this.chapOverride = (chap != null && !chap.isBlank()) ? chap.trim() : null;
    }

    /** Thư mục lưu tuyệt đối. Nếu set, bỏ qua folderOverride/chapOverride. */
    private String outputFolder = null;

    public void setOutputFolder(String path) {
        this.outputFolder = (path != null && !path.isBlank()) ? path.trim() : null;
    }

    private float quality = 0.92f;

    public void setQuality(float q) { this.quality = q; }

    // ── Data class ───────────────────────────────────────────────────────────
    private static class PageInfo {
        final String url;
        final int width;
        final int height;
        final int gridSize;
        final String key;

        PageInfo(String url, int width, int height, int gridSize, String key) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.gridSize = gridSize;
            this.key = key;
        }
    }

    // ── extractChapNumber ────────────────────────────────────────────────────
    @Override
    protected String extractChapNumber(String url) {
        Matcher m = Pattern.compile("/viewer/stories/(\\d+)").matcher(url);
        if (m.find()) return m.group(1);
        String[] parts = url.split("[/?#]");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank()) return parts[i].trim();
        }
        return url.trim();
    }

    // ── parseAndDownload ─────────────────────────────────────────────────────
    @Override
    protected void parseAndDownload(String url, String chapNumber) throws Exception {

        String episodeId = chapNumber;
        String viewerUrl = ORIGIN + "/viewer/stories/" + episodeId;

        System.out.println("🌐 Lấy salt từ trang viewer...");
        String html = httpGetString(viewerUrl, null);

        Matcher saltM = Pattern.compile("\"salt\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
        if (!saltM.find()) {
            throw new RuntimeException("Không tìm thấy \"salt\" trong __NEXT_DATA__. " +
                    "Trang có thể đã đổi cấu trúc, hoặc episode không tồn tại.");
        }
        String salt = saltM.group(1);
        System.out.println("🔑 salt = " + salt);

        // Header chống bot
        String time = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        String clientHash = sha256Hex((time + salt).getBytes(StandardCharsets.UTF_8));

        String apiUrl = ORIGIN + "/api/app/episodes/" + episodeId + "/read_v4";
        System.out.println("📡 GET " + apiUrl);

        String json = httpGetString(apiUrl, conn -> {
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Requested-With", "pixivcomic");
            conn.setRequestProperty("X-Client-Time", time);
            conn.setRequestProperty("X-Client-Hash", clientHash);
            conn.setRequestProperty("Referer", viewerUrl);
            conn.setRequestProperty("Origin", ORIGIN);
        });

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("error")) {
            throw new RuntimeException("API trả lỗi: " + root.get("error"));
        }
        JsonObject ep = root.getAsJsonObject("data").getAsJsonObject("reading_episode");

        String workTitle = getStr(ep, "work_title");
        String numberingTitle = getStr(ep, "numbering_title");
        String subTitle = getStr(ep, "sub_title");
        String salesType = getStr(ep, "sales_type");
        String state = getStr(ep, "state");

        System.out.println("📚 Work    : " + workTitle);
        System.out.println("📖 Episode : " + numberingTitle + (subTitle.isEmpty() ? "" : " - " + subTitle));
        System.out.println("💴 Loại    : " + salesType + " | state: " + state);

        if (!ep.has("pages") || ep.getAsJsonArray("pages").isEmpty()) {
            throw new RuntimeException("Không có trang nào (chương chưa mua / chưa mở khoá?).");
        }

        JsonArray pages = ep.getAsJsonArray("pages");
        List<PageInfo> pageInfos = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            JsonObject p = pages.get(i).getAsJsonObject();
            String pUrl = getStr(p, "url");
            if (pUrl.isEmpty()) continue;
            pageInfos.add(new PageInfo(
                    pUrl,
                    p.get("width").getAsInt(),
                    p.get("height").getAsInt(),
                    p.has("gridsize") && !p.get("gridsize").isJsonNull() ? p.get("gridsize").getAsInt() : 0,
                    getStr(p, "key")));
        }

        System.out.println("🖼  Tìm thấy " + pageInfos.size() + " trang, bắt đầu download...");

        Path folder;
        if (outputFolder != null) {
            folder = Paths.get(outputFolder);
            Files.createDirectories(folder);
            System.out.println("📁 Lưu vào: " + folder);
            queueDownloads(pageInfos, folder, viewerUrl);
            return;
        }

        // Folder series: override > work_title > "PixivComic"
        String folderName;
        if (folderOverride != null) {
            folderName = folderOverride;
        } else if (!workTitle.isEmpty()) {
            folderName = sanitize(workTitle);
        } else {
            folderName = "PixivComic";
        }

        // Folder chương: override > "Chap <số>" nếu numbering_title có số
        //              > tên tác phẩm (trường hợp đọc-một-lần / 読み切り)
        String leaf;
        if (chapOverride != null) {
            leaf = sanitize(chapOverride);
        } else {
            Matcher numM = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(numberingTitle);
            if (numM.find()) {
                leaf = "Chap " + numM.group(1);
            } else if (!workTitle.isEmpty()) {
                leaf = sanitize(workTitle);
            } else {
                leaf = "Chap " + episodeId;
            }
        }

        folder = Paths.get(basePath, folderName, leaf);
        Files.createDirectories(folder);
        System.out.println("📁 Lưu vào: " + folder);

        queueDownloads(pageInfos, folder, viewerUrl);
    }

    private void queueDownloads(List<PageInfo> pageInfos, Path folder, String viewerUrl) {
        for (int i = 0; i < pageInfos.size(); i++) {
            final int index = i + 1;
            final PageInfo info = pageInfos.get(i);
            Future<?> f = executor.submit(() -> downloadAndProcess(info, folder, index, viewerUrl));
            futures.add(f);
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

                byte[] raw = downloadBytes(page.url, referer, page.key);

                boolean shuffled = page.gridSize > 0 && page.key != null && !page.key.isEmpty();
                byte[] out = shuffled ? descramble(raw, page) : raw;

                String fileName = String.format("%03d.jpg", index);
                Files.copy(new ByteArrayInputStream(out), folder.resolve(fileName),
                        StandardCopyOption.REPLACE_EXISTING);

                System.out.println("✔ [" + index + "] " + fileName + (shuffled ? " (descrambled)" : ""));
                return;

            } catch (Exception e) {
                retry--;
                if (retry == 0) {
                    System.err.println("❌ Fail [" + index + "]: " + page.url + " | " + e.getMessage());
                }
            }
        }
    }

    // ── Grid-shuffle descramble ──────────────────────────────────────────────
    /**
     * Đảo ngược hàm shuffle() của pixiv (cobalt-thumber GridShuffle).
     * Ảnh chia thành các dải ngang cao {@code gridsize} px; trong mỗi dải, các
     * cột rộng {@code gridsize} px bị hoán vị. Phần lề bên phải (width không chia
     * hết cho gridsize) giữ nguyên.
     */
    private byte[] descramble(byte[] rawJpeg, PageInfo page) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(rawJpeg));
        if (src == null) throw new RuntimeException("Không đọc được ảnh JPEG");

        int width = src.getWidth();
        int height = src.getHeight();
        int block = page.gridSize;

        int bandCount = (int) Math.ceil((double) height / block);   // số dải ngang
        int colCount = width / block;                               // số cột nguyên

        int[][] tables = buildTables(SHUFFLE_SECRET + page.key, bandCount, colCount);

        int[] in = src.getRGB(0, 0, width, height, null, 0, width);
        int[] out = new int[in.length];

        for (int y = 0; y < height; y++) {
            int[] perm = tables[y / block];
            int rowOff = y * width;
            for (int c = 0; c < colCount; c++) {
                System.arraycopy(in, rowOff + perm[c] * block, out, rowOff + c * block, block);
            }
            // lề dư bên phải: copy nguyên
            int rest = width - colCount * block;
            if (rest > 0) {
                System.arraycopy(in, rowOff + colCount * block, out, rowOff + colCount * block, rest);
            }
        }

        BufferedImage dst = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        dst.setRGB(0, 0, width, height, out, 0, width);
        return writeJpg(dst);
    }

    /**
     * Sinh bảng hoán vị NGƯỢC cho từng dải ngang.
     * Seed = 16 byte đầu của SHA-256(seedStr), đọc little-endian thành 4 uint32.
     * Bỏ 100 giá trị đầu, sau đó Fisher-Yates từng dải (RNG chạy liên tục).
     */
    private static int[][] buildTables(String seedStr, int bandCount, int colCount) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(seedStr.getBytes(StandardCharsets.UTF_8));

        int[] s = new int[4];
        for (int i = 0; i < 4; i++) {
            s[i] = (digest[i * 4] & 0xFF)
                    | ((digest[i * 4 + 1] & 0xFF) << 8)
                    | ((digest[i * 4 + 2] & 0xFF) << 16)
                    | ((digest[i * 4 + 3] & 0xFF) << 24);
        }
        Xoshiro128 rng = new Xoshiro128(s);
        for (int i = 0; i < 100; i++) rng.next();

        int[][] tables = new int[bandCount][];
        for (int b = 0; b < bandCount; b++) {
            int[] t = new int[colCount];
            for (int i = 0; i < colCount; i++) t[i] = i;
            for (int i = colCount - 1; i >= 1; i--) {
                int j = (int) (Integer.toUnsignedLong(rng.next()) % (i + 1));
                int tmp = t[i];
                t[i] = t[j];
                t[j] = tmp;
            }
            // đảo ngược hoán vị: inv[t[i]] = i
            int[] inv = new int[colCount];
            for (int i = 0; i < colCount; i++) inv[t[i]] = i;
            tables[b] = inv;
        }
        return tables;
    }

    /** xoshiro128** — bản port từ hàm PRNG trong bundle JS của pixiv. */
    private static class Xoshiro128 {
        private final int[] s;

        Xoshiro128(int[] seed) {
            this.s = seed.clone();
            if (s[0] == 0 && s[1] == 0 && s[2] == 0 && s[3] == 0) s[0] = 1;
        }

        private static int rotl(int x, int k) {
            k %= 32;
            if (k == 0) return x;
            return (x << k) | (x >>> (32 - k));
        }

        int next() {
            int result = rotl(s[1] * 5, 7) * 9;
            int t = s[1] << 9;
            s[2] ^= s[0];
            s[3] ^= s[1];
            s[1] ^= s[2];
            s[0] ^= s[3];
            s[2] ^= t;
            s[3] = rotl(s[3], 11);
            return result;
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────
    private interface ConnCustomizer {
        void apply(HttpURLConnection conn);
    }

    private String httpGetString(String urlStr, ConnCustomizer customizer) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept-Language", "ja,en-US;q=0.9,en;q=0.8");
        if (cookie != null) conn.setRequestProperty("Cookie", cookie);
        if (customizer != null) customizer.apply(conn);

        int code = conn.getResponseCode();
        InputStream in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        String body = (in == null) ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (code != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("HTTP " + code + " từ " + urlStr + " | "
                    + body.substring(0, Math.min(300, body.length())));
        }
        return body;
    }

    private byte[] downloadBytes(String imageUrl, String referer, String gridShuffleKey) throws Exception {
        URL url = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("Origin", ORIGIN);
        conn.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
        if (gridShuffleKey != null && !gridShuffleKey.isEmpty()) {
            conn.setRequestProperty("X-Cobalt-Thumber-Parameter-GridShuffle-Key", gridShuffleKey);
        }

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) throw new RuntimeException("HTTP " + code);

        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
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

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String getStr(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : "";
    }

    private static String sanitize(String s) {
        String cleaned = s.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        return cleaned.isEmpty() ? "PixivComic" : cleaned;
    }
}
