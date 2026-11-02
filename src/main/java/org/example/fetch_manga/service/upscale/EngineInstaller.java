package org.example.fetch_manga.service.upscale;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Tải và cài engine upscale vào {@code %USERPROFILE%/.fetch_manga/upscalers/<engine>}.
 *
 * <p>Engine không nằm sẵn trong bản exe vì cả ba cộng lại gần 130 MB, trong khi đa số
 * chỉ dùng một. Lần đầu chọn engine nào thì tải engine đó.</p>
 *
 * <p>Thứ tự cài được giữ an toàn: tải về một file tạm riêng → so SHA-256 → giải nén vào
 * thư mục tạm riêng → đổi tên thành thư mục thật. Đứt mạng, tắt ứng dụng hay hai cửa sổ
 * cùng cài một lúc thì cũng chỉ còn file tạm, không bao giờ có một bản cài dở.</p>
 */
public final class EngineInstaller {

    /** Báo tiến độ tải: số byte đã nhận trên tổng số byte. */
    @FunctionalInterface
    public interface Progress {
        void update(long done, long total);
    }

    private static final Path DEFAULT_ROOT =
            Paths.get(System.getProperty("user.home", "."), ".fetch_manga", "upscalers");

    /** Mạng đứng quá lâu thì báo lỗi, thay vì treo luồng nền mãi mãi. */
    private static final int TIMEOUT_MILLIS = 30_000;

    private final Path root;
    /** Khoá ngắt được: lượt tải chương đang chờ cửa sổ xem thử cài xong vẫn bấm Huỷ được. */
    private final ReentrantLock lock = new ReentrantLock();

    public EngineInstaller() {
        this(DEFAULT_ROOT);
    }

    public EngineInstaller(Path root) {
        this.root = root;
    }

    public Path getRoot() {
        return root;
    }

    public boolean isInstalled(UpscaleEngine engine) {
        return findExecutable(engine).isPresent();
    }

    /**
     * Tìm file exe trong thư mục cài. Phải dò vì mỗi file zip xếp khác nhau: Real-ESRGAN
     * để exe ngay gốc, hai engine còn lại bọc thêm một thư mục mang tên phiên bản.
     */
    public Optional<Path> findExecutable(UpscaleEngine engine) {
        Path dir = root.resolve(engine.folderName());
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> stream = Files.walk(dir, 3)) {
            return stream
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(engine.getExeName()))
                    .filter(Files::isRegularFile)
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Path install(UpscaleEngine engine, Progress progress) throws IOException, InterruptedException {
        return install(engine, progress, () -> false);
    }

    /**
     * Cài engine nếu chưa có và trả về đường dẫn exe.
     *
     * @param cancelled được hỏi sau mỗi khối dữ liệu; trả về {@code true} thì dừng tải
     */
    public Path install(UpscaleEngine engine, Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {

        lock.lockInterruptibly();
        try {
            Optional<Path> existing = findExecutable(engine);
            if (existing.isPresent()) return existing.get();

            Files.createDirectories(root);
            // Tên tạm riêng cho từng lượt, để hai cửa sổ ứng dụng cùng cài không giẫm lên nhau.
            Path zip = Files.createTempFile(root, engine.folderName() + "-", ".zip.part");
            Path staging = Files.createTempDirectory(root, engine.folderName() + "-staging-");
            Path target = root.resolve(engine.folderName());

            try {
                download(engine, zip, progress == null ? (done, total) -> { } : progress, cancelled);
                extract(zip, staging);
                if (findExecutable(engine).isEmpty()) {
                    deleteTree(target);
                    try {
                        Files.move(staging, target);
                    } catch (FileAlreadyExistsException e) {
                        // Cửa sổ khác vừa cài xong trước — dùng bản của nó.
                    }
                }
            } finally {
                Files.deleteIfExists(zip);
                deleteTree(staging);
            }

            return findExecutable(engine).orElseThrow(() ->
                    new IOException("Đã giải nén nhưng không thấy " + engine.getExeName()));
        } finally {
            lock.unlock();
        }
    }

    private void download(UpscaleEngine engine, Path zip, Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {

        HttpURLConnection connection = (HttpURLConnection) new URL(engine.getDownloadUrl()).openConnection();
        // GitHub chuyển hướng sang máy chủ lưu file (https → https), HttpURLConnection tự theo.
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent", "FetchManga");

        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("Tải engine thất bại: HTTP " + status);
            }
            long total = connection.getContentLengthLong() > 0 ? connection.getContentLengthLong() : engine.getZipSize();
            MessageDigest sha = sha256();

            try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(zip)) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    // Không dựa vào cờ interrupt: vài thư viện mạng tự nuốt mất cờ này.
                    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                        throw new InterruptedException("Đã huỷ tải engine");
                    }
                    out.write(buffer, 0, n);
                    sha.update(buffer, 0, n);
                    done += n;
                    progress.update(done, total);
                }
            }

            String actual = HexFormat.of().formatHex(sha.digest());
            if (!actual.equalsIgnoreCase(engine.getSha256())) {
                throw new IOException("File engine tải về không khớp mã SHA-256 (có thể bị hỏng hoặc bị thay đổi), "
                        + "đã huỷ cài. Hãy thử lại.");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void extract(Path zip, Path staging) throws IOException {
        Files.createDirectories(staging);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path out = staging.resolve(entry.getName()).normalize();
                // Chặn "zip slip": tên mục kiểu ../../x không được thoát ra ngoài thư mục cài.
                if (!out.startsWith(staging)) {
                    throw new IOException("Đường dẫn lạ trong file zip: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime thiếu SHA-256", e);
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
