package org.example.fetch_manga.service.site;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public abstract class Manga {

    protected String basePath = "E:\\Manga Stuff\\Page";

    /**
     * Tên folder do người dùng nhập. Nếu để trống thì dùng tên mặc định của từng service.
     * Có thể là tên tương đối (nằm trong basePath) hoặc đường dẫn tuyệt đối.
     */
    protected String folderName;

    // Tăng lên 100 thread
    protected ExecutorService executor = Executors.newFixedThreadPool(100);

    // Track các future để biết khi nào xong
    protected List<Future<?>> futures = new ArrayList<>();

    /** Thư mục gốc của thư viện truyện. GUI có thể đổi để không bị set cứng. */
    public void setBasePath(String path) {
        if (path != null && !path.isBlank()) this.basePath = path.trim();
    }

    public String getBasePath() {
        return basePath;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    /** Overload cho các service cần đặt tên folder (vd: HimaTen). */
    public void fetch(String name, String url) throws Exception {
        setFolderName(name);
        fetch(url);
    }

    public void fetch(String url) throws Exception {
        long start = System.currentTimeMillis();

        String chapNumber = extractChapNumber(url);
        parseAndDownload(url, chapNumber);

        executor.shutdown();
        executor.awaitTermination(3, TimeUnit.MINUTES); // Giảm từ 10 phút xuống 3

        long end = System.currentTimeMillis();
        System.out.println("✅ Hoàn thành trong: " + (end - start) / 1000.0 + "s");
    }

    protected abstract void parseAndDownload(String url, String chapNumber) throws Exception;

    protected abstract String extractChapNumber(String url);

    protected Path createFolder(String mangaName, String chapNumber) throws Exception {
        Path folder;

        if (folderName == null || folderName.isBlank()) {
            // Không nhập gì → giữ nguyên quy ước cũ của từng service
            folder = Paths.get(basePath, mangaName, "Chap " + chapNumber);
        } else {
            Path custom = Paths.get(folderName.trim());
            // Đường dẫn tuyệt đối → dùng đúng thư mục đó, không nối thêm "Chap N"
            folder = custom.isAbsolute() ? custom : Paths.get(basePath, folderName.trim(), "Chap " + chapNumber);
        }

        Files.createDirectories(folder);
        return folder;
    }

    protected void downloadImage(String imageUrl, Path folder, int index, String referer) {

        Future<?> future = executor.submit(() -> {
            int retry = 3;
            int[] delays = {0, 500, 1000};

            while (retry > 0) {
                int attempt = 3 - retry;
                try {
                    if (attempt > 0) {
                        Thread.sleep(delays[attempt]);
                    }

                    URL url = new URL(imageUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(8000);

                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    connection.setRequestProperty("Referer", referer);
                    connection.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
                    connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
                    connection.setRequestProperty("Connection", "keep-alive");

                    int responseCode = connection.getResponseCode();
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        throw new RuntimeException("HTTP " + responseCode);
                    }

                    try (InputStream in = connection.getInputStream()) {
                        String ext = imageUrl.contains(".png") ? "png" : "jpg";
                        String fileName = String.format("%03d.%s", index, ext);
                        Path output = folder.resolve(fileName);

                        Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("✔ [" + index + "] " + fileName);
                        break;
                    }

                } catch (Exception e) {
                    retry--;
                    if (retry == 0) {
                        System.err.println("❌ Fail sau 3 lần retry [" + index + "]: " + imageUrl + " | " + e.getMessage());
                    }
                }
            }
        });

        futures.add(future);
    }
}