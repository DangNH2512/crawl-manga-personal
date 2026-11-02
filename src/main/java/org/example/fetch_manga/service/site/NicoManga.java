package org.example.fetch_manga.service.site;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NicoManga - Tải ảnh từ nicomanga.com
 *
 * Cách dùng:
 *   NicoManga nm = new NicoManga();
 *   nm.fetchChapter("https://nicomanga.com/read-himaten-chapter-84.html", "E:\\Manga\\output");
 *
 * URL format: https://nicomanga.com/read-{manga-name}-chapter-{N}.html
 *
 * Cơ chế:
 *   1. Dùng Jsoup để tải HTML trang đọc.
 *   2. Parse HTML tìm tất cả thẻ <img> chứa URL ảnh CDN (ihlv1.xyz).
 *   3. Fallback: tìm trong JS source nếu ảnh lazy-load.
 *   4. Download song song vào thư mục chỉ định.
 */
public class NicoManga extends Manga {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";

    private static final String REFERER = "https://nicomanga.com/";

    // ──────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetch một chapter từ URL của NicoManga, lưu vào thư mục chỉ định.
     * VD: https://nicomanga.com/read-himaten-chapter-84.html
     */
    public void fetchChapter(String readerUrl, String saveFolder) throws Exception {
        System.out.println("🔍 URL: " + readerUrl);

        // ── Bước 1: Tải HTML trang đọc ─────────────────────────────────────
        Document doc = Jsoup.connect(readerUrl)
                .userAgent(UA)
                .referrer(REFERER)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(20000)
                .get();

        // ── Bước 2: Parse ảnh từ HTML ──────────────────────────────────────
        List<String> imageUrls = parseImageUrls(doc, readerUrl);

        if (imageUrls.isEmpty()) {
            throw new RuntimeException(
                "Không tìm thấy ảnh nào! Trang có thể yêu cầu JavaScript hoặc đã thay đổi cấu trúc.\n"
                + "Thử mở " + readerUrl + " trên trình duyệt và kiểm tra F12 → Network."
            );
        }

        System.out.println("📦 Tìm thấy " + imageUrls.size() + " ảnh");

        // ── Bước 3: Xác định thư mục lưu ──────────────────────────────────
        Path folder;
        if (saveFolder != null && !saveFolder.isBlank()) {
            folder = Paths.get(saveFolder);
        } else {
            String chapNum = extractChapNumber(readerUrl);
            String mangaName = parseMangaName(readerUrl);
            folder = Paths.get(basePath, "nicomanga", mangaName, "chapter-" + chapNum);
        }
        Files.createDirectories(folder);
        System.out.println("📁 Lưu vào: " + folder.toAbsolutePath());

        // ── Bước 4: Download song song ─────────────────────────────────────
        for (int i = 0; i < imageUrls.size(); i++) {
            downloadImage(imageUrls.get(i), folder, i + 1, readerUrl);
        }

        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.MINUTES);
        System.out.println("✅ Hoàn thành " + imageUrls.size() + " ảnh!");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Parse ảnh từ Document
    // ──────────────────────────────────────────────────────────────────────────

