package org.example.fetch_manga.service.site;

import com.google.protobuf.CodedInputStream;

import java.io.ByteArrayInputStream;
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
 * Fetches manga images from MangaPlus (mangaplus.shueisha.co.jp).
 *
 * Flow:
 *  1. Extract chapter_id from URL
 *  2. Call https://jumpg-webapi.tokyo-cdn.com/api/manga_viewer → binary Protobuf response
 *  3. Parse protobuf to extract (imageUrl, encryptionKey) for each page
 *  4. Download each scrambled image, XOR-decrypt with its key, save to folder
 */
public class MangaPlus extends Manga {

    private static final String API_BASE = "https://jumpg-webapi.tokyo-cdn.com";
    private static final String REFERER  = "https://mangaplus.shueisha.co.jp/";

    // ──────────────────────────────────────────────────────────────────────────
    // Manga API — Protobuf wire-format field numbers (from actual response.proto)
    //
    // Response             → field 1  = SuccessResult
    // SuccessResult        → field 10 = MangaViewer
    // MangaViewer          → field 1  = repeated Page
    // Page                 → field 1  = MangaPage (oneof)
    // MangaPage            → field 1  = image_url, field 5 = encryption_key
    // ──────────────────────────────────────────────────────────────────────────

    // ── Data class ──────────────────────────────────────────────────────────
    private static class PageInfo {
        final String imageUrl;
        final String encryptionKey;

        PageInfo(String imageUrl, String encryptionKey) {
            this.imageUrl = imageUrl;
            this.encryptionKey = encryptionKey;
        }
    }

    // ── extractChapNumber ───────────────────────────────────────────────────
    @Override
    protected String extractChapNumber(String url) {
        // e.g. https://mangaplus.shueisha.co.jp/viewer/1028483  →  1028483
        String[] parts = url.split("/");
        return parts[parts.length - 1].trim();
    }

    // ── parseAndDownload ─────────────────────────────────────────────────────
    @Override
    protected void parseAndDownload(String url, String chapNumber) throws Exception {

        System.out.println("📡 Gọi MangaPlus API cho chapter " + chapNumber + "...");

        byte[] protoBytes = fetchProto(chapNumber);

        System.out.println("📦 Parse protobuf response (" + protoBytes.length + " bytes)...");

        List<PageInfo> pages = parsePages(protoBytes);

        if (pages.isEmpty()) {
            throw new RuntimeException("Không tìm thấy page nào trong response. " +
                    "Thử lại hoặc kiểm tra chapter_id.");
        }

        System.out.println("🖼  Tìm thấy " + pages.size() + " trang, bắt đầu download & decrypt...");

        Path folder = createFolder("MangaPlus", chapNumber);

        for (int i = 0; i < pages.size(); i++) {
            final int index = i + 1;
            final PageInfo page = pages.get(i);

            Future<?> future = executor.submit(() -> downloadAndDecrypt(page, folder, index));
            futures.add(future);
        }
    }

