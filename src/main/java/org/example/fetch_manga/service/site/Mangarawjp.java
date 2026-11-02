package org.example.fetch_manga.service.site;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Mangarawjp extends Manga {

    @Override
    protected void parseAndDownload(String url, String chapNumber) throws Exception {

        Path folder = createFolder("record", chapNumber);

        System.out.println("🔍 Đang parse trang...");

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .referrer("https://mangarawjp.me/")
                .timeout(10000) // Giảm từ 20s xuống 10s
                .get();

        Elements images = doc.select("div.ng-scope img");

        if (images.isEmpty()) {
            // Thử các selector khác phòng layout thay đổi
            images = doc.select("img[data-original]");
        }

        if (images.isEmpty()) {
            throw new RuntimeException("Không tìm thấy ảnh! Kiểm tra lại selector.");
        }

        // ⚡ Thu thập tất cả URL trước, rồi mới submit download
        List<String> imageUrls = new ArrayList<>();
        for (Element img : images) {
            String imgUrl = img.attr("data-original");
            if (imgUrl.isEmpty()) imgUrl = img.attr("src");
            if (!imgUrl.isEmpty()) imageUrls.add(imgUrl);
        }

        System.out.println("📦 Tìm thấy " + imageUrls.size() + " ảnh, bắt đầu download song song...");

        // Submit tất cả cùng lúc
        for (int i = 0; i < imageUrls.size(); i++) {
            downloadImage(imageUrls.get(i), folder, i + 1, url);
        }
    }

    @Override
    protected String extractChapNumber(String url) {
        if (!url.contains("-wa")) {
            throw new IllegalArgumentException("URL không đúng format! Phải chứa '-wa'");
        }

        String[] parts = url.split("/");
        String chapPart = parts[parts.length - 1];
        return chapPart.replace("-wa", "");
    }
}