    private List<String> parseImageUrls(Document doc, String pageUrl) {
        List<String> urls = new ArrayList<>();

        // ── Cách 1 (ưu tiên): Parse window.chapterImages từ <script> ────────
        // NicoManga lưu danh sách ảnh trong JS:
        //   window.chapterImages=["url1","url2",...];
        // Thẻ script có type="text/psajs" (PageSpeed), Jsoup vẫn đọc nội dung text.
        String html = doc.html();
        urls = extractChapterImagesFromJS(html);
        if (!urls.isEmpty()) {
            System.out.println("✅ window.chapterImages tìm thấy " + urls.size() + " ảnh");
            return urls;
        }

        // ── Cách 2: Tìm img trong các container thường gặp ─────────────────
        String[] containerSelectors = {
            "div.chapter-content img",
            "div#chapter-images img",
            "div.reading-content img",
            "div.chapter img",
            "div#reading img",
            "div.manga-reading-section img",
            "div[class*='chapter'] img",
            "div[id*='chapter'] img",
            "div.pages img",
            "article img"
        };

        for (String sel : containerSelectors) {
            Elements imgs = doc.select(sel);
            if (!imgs.isEmpty()) {
                System.out.println("✅ Selector \"" + sel + "\" tìm thấy " + imgs.size() + " ảnh");
                for (Element img : imgs) {
                    String src = getBestSrc(img);
                    if (isMangaImage(src)) urls.add(src);
                }
                if (!urls.isEmpty()) return urls;
            }
        }

        // ── Cách 3: Tìm tất cả img có src từ CDN ──────────────────────────
        Elements allImgs = doc.select("img");
        for (Element img : allImgs) {
            String src = getBestSrc(img);
            if (isMangaImage(src)) {
                urls.add(src);
            }
        }
        if (!urls.isEmpty()) {
            System.out.println("✅ Fallback img scan tìm thấy " + urls.size() + " ảnh CDN");
            return urls;
        }

        // ── Cách 4: Tìm URL ảnh CDN rời rạc trong toàn bộ HTML ────────────
        Pattern[] patterns = {
            Pattern.compile("\"(https?://[^\"]*ihlv\\d*\\.xyz[^\"]+\\.(?:jpg|jpeg|png|webp))\""),
            Pattern.compile("'(https?://[^']*ihlv\\d*\\.xyz[^']+\\.(?:jpg|jpeg|png|webp))'"),
            Pattern.compile("src=[\"'](https?://[^\"']*ihlv\\d*\\.xyz[^\"']+)[\"']"),
            Pattern.compile("\"(https?://[^\"]+/images[^\"]+\\.(?:jpg|jpeg|png|webp))\""),
        };

        for (Pattern p : patterns) {
            Matcher m = p.matcher(html);
            while (m.find()) {
                String u = cleanUrl(m.group(1));
                if (!u.isEmpty() && !urls.contains(u)) urls.add(u);
            }
            if (!urls.isEmpty()) {
                System.out.println("✅ JS regex tìm thấy " + urls.size() + " ảnh");
                return urls;
            }
        }

        return urls;
    }

    /**
     * Parse mảng window.chapterImages=[...] từ HTML source.
     * Mỗi URL có thể chứa escaped slash (\/) và trailing \n\r cần xử lý.
     */
    private List<String> extractChapterImagesFromJS(String html) {
        List<String> urls = new ArrayList<>();

        // Tìm window.chapterImages=[...]
        Pattern p = Pattern.compile("window\\.chapterImages\\s*=\\s*\\[([^]]+)]");
        Matcher m = p.matcher(html);
        if (!m.find()) return urls;

        String arrayContent = m.group(1);

        // Tách từng URL trong mảng (nằm giữa dấu " hoặc ')
        Pattern urlPattern = Pattern.compile("[\"']([^\"']+)[\"']");
        Matcher urlMatcher = urlPattern.matcher(arrayContent);
        while (urlMatcher.find()) {
            String url = cleanUrl(urlMatcher.group(1));
            if (!url.isEmpty()) {
                urls.add(url);
            }
        }

        return urls;
    }

    /** Unescape và trim URL: \/ → /, loại bỏ \n \r và khoảng trắng thừa. */
    private String cleanUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw
                .replace("\\/", "/")   // JS escaped slash
                .replace("\\n", "")    // literal \n in source
                .replace("\\r", "")    // literal \r in source
                .replaceAll("[\\n\\r]", "") // actual newline/CR chars
                .trim();
    }

    /** Lấy URL tốt nhất từ các attribute của thẻ img (hỗ trợ lazy-load). */
    private String getBestSrc(Element img) {
        for (String attr : new String[]{"data-src", "data-original", "data-lazy-src", "src"}) {
            String v = img.attr(attr);
            if (!v.isEmpty() && !v.startsWith("data:")) return v;
        }
        return "";
    }

    /** Kiểm tra URL có phải ảnh manga CDN không. */
    private boolean isMangaImage(String url) {
        if (url == null || url.isBlank()) return false;
        return url.contains("ihlv") // CDN chính của nicomanga
            || (url.matches("https?://.+") && url.matches(".*\\.(jpg|jpeg|png|webp)(\\?.*)?$"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private String parseMangaName(String url) {
        // https://nicomanga.com/read-himaten-chapter-84.html → himaten
        Pattern p = Pattern.compile("/read-(.+?)-chapter-");
        Matcher m = p.matcher(url);
        return m.find() ? m.group(1) : "unknown";
    }

    @Override
    protected String extractChapNumber(String url) {
        // chapter-84 → 84
        Pattern p = Pattern.compile("chapter-(\\d+(?:\\.\\d+)?)");
        Matcher m = p.matcher(url);
        return m.find() ? m.group(1) : "unknown";
    }

    @Override
    protected void parseAndDownload(String url, String chapNumber) throws Exception {
        fetchChapter(url, null);
    }
}
