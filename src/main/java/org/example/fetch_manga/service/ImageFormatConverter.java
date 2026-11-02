package org.example.fetch_manga.service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Chuyển mọi ảnh trong một thư mục sang một định dạng đích (png hoặc jpg).
 *
 * <p>Vì sao cần: nhiều nguồn (Comic Walker, Manga One...) tải về ảnh <b>WebP</b> gốc để
 * khỏi mất chất lượng, nhưng Photoshop bản cũ lại không mở được WebP. Bước này chạy
 * <b>sau khi tải xong</b>, decode ảnh rồi ghi lại thành png/jpg — hai định dạng
 * Photoshop luôn mở được — và xoá file gốc để không còn hai bản trùng.</p>
 *
 * <p>Ảnh vốn đã đúng định dạng đích thì bỏ qua. Nhờ chạy trước bước tạo .psd nên file
 * psd được dựng từ ảnh đã chuyển, không sinh psd cho cả bản gốc lẫn bản mới.</p>
 */
public final class ImageFormatConverter {

    private static final Set<String> CONVERTIBLE =
            Set.of("webp", "jpg", "jpeg", "png", "bmp", "gif", "tif", "tiff");

    private static final float JPG_QUALITY = 0.92f;

    private ImageFormatConverter() { }

    /** Kết quả một lượt chuyển, để báo lại cho người dùng. */
    public static final class Result {
        private final int converted;
        private final int skipped;
        private final int failed;

        Result(int converted, int skipped, int failed) {
            this.converted = converted;
            this.skipped = skipped;
            this.failed = failed;
        }

        public int getConverted() { return converted; }
        public int getSkipped()   { return skipped; }
        public int getFailed()    { return failed; }

        @Override
        public String toString() {
            return "chuyển " + converted + ", giữ nguyên " + skipped + ", lỗi " + failed;
        }
    }

    /**
     * @param folder    thư mục chứa ảnh vừa tải
     * @param target    định dạng đích: {@code "png"} hoặc {@code "jpg"}
     * @param recursive có xử lý cả thư mục con hay không
     */
    public static Result convert(Path folder, String target, boolean recursive)
            throws IOException {

        String ext = normalize(target);
        if (ext == null) {
            throw new IOException("Định dạng đích không hợp lệ: " + target);
        }
        if (folder == null || !Files.isDirectory(folder)) {
            throw new IOException("Không tìm thấy thư mục: " + folder);
        }

        List<Path> images = collect(folder, recursive);
        if (images.isEmpty()) return new Result(0, 0, 0);

        System.out.println("🔄 Chuyển " + images.size() + " ảnh sang ." + ext + "...");

        int converted = 0;
        int skipped = 0;
        int failed = 0;

        for (Path image : images) {
            String current = extensionOf(image);
            // Đã đúng định dạng đích (coi jpeg ≡ jpg) thì khỏi đụng vào.
            if (current.equals(ext) || (ext.equals("jpg") && current.equals("jpeg"))) {
                skipped++;
                continue;
            }
            try {
                convertOne(image, ext);
                converted++;
            } catch (Exception e) {
                failed++;
                System.err.println("❌ Không chuyển được " + image.getFileName() + ": " + e.getMessage());
            }
        }

        Result result = new Result(converted, skipped, failed);
        System.out.println("🔄 Xong định dạng: " + result);
        return result;
    }

    private static void convertOne(Path image, String ext) throws IOException {
        BufferedImage src = ImageIO.read(image.toFile());
        if (src == null) {
            throw new IOException("không đọc được (thiếu bộ giải mã?)");
        }

        Path out = replaceExtension(image, ext);

        if (ext.equals("png")) {
            if (!ImageIO.write(src, "png", out.toFile())) {
                throw new IOException("không có bộ ghi PNG");
            }
        } else {
            writeJpg(src, out);
        }

        // Chỉ xoá bản gốc sau khi bản mới đã ghi thành công, và nếu tên khác nhau.
        if (!out.equals(image)) {
            Files.deleteIfExists(image);
        }
        System.out.println("✔ ." + ext + "  " + out.getFileName());
    }

    /**
     * JPG không có alpha nên ghép ảnh lên nền trắng trước, rồi ghi ở chất lượng cao.
     * Bộ upscale cũng dùng hàm này để ảnh jpg ghi ra giống hệt bước đổi định dạng.
     */
    public static void writeJpg(BufferedImage src, Path out) throws IOException {
        BufferedImage rgb = src;
        if (src.getTransparency() != BufferedImage.OPAQUE || src.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, src.getWidth(), src.getHeight());
            g.drawImage(src, 0, 0, null);
            g.dispose();
        }

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPG_QUALITY);

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out.toFile())) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static String normalize(String target) {
        if (target == null) return null;
        String t = target.trim().toLowerCase(Locale.ROOT);
        if (t.equals("jpeg")) t = "jpg";
        return (t.equals("png") || t.equals("jpg")) ? t : null;
    }

    private static List<Path> collect(Path folder, boolean recursive) throws IOException {
        int depth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> stream = Files.walk(folder, depth)) {
            List<Path> out = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(p -> CONVERTIBLE.contains(extensionOf(p)))
                    .forEach(out::add);
            return out;
        }
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static Path replaceExtension(Path file, String ext) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return file.resolveSibling(base + "." + ext);
    }
}