    // ── Fetch Protobuf bytes from API ────────────────────────────────────────
    private byte[] fetchProto(String chapterId) throws Exception {
        String apiUrl = API_BASE + "/api/manga_viewer"
                + "?chapter_id=" + chapterId
                + "&split=no"
                + "&img_quality=super_high";

        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0");
        conn.setRequestProperty("Referer", REFERER);
        conn.setRequestProperty("Origin", "https://mangaplus.shueisha.co.jp");

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("API trả về HTTP " + code);
        }

        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        }
    }

    // ── Parse Protobuf binary → list of PageInfo ─────────────────────────────
    //
    // MangaPlus Protobuf structure (abbreviated):
    //
    //   message Response {
    //     oneof data { SuccessResult success = 1; }
    //   }
    //   message SuccessResult {
    //     oneof view { MangaViewer manga_viewer = 1; }
    //   }
    //   message MangaViewer {
    //     repeated MangaPage pages = 9;
    //   }
    //   message MangaPage {
    //     oneof page { MangaPage_Img manga_page = 1; }
    //   }
    //   message MangaPage_Img {
    //     string image_url      = 1;
    //     string encryption_key = 2;
    //   }
    //
    private List<PageInfo> parsePages(byte[] data) throws Exception {
        List<PageInfo> result = new ArrayList<>();

        CodedInputStream response = CodedInputStream.newInstance(data);
        response.setRecursionLimit(100);

        // Level 1: Response — field 1 = SuccessResult
        while (!response.isAtEnd()) {
            int tag = response.readTag();
            int field = tag >>> 3;
            int wire  = tag & 0x7;

            if (field == 1 && wire == 2) {
                // SuccessResult embedded
                byte[] successBytes = response.readBytes().toByteArray();
                CodedInputStream success = CodedInputStream.newInstance(successBytes);

                // Level 2: SuccessResult — field 1 = MangaViewer
                while (!success.isAtEnd()) {
                    int t2 = success.readTag();
                    int f2 = t2 >>> 3;
                    int w2 = t2 & 0x7;

                    if (f2 == 10 && w2 == 2) {
                        byte[] viewerBytes = success.readBytes().toByteArray();
                        parseMangaViewer(viewerBytes, result);
                    } else {
                        skipField(success, w2);
                    }
                }
            } else {
                skipField(response, wire);
            }
        }

        return result;
    }

    private void parseMangaViewer(byte[] data, List<PageInfo> result) throws Exception {
        CodedInputStream viewer = CodedInputStream.newInstance(data);

        // Level 3: MangaViewer — field 1 = repeated Page
        while (!viewer.isAtEnd()) {
            int tag  = viewer.readTag();
            int field = tag >>> 3;
            int wire  = tag & 0x7;

            if (field == 1 && wire == 2) {
                byte[] pageBytes = viewer.readBytes().toByteArray();
                parseMangaPage(pageBytes, result);
            } else {
                skipField(viewer, wire);
            }
        }
    }

    private void parseMangaPage(byte[] data, List<PageInfo> result) throws Exception {
        CodedInputStream page = CodedInputStream.newInstance(data);

        // Level 4: MangaPage — field 1 = MangaPage_Img
        while (!page.isAtEnd()) {
            int tag  = page.readTag();
            int field = tag >>> 3;
            int wire  = tag & 0x7;

            if (field == 1 && wire == 2) {
                byte[] imgBytes = page.readBytes().toByteArray();
                PageInfo info = parseMangaPageImg(imgBytes);
                if (info != null && !info.imageUrl.isEmpty()) {
                    result.add(info);
                }
            } else {
                skipField(page, wire);
            }
        }
    }

    private PageInfo parseMangaPageImg(byte[] data) throws Exception {
        CodedInputStream img = CodedInputStream.newInstance(data);
        String imageUrl = "";
        String encryptionKey = "";

        // Level 5: MangaPage_Img — field 1 = image_url, field 5 = encryption_key
        while (!img.isAtEnd()) {
            int tag  = img.readTag();
            int field = tag >>> 3;
            int wire  = tag & 0x7;

            if (field == 1 && wire == 2) {
                imageUrl = img.readString();
            } else if (field == 5 && wire == 2) {
                encryptionKey = img.readString();
            } else {
                skipField(img, wire);
            }
        }

        if (imageUrl.isEmpty()) return null;
        return new PageInfo(imageUrl, encryptionKey);
    }

    /** Skip a field we don't care about. */
    private void skipField(CodedInputStream stream, int wireType) throws Exception {
        switch (wireType) {
            case 0 -> stream.readInt64();          // Varint
            case 1 -> stream.readRawLittleEndian64(); // 64-bit
            case 2 -> stream.readBytes();           // Length-delimited
            case 5 -> stream.readRawLittleEndian32(); // 32-bit
            default -> throw new RuntimeException("Unknown wire type: " + wireType);
        }
    }

    // ── Download + XOR Decrypt + Save ────────────────────────────────────────
    private void downloadAndDecrypt(PageInfo page, Path folder, int index) {
        int retry = 3;
        int[] delays = {0, 500, 1000};

        while (retry > 0) {
            int attempt = 3 - retry;
            try {
                if (attempt > 0) Thread.sleep(delays[attempt]);

                // 1. Download
                byte[] data = downloadBytes(page.imageUrl);

                // 2. XOR decrypt (only if key is provided)
                if (!page.encryptionKey.isEmpty()) {
                    data = xorDecrypt(data, page.encryptionKey);
                }

                // 3. Save
                String ext = page.imageUrl.contains(".png") ? "png" : "jpg";
                String fileName = String.format("%03d.%s", index, ext);
                Path output = folder.resolve(fileName);
                Files.copy(new ByteArrayInputStream(data), output, StandardCopyOption.REPLACE_EXISTING);

                System.out.println("✔ [" + index + "] " + fileName);
                return;

            } catch (Exception e) {
                retry--;
                if (retry == 0) {
                    System.err.println("❌ Fail sau 3 lần retry [" + index + "]: " + page.imageUrl
                            + " | " + e.getMessage());
                }
            }
        }
    }

    private byte[] downloadBytes(String imageUrl) throws Exception {
        URL url = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0");
        conn.setRequestProperty("Referer", REFERER);

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("HTTP " + code + " khi tải " + imageUrl);
        }

        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        }
    }

    /**
     * XOR decrypt: mỗi byte = data[i] ^ key[i % key.length]
     * key là chuỗi hex, ví dụ "a1b2c3d4e5f6..."
     */
    private byte[] xorDecrypt(byte[] data, String hexKey) {
        byte[] key = hexToBytes(hexKey);
        int keyLen = key.length;
        for (int i = 0; i < data.length; i++) {
            data[i] ^= key[i % keyLen];
        }
        return data;
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] result = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            result[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return result;
    }
}
