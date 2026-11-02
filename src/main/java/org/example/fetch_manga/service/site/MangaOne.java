package org.example.fetch_manga.service.site;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * マンガワン / Manga One (manga-one.com, Shogakukan).
 *
 * <p>Cơ chế (dựng ngược 2026-09-16):</p>
 * <ol>
 *   <li>URL chương: {@code /manga/<titleId>/chapter/<chapterId>} → tách hai id.</li>
 *   <li><b>POST</b> (GET trả 405) tới
 *       {@code /api/client?rq=viewer_v2&title_id=..&chapter_id=..&page=1&limit=10&...}
 *       — tham số nằm trên URL, body để rỗng. Response là <b>protobuf</b>, không phải JSON.</li>
 *   <li>Trong protobuf: field 3 = khoá AES dạng hex 64 ký tự (32 byte → AES-256),
 *       field 4 = IV hex 32 ký tự (16 byte). Danh sách trang nằm ở field 1, mỗi trang
 *       là một URL {@code .../manga_page_low/<chapterId>/<n>.webp.enc?hash=..&expires=..}</li>
 *   <li>Tải từng file {@code .enc} rồi giải mã <b>AES-256-CBC</b> với key/iv ở trên → ra WebP.</li>
 * </ol>
 *
 * <p>⚠ Field 1 (danh sách trang) chỉ có khi request kèm <b>cookie đăng nhập</b> của tài
 * khoản có quyền đọc chương đó; gọi không cookie thì response vẫn có key/iv nhưng không
 * có trang nào. Khoá và tham số {@code hash} đều có hạn nên phải gọi API mỗi lần tải.</p>
 */
public class MangaOne extends Manga {

    private static final String ORIGIN = "https://manga-one.com";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** Cookie tài khoản — bắt buộc với chương đã mua/mở bằng vé. */
    private String cookie = null;

    public void setCookie(String cookie) {
        this.cookie = (cookie != null && !cookie.isBlank()) ? cookie.trim() : null;
    }

    /** Thư mục lưu tuyệt đối; null thì dùng quy ước basePath của lớp cha. */
    private String outputFolder = null;

    public void setOutputFolder(String path) {
        this.outputFolder = (path != null && !path.isBlank()) ? path.trim() : null;
    }

    private static final Pattern URL_IDS =
            Pattern.compile("/manga/(\\d+)/(chapter|choitashi)/(\\d+)");

    /**
     * Link ảnh đã mã hoá nằm rải trong protobuf; bắt thẳng bằng regex cho chắc.
     *
     * <p>group(1) = số trang server ghi trong đường dẫn (dùng để sắp xếp và đặt tên).
     * Phần query chỉ nhận đúng ký tự hợp lệ của URL — <b>không</b> dùng {@code [^\s"]+}
     * vì protobuf có byte nhị phân xen giữa các link, {@code [^\s"]} sẽ nuốt luôn byte
     * rác lẫn cả link kế tiếp, sinh ra link hỏng làm lệch số thứ tự trang.</p>
     */
    private static final Pattern ENC_IMAGE =
            Pattern.compile("https://[\\w.-]+/secure/\\d+/webp/(?:manga_page[\\w_]*|choitashi_page)/\\d+/(\\d+)"
                    + "\\.webp\\.enc\\?[A-Za-z0-9=&%._~+-]+");

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern HEX32 = Pattern.compile("^[0-9a-fA-F]{32}$");

    // ── Manga ───────────────────────────────────────────────────────────────

    @Override
    protected String extractChapNumber(String url) {
        Matcher m = URL_IDS.matcher(url);
        if (!m.find()) {
            throw new IllegalArgumentException(
                    "URL không đúng dạng https://manga-one.com/manga/<id>/chapter/<id> "
                            + "(hoặc /choitashi/<id> cho ảnh bonus)");
        }
        return m.group(3);
    }

