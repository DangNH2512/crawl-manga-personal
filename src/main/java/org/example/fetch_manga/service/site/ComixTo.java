package org.example.fetch_manga.service.site;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ComixTo extends Manga {

    @Override
    protected void parseAndDownload(String url, String chapNumber) throws Exception {
        // Tạo folder tên chapter trong E:\Manga Stuff\Page\gai tho
        Path folder = createFolder("gai tho", chapNumber);

        System.out.println("🔍 Đang parse trang Comix.to... Lưu vào: " + folder);

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(15000)
                .get();

        String html = doc.html();
        
        // Trích xuất các URLs hình ảnh có trong JSON
        Pattern p = Pattern.compile("\\\\?\"url\\\\?\":\\\\?\"(https?://[^\\\\\"\\\\]+\\.(?:webp|jpg|png|jpeg))\\\\?\"");
        Matcher m = p.matcher(html);

        List<String> imageUrls = new ArrayList<>();
        while (m.find()) {
            String imgUrl = m.group(1).replace("\\/", "/");
            if (!imageUrls.contains(imgUrl)) {
                imageUrls.add(imgUrl);
            }
        }

        // Bổ sung thêm fallback nếu trang render kiểu khác
        if (imageUrls.isEmpty()) {
             // Fallback
             org.jsoup.select.Elements imgs = doc.select("img");
             for (org.jsoup.nodes.Element img : imgs) {
                 String src = img.attr("src");
                 if(src.contains("webp") || src.contains("jpg")) {
                     if (!imageUrls.contains(src) && src.startsWith("http")) {
                        imageUrls.add(src);
                     }
                 }
             }
        }

        if (imageUrls.isEmpty()) {
            throw new RuntimeException("Không tìm thấy ảnh! Regex có thể không khớp.");
        }

        System.out.println("📦 Tìm thấy " + imageUrls.size() + " ảnh, bắt đầu download & convert...");

        for (int i = 0; i < imageUrls.size(); i++) {
            downloadImage(imageUrls.get(i), folder, i + 1, url);
        }
    }

    @Override
    protected String extractChapNumber(String url) {
        // vd url: https://comix.to/title/n85jy-senryuu-shoujo/3240974-chapter-156
        String[] parts = url.split("-chapter-");
        if (parts.length < 2) {
            throw new IllegalArgumentException("URL không đúng format! Phải chứa '-chapter-'");
        }
        return parts[parts.length - 1].split("/")[0].split("\\?")[0];
    }

    @Override
    protected void downloadImage(String imageUrl, Path folder, int index, String referer) {
        Future<?> future = executor.submit(() -> {
            int retry = 4;
            int[] delays = {0, 1000, 3000, 5000};

            while (retry > 0) {
                int attempt = 4 - retry;
                try {
                    if (attempt > 0) {
                        Thread.sleep(delays[attempt - 1]);
                    }

                    URL url = new URL(imageUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(20000);

                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                    connection.setRequestProperty("Referer", referer);
                    connection.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");

                    int responseCode = connection.getResponseCode();
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        throw new RuntimeException("HTTP " + responseCode);
                    }

                    try (InputStream in = connection.getInputStream()) {
                        // Converter WebP -> PNG
                        if (imageUrl.contains(".webp")) {
                            BufferedImage image = ImageIO.read(in);
                            if (image == null) {
                                throw new RuntimeException("ImageIO.read() trả về null. WebP converter plugin không khởi tạo được.");
                            }
                            String fileName = String.format("%03d.png", index);
                            Path output = folder.resolve(fileName);
                            ImageIO.write(image, "png", output.toFile());
                            System.out.println("✔ [" + index + "] " + fileName + " (converted from webp)");
                        } else {
                            String ext = imageUrl.substring(imageUrl.lastIndexOf('.') + 1);
                            if (ext.contains("?")) ext = ext.substring(0, ext.indexOf('?'));
                            if (!ext.matches("(?i)png|jpg|jpeg")) ext = "jpg"; // default fallback
                            
                            String fileName = String.format("%03d.%s", index, ext);
                            Path output = folder.resolve(fileName);

                            Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("✔ [" + index + "] " + fileName);
                        }
                        break;
                    }

                } catch (Exception e) {
                    retry--;
                    if (retry == 0) {
                        System.err.println("❌ Lỗi sau 4 lần thử [" + index + "]: " + imageUrl + " | " + e.getMessage());
                    }
                }
            }
        });

        futures.add(future);
    }
}
