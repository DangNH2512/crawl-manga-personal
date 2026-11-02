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

public class MangaPill extends Manga {

    @Override
    protected void parseAndDownload(String url, String chapNumber) throws Exception {
        // Tạo folder đích tĩnh theo yêu cầu (E:\Manga Stuff\Page\comic\[chapter])
        Path folder = createFolder("comic", chapNumber);

        System.out.println("🔍 Đang parse trang MangaPill... Lưu vào thư mục: " + folder.toAbsolutePath());

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(15000)
                .get();

        // Hình ảnh trong mangapill thường nằm trong cặp thẻ <picture> <img> có data-src
        Elements images = doc.select("picture img");

        List<String> imageUrls = new ArrayList<>();
        for (Element img : images) {
            String imgUrl = img.attr("data-src");
            if (imgUrl.isEmpty()) {
                imgUrl = img.attr("src");
            }
            if (!imgUrl.isEmpty()) {
                imageUrls.add(imgUrl.trim());
            }
        }

        if (imageUrls.isEmpty()) {
            throw new RuntimeException("Không tìm thấy ảnh! Kiểm tra lại selector hoặc URL có thể bị block.");
        }

        System.out.println("📦 Tìm thấy " + imageUrls.size() + " ảnh, bắt đầu download song song...");

        for (int i = 0; i < imageUrls.size(); i++) {
            downloadImage(imageUrls.get(i), folder, i + 1, url);
        }
    }

    @Override
    protected String extractChapNumber(String url) {
        // vd url: https://mangapill.com/chapters/6675-10094000/yahari-ore-no-seishun-love-comedy-wa-machigatteiru-comic-chapter-94
        String[] parts = url.split("-chapter-");
        if (parts.length < 2) {
            // Cố gắng tìm cách khác nếu URL không chứa "-chapter-"
            String[] slashParts = url.split("/");
            String lastPart = slashParts[slashParts.length - 1];
            if (lastPart.contains("chapter-")) {
                return lastPart.split("chapter-")[1];
            }
            throw new IllegalArgumentException("URL không đúng format! Không chứa được số chapter ở đuôi.");
        }
        return parts[parts.length - 1];
    }
}