    @Override
    protected void parseAndDownload(String url, String chapNumber) throws Exception {
        Matcher m = URL_IDS.matcher(url);
        if (!m.find()) {
            throw new IllegalArgumentException("Không tách được title_id / chapter_id từ URL.");
        }
        String titleId = m.group(1);
        boolean choitashi = "choitashi".equals(m.group(2));
        String chapterId = m.group(3);

        String api = choitashi ? "choitashi_viewer" : "viewer_v2";
        System.out.println("📡 Gọi API " + api + " (title " + titleId + ", "
                + (choitashi ? "ảnh bonus " : "chapter ") + chapterId + ")...");
        byte[] proto = callViewerApi(titleId, chapterId, url, choitashi);
        System.out.println("📦 Nhận " + proto.length + " byte protobuf");

        Viewer viewer = parseViewer(proto);

        if (viewer.key == null || viewer.iv == null) {
            throw new RuntimeException("Không tìm thấy khoá giải mã trong response.");
        }
        System.out.println("🔑 Khoá AES-" + (viewer.key.length * 8) + " + IV " + viewer.iv.length + " byte");

        if (viewer.pageUrls.isEmpty()) {
            throw new RuntimeException(
                    "Response không có trang nào. Nguyên nhân hầu như luôn là thiếu cookie "
                            + "hoặc tài khoản chưa mở chương này — hãy dán cookie của phiên đang đọc được.");
        }
        System.out.println("🖼  " + viewer.pageUrls.size() + " trang");

        Path folder = resolveFolder(chapNumber);
        Files.createDirectories(folder);
        System.out.println("📁 Lưu vào: " + folder);

        for (int i = 0; i < viewer.pageUrls.size(); i++) {
            downloadAndDecrypt(viewer.pageUrls.get(i), folder, i + 1, viewer.key, viewer.iv, url);
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.MINUTES);
        System.out.println("✅ Hoàn thành " + viewer.pageUrls.size() + " trang!");
    }

    private Path resolveFolder(String chapNumber) throws Exception {
        return (outputFolder != null)
                ? java.nio.file.Paths.get(outputFolder)
                : createFolder("MangaOne", chapNumber);
    }

    // ── Gọi API ─────────────────────────────────────────────────────────────

