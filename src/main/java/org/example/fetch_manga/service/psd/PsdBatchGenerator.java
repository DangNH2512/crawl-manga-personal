package org.example.fetch_manga.service.psd;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Duyệt một thư mục ảnh và sinh file .psd hai lớp cho từng ảnh:
 * {@code 001.jpg} sẽ có thêm {@code 001.psd} nằm ngay cạnh.
 *
 * <p>Ảnh nào đã có .psd thì bỏ qua, nên chạy lại nhiều lần cũng không tốn công.</p>
 */
public final class PsdBatchGenerator {

    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp", "bmp", "gif", "tif", "tiff");

    /**
     * Số luồng nén chạy song song. Giữ thấp vì mỗi luồng ôm cả ảnh gốc lẫn dữ liệu
     * đã nén trong bộ nhớ, mà trang truyện thì khá nặng.
     */
    private static final int MAX_WORKERS = 3;

    private PsdBatchGenerator() { }

    /** Kết quả một lượt chạy, dùng để báo lại cho người dùng. */
    public static final class Result {
        private final int created;
        private final int skipped;
        private final int failed;

        Result(int created, int skipped, int failed) {
            this.created = created;
            this.skipped = skipped;
            this.failed = failed;
        }

        public int getCreated() { return created; }
        public int getSkipped() { return skipped; }
        public int getFailed()  { return failed; }

        public boolean isEmpty() { return created == 0 && skipped == 0 && failed == 0; }

        @Override
        public String toString() {
            return "tạo mới " + created + ", bỏ qua " + skipped + ", lỗi " + failed;
        }
    }

    /**
     * @param folder    thư mục chứa ảnh
     * @param recursive có xử lý cả thư mục con hay không
     * @param overwrite ghi đè file .psd đã tồn tại
     */
    public static Result generate(Path folder, boolean recursive, boolean overwrite)
            throws IOException, InterruptedException {

        if (folder == null || !Files.isDirectory(folder)) {
            throw new IOException("Không tìm thấy thư mục: " + folder);
        }

        List<Path> images = collectImages(folder, recursive);
        if (images.isEmpty()) {
            System.out.println("🖼  Không có ảnh nào trong " + folder + " để tạo .psd.");
            return new Result(0, 0, 0);
        }

        System.out.println("🎨 Tạo .psd cho " + images.size() + " ảnh trong " + folder + "...");

        AtomicInteger created = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        int workers = Math.max(1, Math.min(MAX_WORKERS, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> tasks = new ArrayList<>(images.size());

        try {
            for (Path image : images) {
                // Bắt lỗi ngay trong tác vụ để một ảnh hỏng không làm dừng cả lượt,
                // và để báo rõ file nào hỏng thay vì chỉ đếm số lượng.
                tasks.add(pool.submit(() -> {
                    try {
                        if (convert(image, overwrite)) {
                            created.incrementAndGet();
                        } else {
                            skipped.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        System.err.println("❌ psd  " + image.getFileName() + ": " + e.getMessage());
                    }
                    return null;
                }));
            }
            for (Future<?> task : tasks) {
                try {
                    task.get();
                } catch (ExecutionException e) {
                    failed.incrementAndGet();
                    System.err.println("❌ psd: " + e.getCause());
                }
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.MINUTES);
        }

        Result result = new Result(created.get(), skipped.get(), failed.get());
        System.out.println("🎨 Xong .psd: " + result);
        return result;
    }

    /** @return true nếu đã ghi ra file mới, false nếu bỏ qua vì đã có sẵn */
    private static boolean convert(Path image, boolean overwrite) throws IOException {
        Path output = replaceExtension(image, "psd");

        if (!overwrite && Files.exists(output)) {
            return false;
        }

        BufferedImage source = ImageIO.read(image.toFile());
        if (source == null) {
            throw new IOException("Không đọc được ảnh (thiếu bộ giải mã?): " + image.getFileName());
        }

        PsdWriter.write(source, output);
        System.out.println("✔ psd  " + output.getFileName());
        return true;
    }

    private static Path replaceExtension(Path file, String extension) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return file.resolveSibling(base + "." + extension);
    }

    private static List<Path> collectImages(Path folder, boolean recursive) throws IOException {
        int depth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> stream = Files.walk(folder, depth)) {
            List<Path> images = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(PsdBatchGenerator::isImage)
                    .forEach(images::add);
            images.sort(Comparator.comparing(Path::toString, String::compareToIgnoreCase));
            return images;
        }
    }

    private static boolean isImage(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && IMAGE_EXTENSIONS.contains(name.substring(dot + 1));
    }
}
