package org.example.fetch_manga.service.site;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * CuuTruyen - Tải ảnh từ cuutruyen.net
 *
 * Hỗ trợ 2 cách dùng:
 *   1. fetchFromUrl(editUrl)         - tự parse trang edit chương
 *   2. downloadFromHtml(html, folder) - parse từ HTML paste vào
 */
public class CuuTruyen extends Manga {

    // ── Auth credentials ──────────────────────────────────────────────────────
    private String m4uUid   = "3866";
    private String m4uToken = "7uBLVpgreWFZTk2jmPAhsUy9";
    private String cfClearance = "";

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setM4uUid(String uid)          { this.m4uUid = uid; }
    public void setM4uToken(String token)      { this.m4uToken = token; }
    public void setCfClearance(String cf)      { this.cfClearance = cf; }

    // ── CSS selector cho ảnh trên trang edit ─────────────────────────────────
    private static final String IMG_SELECTOR = "img[src*=\"storage-ct.lrclib.net\"]";

    // =========================================================================
    // 1. Fetch trực tiếp từ URL trang edit chương
    //    Ví dụ: https://cuutruyen.net/team-management/475/chapters/57440/edit
    // =========================================================================
    public void fetchFromUrl(String editUrl) throws Exception {
        fetchFromUrl(editUrl, null);
    }

    /**
     * @param editUrl  URL trang edit chương
     * @param saveFolder  Đường dẫn thư mục lưu. Nếu null → tự tạo từ chapter number
     */
    public void fetchFromUrl(String editUrl, String saveFolder) throws Exception {
        System.out.println("🔍 Đang phân tích URL: " + editUrl);

        // Lấy số chương từ URL: .../chapters/57440/edit
        String chapId = extractChapIdFromUrl(editUrl);
        if ("unknown".equals(chapId)) {
            throw new RuntimeException("Không tìm thấy ID chương trong URL!");
        }

        // Gọi thẳng vào API để lấy ảnh (Sử dụng curl gốc của Windows để né Cloudflare block Java/OkHttp)
        String apiUrl = "https://cuutruyen.net/api/v2/chapters/" + chapId + "?include=pages";
        System.out.println("🔍 Đang lấy dữ liệu từ API: " + apiUrl);

        String cookieStr = "m4u_uid=" + m4uUid + "; m4u_token=" + m4uToken;
        if (!cfClearance.isEmpty()) cookieStr += "; cf_clearance=" + cfClearance;

        // Tiến trình curl gọi thẳng ra ngoài hệ điều hành Windows
        ProcessBuilder pb = new ProcessBuilder(
            "curl", "-s",
            "-H", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "-H", "Accept: application/json, text/plain, */*",
            "-H", "Referer: https://cuutruyen.net/",
            "-H", "Cookie: " + cookieStr,
            apiUrl
        );

        Process p = pb.start();
        String jsonResponse = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        p.waitFor();

        if (jsonResponse.isEmpty() || jsonResponse.startsWith("<")) {
            throw new RuntimeException("Lỗi API (Cloudflare chặn hoặc token hết hạn). \nNội dung trả về: " + jsonResponse);
        }

        // Parse JSON thủ công bằng Regex tìm image_original_url (hoặc image_url)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"image_original_url\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(jsonResponse);
        
        List<String> imageUrls = new ArrayList<>();
        while (matcher.find()) {
            imageUrls.add(matcher.group(1));
        }

        // Fallback nếu null original, lấy image_url thường
        if (imageUrls.isEmpty()) {
            pattern = java.util.regex.Pattern.compile("\"image_url\"\\s*:\\s*\"([^\"]+)\"");
            matcher = pattern.matcher(jsonResponse);
            while (matcher.find()) {
                imageUrls.add(matcher.group(1));
            }
        }

        if (imageUrls.isEmpty()) {
            throw new RuntimeException("Không tìm thấy ảnh từ API! Kiểm tra lại URL hoặc Token.");
        }

        // Xác định thư mục lưu
        Path folder;
        if (saveFolder != null && !saveFolder.isEmpty()) {
            folder = Paths.get(saveFolder);
        } else {
            folder = Paths.get(basePath, "balcony", "chapter-" + chapId);
        }
        Files.createDirectories(folder);
        System.out.println("📁 Lưu vào: " + folder);

        System.out.println("📦 Tìm thấy " + imageUrls.size() + " ảnh, bắt đầu download song song...");

        for (int i = 0; i < imageUrls.size(); i++) {
            downloadImage(imageUrls.get(i), folder, i + 1, "https://cuutruyen.net/");
        }

        // Chờ tất cả xong
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.MINUTES);
        System.out.println("✅ Hoàn thành " + imageUrls.size() + " ảnh!");
    }

    // =========================================================================
    // 2. Parse từ đoạn HTML paste vào (option 6 trong menu)
    // =========================================================================
    public void downloadFromHtml(String html, String saveFolder) throws Exception {
        System.out.println("🔍 Đang parse HTML...");

        Document doc = Jsoup.parse(html, "https://cuutruyen.net");
        Elements images = doc.select(IMG_SELECTOR);

        if (images.isEmpty()) {
            images = doc.select("img[src*=\"storage\"]");
        }
        if (images.isEmpty()) {
            throw new RuntimeException("Không tìm thấy ảnh trong HTML đã paste. Kiểm tra lại nội dung.");
        }

        Path folder = Paths.get(saveFolder);
        Files.createDirectories(folder);
        System.out.println("📁 Lưu vào: " + folder);

        downloadImages(images, folder, "https://cuutruyen.net/");

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.MINUTES);
        System.out.println("✅ Xong!");
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================
    private void downloadImages(Elements images, Path folder, String referer) {
        // Thu thập URL
        List<String> urls = new ArrayList<>();
        for (Element img : images) {
            String src = img.attr("src");
            if (src.isEmpty()) src = img.attr("data-src");
            if (!src.isEmpty()) urls.add(src);
        }

        System.out.println("📦 Tìm thấy " + urls.size() + " ảnh, bắt đầu download song song...");

        for (int i = 0; i < urls.size(); i++) {
            downloadImage(urls.get(i), folder, i + 1, referer);
        }

        // Chờ tất cả xong
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("✅ Hoàn thành " + urls.size() + " ảnh!");
    }

    private Connection buildJsoupConn(String url) {
        Map<String, String> cookies = new LinkedHashMap<>();
        cookies.put("m4u_uid",   m4uUid);
        cookies.put("m4u_token", m4uToken);
        if (!cfClearance.isEmpty()) {
            cookies.put("cf_clearance", cfClearance);
        }

        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                .referrer("https://cuutruyen.net/")
                .cookies(cookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "vi,en;q=0.9")
                .timeout(30000)
                .ignoreHttpErrors(true);
    }

    private String extractChapIdFromUrl(String url) {
        // URL dạng: .../chapters/57440/edit  hoặc  .../chapters/57440
        String[] parts = url.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("chapters".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return "unknown";
    }

    // ─── Override Manga abstract methods (không dùng theo flow chuẩn) ─────────
    @Override
    protected void parseAndDownload(String url, String chapNumber) throws Exception {
        fetchFromUrl(url);
    }

    @Override
    protected String extractChapNumber(String url) {
        return extractChapIdFromUrl(url);
    }
}