    /**
     * @param choitashi true = ảnh bonus (choitashi_viewer), false = trang truyện (viewer_v2).
     *                  Hai loại dùng cùng dạng protobuf và cùng cách giải mã, chỉ khác tên
     *                  API và thư mục ảnh trên server (choitashi_page vs manga_page_low).
     */
    private byte[] callViewerApi(String titleId, String chapterId, String referer, boolean choitashi)
            throws Exception {
        String rq = choitashi ? "choitashi_viewer" : "viewer_v2";
        String api = ORIGIN + "/api/client?rq=" + rq
                + "&title_id=" + titleId
                + "&chapter_id=" + chapterId
                + "&page=1&limit=10&sort_type=desc"
                + (choitashi ? "&type=choitashi" : "&list_type=chapter")
                + "&free_point=0&event_point=0&paid_point=0";

        HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
        // GET trả 405: endpoint chỉ nhận POST, tham số vẫn để trên query string.
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Origin", ORIGIN);
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "ja,en-US;q=0.9,en;q=0.8");
        if (cookie != null) conn.setRequestProperty("Cookie", cookie);
        conn.getOutputStream().close();

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("API trả HTTP " + code
                    + (code == 401 || code == 403 ? " — cookie sai hoặc đã hết hạn." : ""));
        }
        try (InputStream in = conn.getInputStream()) {
            return readAll(in);
        }
    }

    // ── Đọc protobuf ────────────────────────────────────────────────────────

    /** Những gì cần lấy ra từ response. */
    private static final class Viewer {
        byte[] key;
        byte[] iv;
        final List<String> pageUrls = new ArrayList<>();
    }

    /**
     * Quét toàn bộ cây protobuf thay vì bám cứng vào số thứ tự field.
     *
     * <p>Danh sách trang nằm lồng nhiều lớp và cấu trúc có thể đổi theo phiên bản,
     * nên cách chắc ăn là duyệt hết mọi chuỗi: cái nào là link {@code .webp.enc} thì
     * gom lại, cái nào là hex 64/32 ký tự thì nhận làm key/iv.</p>
     *
     * <p>Trang được gom vào map theo <b>số trang trong URL</b> rồi sắp xếp lại, nên
     * dù cùng một link xuất hiện nhiều lần (message cha và field con) hay thứ tự
     * trong protobuf có lộn xộn thì kết quả vẫn đúng thứ tự và không trùng.</p>
     */
    private Viewer parseViewer(byte[] data) {
        Viewer v = new Viewer();
        List<String> strings = new ArrayList<>();
        collectStrings(data, 0, data.length, strings, 0);

        java.util.TreeMap<Integer, String> pagesByNumber = new java.util.TreeMap<>();

        for (String s : strings) {
            if (v.key == null && HEX64.matcher(s).matches()) {
                v.key = hexToBytes(s);
            } else if (v.iv == null && HEX32.matcher(s).matches()) {
                v.iv = hexToBytes(s);
            }
            Matcher m = ENC_IMAGE.matcher(s);
            while (m.find()) {
                int pageNo = Integer.parseInt(m.group(1));
                // Giữ link đầu tiên gặp cho mỗi số trang; bản trùng về sau bỏ qua.
                pagesByNumber.putIfAbsent(pageNo, m.group());
            }
        }

        v.pageUrls.addAll(pagesByNumber.values());
        return v;
    }

    /** Duyệt đệ quy wire-format, gom mọi chuỗi gặp được. */
    private void collectStrings(byte[] d, int from, int to, List<String> out, int depth) {
        if (depth > 12) return;
        int pos = from;
        while (pos < to) {
            long[] tag = readVarint(d, pos, to);
            if (tag == null) return;
            pos = (int) tag[1];

            int field = (int) (tag[0] >>> 3);
            int wire = (int) (tag[0] & 7);
            if (field == 0) return;

            switch (wire) {
                case 0: {
                    long[] v = readVarint(d, pos, to);
                    if (v == null) return;
                    pos = (int) v[1];
                    break;
                }
                case 1: pos += 8; break;
                case 5: pos += 4; break;
                case 2: {
                    long[] len = readVarint(d, pos, to);
                    if (len == null) return;
                    pos = (int) len[1];
                    int n = (int) len[0];
                    if (n < 0 || pos + n > to) return;

                    out.add(new String(d, pos, n, StandardCharsets.UTF_8));
                    // Có thể là message lồng bên trong: thử đọc tiếp, hỏng thì thôi.
                    collectStrings(d, pos, pos + n, out, depth + 1);
                    pos += n;
                    break;
                }
                default: return;
            }
        }
    }

    /** @return {giá trị, vị trí kế tiếp} hoặc null nếu hỏng */
    private long[] readVarint(byte[] d, int pos, int to) {
        long result = 0;
        int shift = 0;
        while (pos < to) {
            int b = d[pos++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return new long[]{result, pos};
            shift += 7;
            if (shift > 63) return null;
        }
        return null;
    }

    // ── Tải và giải mã ──────────────────────────────────────────────────────

    private void downloadAndDecrypt(String imageUrl, Path folder, int index,
                                    byte[] key, byte[] iv, String referer) {
        futures.add(executor.submit(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(imageUrl).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Referer", referer);
                conn.setRequestProperty("Origin", ORIGIN);
                conn.setRequestProperty("Accept", "*/*");
                if (cookie != null) conn.setRequestProperty("Cookie", cookie);

                byte[] encrypted;
                try (InputStream in = conn.getInputStream()) {
                    encrypted = readAll(in);
                }

                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                byte[] plain = cipher.doFinal(encrypted);

                if (!looksLikeWebp(plain)) {
                    throw new RuntimeException("Giải mã xong nhưng không phải file WebP hợp lệ.");
                }

                Path out = folder.resolve(String.format("%03d.webp", index));
                Files.write(out, plain);
                System.out.println("✔ [" + index + "] " + out.getFileName()
                        + " (" + plain.length / 1024 + " KB)");

            } catch (Exception e) {
                System.err.println("❌ Trang " + index + ": " + e.getMessage());
            }
        }));
    }

    private boolean looksLikeWebp(byte[] b) {
        return b.length > 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    // ── Tiện ích ────────────────────────────────────────────────────────────

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
        return buffer.toByteArray();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
