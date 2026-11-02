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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetch chương truyện từ Comic Walker / カドコミ (comic-walker.com, KADOKAWA).
 *
 * URL episode:
 *   https://comic-walker.com/detail/{workCode}/episodes/{episodeCode}?episodeType=latest
 *   vd: https://comic-walker.com/detail/KC_008602_S/episodes/KC_0086020000200011_E
 *
 * Flow (dựng ngược từ bundle Next.js của trang, 2026-09-02):
 *  1. GET /api/contents/details/work?workCode={workCode}
 *        → work.title (tên series, dùng đặt tên folder).
 *  2. GET /api/contents/details/episode?episodeCode={episodeCode}&workCode={workCode}&episodeType={type}
 *        → episode.id (UUID) — ⚠ KHÁC episodeCode, API viewer chỉ nhận UUID này;
 *          episode.title ("第1話"), episode.internal.episodeNo, internal.pageCount.
 *  3. GET /api/contents/viewer?episodeId={UUID}&imageSizeType=width:1284
 *        → manuscripts[] mỗi phần tử { page, width, height, drmMode, drmHash, drmImageUrl }.
 *        ⚠ imageSizeType BẮT BUỘC và chỉ chấp nhận đúng "width:1284"
 *          (2048 / 1536 / original / height:* đều trả "bad request").
 *        Response còn có "expiresAt" — drmImageUrl là link CloudFront ký sẵn, hết
 *        hạn sau ít phút nên phải tải ngay.
 *  4. Tải drmImageUrl → file ảnh bị mã hoá. drmMode = "xor": XOR toàn bộ byte với
 *     key = hex-decode(drmHash) (8 byte) lặp lại → ra file WebP hợp lệ (RIFF....WEBP).
 *     KHÔNG có scramble lưới như GigaViewer / pixiv Comic.
 *  5. Ghi ra đĩa theo {@link #imageFormat}: "webp" giữ nguyên byte gốc (mặc định,
 *     không mất chất lượng), "jpg" / "png" thì decode rồi encode lại.
 *
 * Chương trả phí / giới hạn: truyền cookie tài khoản đã đăng nhập qua {@link #setCookie}.
 */
public class ComicWalker extends Manga {

    private static final String ORIGIN = "https://comic-walker.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** Kích thước ảnh duy nhất server chấp nhận. */
    private static final String IMAGE_SIZE_TYPE = "width:1284";

    /** Cookie tài khoản (chỉ cần cho chương trả phí). Null nếu free. */
    private String cookie = null;

    public void setCookie(String cookie) {
        this.cookie = (cookie != null && !cookie.isBlank()) ? cookie.trim() : null;
    }

    /** Tên folder series (Enter = tự lấy theo work.title). */
    private String folderOverride = null;

    public void setFolderOverride(String name) {
        this.folderOverride = (name != null && !name.isBlank()) ? name.trim() : null;
    }

    /** Tên folder chương (Enter = "Chap {episodeNo}"). */
    private String chapOverride = null;

    public void setChapOverride(String chap) {
        this.chapOverride = (chap != null && !chap.isBlank()) ? chap.trim() : null;
    }

    /** Thư mục lưu tuyệt đối. Nếu set, bỏ qua folderOverride/chapOverride. */
    private String outputFolder = null;

    public void setOutputFolder(String path) {
        this.outputFolder = (path != null && !path.isBlank()) ? path.trim() : null;
    }

    /** "webp" (giữ nguyên byte gốc), "jpg", hoặc "png". */
    private String imageFormat = "webp";

    public void setImageFormat(String fmt) {
        if (fmt == null || fmt.isBlank()) return;
        String f = fmt.trim().toLowerCase();
        if (f.equals("jpeg")) f = "jpg";
        if (f.equals("webp") || f.equals("jpg") || f.equals("png")) this.imageFormat = f;
    }

    private float quality = 0.92f;

    public void setQuality(float q) {
        this.quality = q;
    }

    /** episodeType lấy từ query string của URL ("latest" / "first"...). */
    private String episodeType = "first";

    /** workCode parse từ URL trong extractChapNumber, dùng lại ở parseAndDownload. */
    private String workCode = null;

    // ── Data class ───────────────────────────────────────────────────────────
    private static class PageInfo {
        final String url;
        final String drmMode;
        final String drmHash;

        PageInfo(String url, String drmMode, String drmHash) {
            this.url = url;
            this.drmMode = drmMode;
            this.drmHash = drmHash;
        }
    }

    // ── extractChapNumber: trả về episodeCode ────────────────────────────────
    @Override
    protected String extractChapNumber(String url) {
        Matcher m = Pattern.compile("/detail/([^/?#]+)/episodes/([^/?#]+)").matcher(url);
        if (m.find()) {
            workCode = m.group(1);
            String type = getQueryParam(url, "episodeType");
            if (type != null && !type.isBlank()) episodeType = type;
            return m.group(2);
        }
        throw new IllegalArgumentException("URL không đúng dạng " +
                "https://comic-walker.com/detail/{workCode}/episodes/{episodeCode}");
    }

    // ── parseAndDownload ─────────────────────────────────────────────────────
    @Override
    protected void parseAndDownload(String url, String chapNumber) throws Exception {

        String episodeCode = chapNumber;
        if (workCode == null || workCode.isBlank()) {
            throw new IllegalArgumentException("Không parse được workCode từ URL: " + url);
        }
        String refererUrl = ORIGIN + "/detail/" + workCode + "/episodes/" + episodeCode;

        // 1. Thông tin series
        System.out.println("📡 GET /api/contents/details/work?workCode=" + workCode);
        JsonObject workRoot = getJson(ORIGIN + "/api/contents/details/work?workCode=" + enc(workCode), refererUrl);
        String workTitle = "";
        if (workRoot.has("work") && workRoot.get("work").isJsonObject()) {
            workTitle = getStr(workRoot.getAsJsonObject("work"), "title");
        }

        // 2. Thông tin episode → lấy UUID cho API viewer
        String epApi = ORIGIN + "/api/contents/details/episode?episodeCode=" + enc(episodeCode)
                + "&workCode=" + enc(workCode) + "&episodeType=" + enc(episodeType);
        System.out.println("📡 GET /api/contents/details/episode?episodeCode=" + episodeCode);
        JsonObject epRoot = getJson(epApi, refererUrl);
        if (!epRoot.has("episode") || !epRoot.get("episode").isJsonObject()) {
            throw new RuntimeException("API không trả về episode (code sai hoặc chương đã gỡ?).");
        }
        JsonObject ep = epRoot.getAsJsonObject("episode");

        String episodeId = getStr(ep, "id");
        String episodeTitle = getStr(ep, "title");
        String episodeNo = "";
        int pageCount = 0;
        if (ep.has("internal") && ep.get("internal").isJsonObject()) {
            JsonObject internal = ep.getAsJsonObject("internal");
            if (internal.has("episodeNo") && !internal.get("episodeNo").isJsonNull()) {
                episodeNo = internal.get("episodeNo").getAsString();
            }
            if (internal.has("pageCount") && !internal.get("pageCount").isJsonNull()) {
                pageCount = internal.get("pageCount").getAsInt();
            }
        }

        System.out.println("📚 Series  : " + (workTitle.isEmpty() ? "(không rõ)" : workTitle));
        System.out.println("📖 Episode : " + episodeTitle
                + (episodeNo.isEmpty() ? "" : " (No." + episodeNo + ")")
                + (pageCount > 0 ? " - " + pageCount + " trang" : ""));

        if (episodeId.isEmpty()) throw new RuntimeException("Không lấy được episode.id (UUID).");

        // 3. Danh sách trang
        String viewerApi = ORIGIN + "/api/contents/viewer?episodeId=" + enc(episodeId)
                + "&imageSizeType=" + enc(IMAGE_SIZE_TYPE);
        System.out.println("📡 GET /api/contents/viewer?episodeId=" + episodeId);
        JsonObject viewer = getJson(viewerApi, refererUrl);

        if (!viewer.has("manuscripts") || viewer.getAsJsonArray("manuscripts").isEmpty()) {
            throw new RuntimeException("Không có trang nào — chương trả phí chưa mua, " +
                    "hoặc cần cookie tài khoản đã đăng nhập.");
        }

        JsonArray manuscripts = viewer.getAsJsonArray("manuscripts");
        List<PageInfo> pages = new ArrayList<>();
        for (int i = 0; i < manuscripts.size(); i++) {
            JsonObject m = manuscripts.get(i).getAsJsonObject();
            String imgUrl = getStr(m, "drmImageUrl");
            if (imgUrl.isEmpty()) continue;
            pages.add(new PageInfo(imgUrl, getStr(m, "drmMode"), getStr(m, "drmHash")));
        }

        String expiresAt = getStr(viewer, "expiresAt");
        System.out.println("🖼  Tìm thấy " + pages.size() + " trang"
                + (expiresAt.isEmpty() ? "" : " (link hết hạn lúc " + expiresAt + ")")
                + ", lưu dạng ." + imageFormat);

        // 4. Thư mục lưu
        Path folder;
        if (outputFolder != null) {
            folder = Paths.get(outputFolder);
        } else {
            String seriesFolder;
            if (folderOverride != null) {
                seriesFolder = sanitize(folderOverride);
            } else if (!workTitle.isEmpty()) {
                seriesFolder = sanitize(workTitle);
            } else {
                seriesFolder = "ComicWalker";
            }

            String leaf;
            if (chapOverride != null) {
                leaf = sanitize(chapOverride);
            } else if (!episodeNo.isEmpty()) {
                leaf = "Chap " + episodeNo;
            } else {
                Matcher numM = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(episodeTitle);
                leaf = numM.find() ? "Chap " + numM.group(1) : sanitize(episodeTitle);
            }

            folder = Paths.get(basePath, seriesFolder, leaf);
        }
        Files.createDirectories(folder);
        System.out.println("📁 Lưu vào: " + folder);

        // 5. Tải + giải mã
        for (int i = 0; i < pages.size(); i++) {
            final int index = i + 1;
            final PageInfo info = pages.get(i);
            Future<?> f = executor.submit(() -> downloadAndProcess(info, folder, index, refererUrl));
            futures.add(f);
        }
    }

    // ── Download + Decrypt + Save ────────────────────────────────────────────
    private void downloadAndProcess(PageInfo page, Path folder, int index, String referer) {
        int retry = 3;
        int[] delays = { 0, 500, 1000 };

        while (retry > 0) {
            int attempt = 3 - retry;
            try {
                if (attempt > 0) Thread.sleep(delays[attempt]);

                byte[] raw = downloadBytes(page.url, referer);
                byte[] decrypted = decrypt(raw, page);

                String fileName = String.format("%03d.%s", index, imageFormat);
                Path output = folder.resolve(fileName);

                if (imageFormat.equals("webp")) {
                    // Byte sau giải mã đã là file WebP hợp lệ → ghi thẳng, không re-encode.
                    Files.copy(new ByteArrayInputStream(decrypted), output, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(decrypted));
                    if (img == null) {
                        throw new RuntimeException("ImageIO.read() trả về null — plugin đọc WebP chưa khởi tạo được");
                    }
                    if (imageFormat.equals("png")) {
                        ImageIO.write(img, "png", output.toFile());
                    } else {
                        Files.copy(new ByteArrayInputStream(writeJpg(img)), output,
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }

                System.out.println("✔ [" + index + "] " + fileName
                        + ("xor".equalsIgnoreCase(page.drmMode) ? " (giải mã XOR)" : ""));
                return;

            } catch (Exception e) {
                retry--;
                if (retry == 0) {
                    System.err.println("❌ Fail [" + index + "]: " + page.url + " | " + e.getMessage());
                }
            }
        }
    }

    /**
     * drmMode = "xor": XOR toàn bộ byte với key = hex-decode(drmHash), lặp vòng.
     * Mode khác / hash rỗng: trả nguyên byte gốc.
     */
    private static byte[] decrypt(byte[] data, PageInfo page) {
        if (!"xor".equalsIgnoreCase(page.drmMode) || page.drmHash == null || page.drmHash.isEmpty()) {
            return data;
        }
        byte[] key = hexToBytes(page.drmHash);
        if (key.length == 0) return data;

        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return out;
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────
    private JsonObject getJson(String urlStr, String referer) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Language", "ja,en-US;q=0.9,en;q=0.8");
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("Origin", ORIGIN);
        if (cookie != null) conn.setRequestProperty("Cookie", cookie);

        int code = conn.getResponseCode();
        InputStream in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        String body = (in == null) ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (code != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("HTTP " + code + " từ " + urlStr + " | "
                    + body.substring(0, Math.min(300, body.length())));
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private byte[] downloadBytes(String imageUrl, String referer) throws Exception {
        URL url = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("Origin", ORIGIN);
        conn.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
        if (cookie != null) conn.setRequestProperty("Cookie", cookie);

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) throw new RuntimeException("HTTP " + code);

        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private byte[] writeJpg(BufferedImage img) throws Exception {
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(img, 0, 0, null);
        }
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(Math.max(0f, Math.min(1f, quality)));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            w.setOutput(ios);
            w.write(null, new IIOImage(rgb, null, null), p);
        } finally {
            w.dispose();
        }
        return bos.toByteArray();
    }

    private static byte[] hexToBytes(String hex) {
        String h = hex.trim();
        if (h.length() % 2 != 0) return new byte[0];
        byte[] out = new byte[h.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return new byte[0];
        }
        return out;
    }

    private static String getQueryParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) return pair.substring(eq + 1);
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String getStr(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : "";
    }

    private static String sanitize(String s) {
        String cleaned = s.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        return cleaned.isEmpty() ? "ComicWalker" : cleaned;
    }
}
