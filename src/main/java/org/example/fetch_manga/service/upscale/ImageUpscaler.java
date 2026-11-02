package org.example.fetch_manga.service.upscale;

import org.example.fetch_manga.service.ImageFormatConverter;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Upscale ảnh bằng AI rồi <b>ghi đè</b> lên ảnh gốc.
 *
 * <p>Cách làm: chép các ảnh cần xử lý vào một thư mục tạm dưới tên số thứ tự, gọi
 * engine một lần cho cả thư mục (nạp mô hình lên GPU chỉ một lần, nhanh hơn nhiều so
 * với gọi từng ảnh), engine xuất png không mất dữ liệu, rồi mới mã hoá lại đúng
 * định dạng gốc và thay vào chỗ ảnh cũ.</p>
 *
 * <p>Ảnh gốc chỉ bị thay sau khi bản upscale của chính nó đã ghi xong ra file tạm,
 * và việc thay là một lần đổi tên. Engine lỗi, bị huỷ hay mất điện giữa chừng thì
 * ảnh gốc vẫn nguyên vẹn.</p>
 */
public final class ImageUpscaler {

    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp", "bmp", "gif", "tif", "tiff");

    /** Định dạng engine đọc thẳng được; loại khác được đổi tạm sang png trước khi đưa vào. */
    private static final Set<String> ENGINE_READABLE = Set.of("jpg", "jpeg", "png", "webp");

    private static final float WEBP_QUALITY = 0.92f;

    /** Engine và thư mục tạm đang dùng — để tắt ứng dụng giữa chừng không bỏ lại tiến trình chạy ngầm trên GPU. */
    private static final Set<Process> RUNNING = ConcurrentHashMap.newKeySet();
    private static final Set<Path> WORK_DIRS = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ImageUpscaler::stopAll, "upscale-cleanup"));
    }

    private final EngineInstaller installer;
    private final BooleanSupplier cancelled;

    public ImageUpscaler(EngineInstaller installer) {
        this(installer, () -> false);
    }

    /**
     * @param cancelled hỏi xem người dùng đã bấm Huỷ chưa. Cần riêng cờ này vì cờ interrupt
     *                  của luồng có thể bị thư viện mạng nuốt mất trong lúc tải engine.
     */
    public ImageUpscaler(EngineInstaller installer, BooleanSupplier cancelled) {
        this.installer = installer;
        this.cancelled = cancelled;
    }

    private void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new InterruptedException("Đã huỷ upscale");
        }
    }

    /** Giết mọi engine đang chạy và dọn thư mục tạm. Chạy khi ứng dụng thoát. */
    static void stopAll() {
        for (Process process : RUNNING) {
            kill(process);
        }
        for (Path dir : WORK_DIRS) {
            deleteQuietly(dir);
        }
    }

    /** Giết tiến trình và chờ nó thoát hẳn, để file nó đang ghi được nhả ra trước khi xoá thư mục tạm. */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path createWorkDir(String prefix) throws IOException {
        Path dir = Files.createTempDirectory(prefix);
        WORK_DIRS.add(dir);
        return dir;
    }

    /** Dọn thư mục tạm; lỗi dọn dẹp chỉ ghi chú, không được che mất kết quả thật (xong / huỷ / lỗi). */
    private static void deleteQuietly(Path dir) {
        try {
            EngineInstaller.deleteTree(dir);
            WORK_DIRS.remove(dir);
        } catch (IOException e) {
            System.err.println("⚠ Không dọn được thư mục tạm " + dir + ": " + e.getMessage());
        }
    }

    /** Kết quả một lượt chạy, để báo lại cho người dùng. */
    public static final class Result {
        private final int upscaled;
        private final int skipped;
        private final int failed;

        Result(int upscaled, int skipped, int failed) {
            this.upscaled = upscaled;
            this.skipped = skipped;
            this.failed = failed;
        }

        public int getUpscaled() { return upscaled; }
        public int getSkipped()  { return skipped; }
        public int getFailed()   { return failed; }

        @Override
        public String toString() {
            return "upscale " + upscaled + (skipped > 0 ? ", giữ nguyên " + skipped : "") + ", lỗi " + failed;
        }
    }

    // ── Chọn ảnh ────────────────────────────────────────────────────────────

    /**
     * @param modifiedAfterMillis chỉ lấy file sửa từ mốc này trở đi; {@code 0} để lấy hết.
     *                            Dùng sau khi tải chương, để không upscale lại những
     *                            ảnh cũ đã upscale từ trước nằm cùng thư mục.
     */
    public static List<Path> collectImages(Path folder, boolean recursive, long modifiedAfterMillis)
            throws IOException {

        if (folder == null || !Files.isDirectory(folder)) {
            throw new IOException("Không tìm thấy thư mục: " + folder);
        }
        int depth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> stream = Files.walk(folder, depth)) {
            List<Path> images = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(ImageUpscaler::isImage)
                    .filter(p -> modifiedAfterMillis <= 0 || lastModified(p) >= modifiedAfterMillis)
                    .forEach(images::add);
            images.sort(Comparator.comparing(Path::toString, String::compareToIgnoreCase));
            return images;
        }
    }

    public static boolean isImage(Path file) {
        return IMAGE_EXTENSIONS.contains(extensionOf(file));
    }

    // ── Chạy ────────────────────────────────────────────────────────────────

    /**
     * Upscale từng ảnh trong danh sách và ghi đè lên chính nó.
     *
     * @param targetFormat {@code "original"} giữ định dạng gốc, hoặc {@code "png"} /
     *                     {@code "jpg"} để ghi luôn ra định dạng đó (xoá file gốc khác đuôi)
     */
    public Result upscale(List<Path> images, UpscaleOptions options, String targetFormat)
            throws IOException, InterruptedException {

        if (images.isEmpty()) {
            System.out.println("🔍 Không có ảnh nào để upscale.");
            return new Result(0, 0, 0);
        }

        System.out.println("🔍 Upscale " + images.size() + " ảnh bằng " + options.describe() + "...");

        // Lập kế hoạch trước: theo số pixel thì mỗi ảnh có thể cần một tỉ lệ AI khác nhau,
        // nên gom ảnh theo tỉ lệ và gọi engine một lần cho mỗi nhóm.
        Dimension[] targets = new Dimension[images.size()];
        Map<Integer, List<Integer>> groups = new TreeMap<>();
        int failed = 0;
        int skipped = 0;
        for (int i = 0; i < images.size(); i++) {
            Path image = images.get(i);
            if (options.isScaleMode()) {
                groups.computeIfAbsent(options.getScale(), k -> new ArrayList<>()).add(i);
                continue;
            }
            try {
                Dimension size = readSize(image);
                if (options.isLargeEnough(size.width, size.height)) {
                    skipped++;
                    System.out.println("↷ giữ nguyên  " + image.getFileName()
                            + " (đã " + size.width + "×" + size.height + ")");
                    continue;
                }
                targets[i] = options.targetSize(size.width, size.height);
                groups.computeIfAbsent(options.engineScale(size.width, size.height), k -> new ArrayList<>()).add(i);
            } catch (IOException | RuntimeException e) {
                failed++;
                System.err.println("❌ upscale  " + image.getFileName() + ": " + messageOf(e));
            }
        }

        int upscaled = 0;
        if (!groups.isEmpty()) {
            Path exe = ensureEngine(options.getModel().getEngine());
            checkCancelled();
            Path work = createWorkDir("fetch-manga-upscale-");
            try {
                for (Map.Entry<Integer, List<Integer>> group : groups.entrySet()) {
                    int[] counts = upscaleGroup(exe, work, images, group.getValue(), targets,
                            options, group.getKey(), targetFormat, groups.size() > 1);
                    upscaled += counts[0];
                    failed += counts[1];
                }
            } finally {
                deleteQuietly(work);
            }
        }

        Result result = new Result(upscaled, skipped, failed);
        System.out.println("🔍 Xong upscale: " + result);
        return result;
    }

    /** Chạy engine cho một nhóm ảnh cùng tỉ lệ rồi ghi đè từng ảnh. @return {số ảnh xong, số ảnh lỗi} */
    private int[] upscaleGroup(Path exe, Path work, List<Path> images, List<Integer> indexes,
                               Dimension[] targets, UpscaleOptions options, int scale,
                               String targetFormat, boolean announce)
            throws IOException, InterruptedException {

        Path in = Files.createDirectories(work.resolve("in-" + scale));
        Path out = Files.createDirectories(work.resolve("out-" + scale));

        // Tên tạm theo số thứ tự: tránh mọi rắc rối về ký tự lạ, và hai ảnh trùng
        // tên ở hai thư mục con khác nhau cũng không đè lên nhau.
        List<Integer> ready = new ArrayList<>(indexes.size());
        Map<Integer, String> stamps = new TreeMap<>();
        int failed = 0;
        for (int i : indexes) {
            try {
                stamps.put(i, stamp(images.get(i)));
                stage(images.get(i), in, String.format("%05d", i));
                ready.add(i);
            } catch (IOException | RuntimeException e) {
                failed++;
                System.err.println("❌ upscale  " + images.get(i).getFileName() + ": " + messageOf(e));
            }
        }
        if (ready.isEmpty()) return new int[]{0, failed};

        if (announce) {
            System.out.println("   · " + ready.size() + " ảnh chạy AI " + scale + "x");
        }
        runEngine(exe, options, scale, in, out, ready.size());

        int upscaled = 0;
        for (int i : ready) {
            checkCancelled();
            Path image = images.get(i);
            Path png = out.resolve(String.format("%05d", i) + ".png");
            try {
                if (!Files.isRegularFile(png) || Files.size(png) == 0) {
                    throw new IOException("engine không xuất được ảnh (file hỏng hoặc định dạng lạ?)");
                }
                // Ảnh gốc đổi trong lúc engine chạy (trang còn đang tải dở, hoặc vừa được sửa
                // bằng Photoshop) thì bản upscale đã cũ — giữ nguyên ảnh gốc.
                if (!stamps.get(i).equals(stamp(image))) {
                    throw new IOException("ảnh gốc vừa thay đổi trong lúc upscale (đang tải dở hoặc vừa sửa?), "
                            + "giữ nguyên ảnh gốc");
                }
                Path written = replaceWithUpscaled(image, png, targetFormat, targets[i]);
                upscaled++;
                System.out.println("✔ upscale  " + written.getFileName()
                        + (targets[i] == null ? "" : "  (" + targets[i].width + "×" + targets[i].height + ")"));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                // Bắt cả lỗi runtime (vd bộ ghi WebP ném NullPointerException khi ảnh quá
                // 16383px) và thiếu bộ nhớ, để một ảnh hỏng không dừng cả loạt giữa chừng.
                failed++;
                System.err.println("❌ upscale  " + image.getFileName() + ": " + messageOf(e));
            }
        }
        return new int[]{upscaled, failed};
    }

    /** Dấu vết kích thước + thời gian sửa, để phát hiện ảnh gốc bị thay đổi giữa chừng. */
    private static String stamp(Path file) throws IOException {
        return Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis();
    }

    private static String messageOf(Throwable error) {
        if (error instanceof OutOfMemoryError) return "không đủ bộ nhớ để xử lý ảnh này";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** Upscale một ảnh ra bộ nhớ để xem thử, không đụng tới file gốc. */
    public BufferedImage preview(Path image, UpscaleOptions options) throws IOException, InterruptedException {
        int scale = options.getScale();
        Dimension target = null;
        if (!options.isScaleMode()) {
            Dimension size = readSize(image);
            if (options.isLargeEnough(size.width, size.height)) {
                throw new IOException("Ảnh đã " + size.width + "×" + size.height + ", đạt mục tiêu "
                        + options.getTargetPixels() + "px nên sẽ được giữ nguyên, không upscale.");
            }
            target = options.targetSize(size.width, size.height);
            scale = options.engineScale(size.width, size.height);
        }

        Path exe = ensureEngine(options.getModel().getEngine());
        Path work = createWorkDir("fetch-manga-preview-");
        try {
            Path in = Files.createDirectories(work.resolve("in"));
            Path out = Files.createDirectories(work.resolve("out"));
            stage(image, in, "00000");
            runEngine(exe, options, scale, in, out, 1);

            Path png = out.resolve("00000.png");
            if (!Files.isRegularFile(png)) {
                throw new IOException("Engine không xuất được ảnh: " + image.getFileName());
            }
            return finish(readImage(png), Resampler.isGrayscale(readImage(image)), target);
        } finally {
            deleteQuietly(work);
        }
    }

    private Path ensureEngine(UpscaleEngine engine) throws IOException, InterruptedException {
        Optional<Path> installed = installer.findExecutable(engine);
        if (installed.isPresent()) {
            return installed.get();
        }
        System.out.println("⬇ Chưa có engine " + engine.getDisplayName()
                + ", đang tải về (" + engine.sizeMb() + " MB, chỉ tải một lần)...");
        int[] lastStep = {-1};
        Path exe = installer.install(engine, (done, total) -> {
            int step = (int) (done * 100 / Math.max(1, total)) / 20;
            if (step != lastStep[0]) {
                lastStep[0] = step;
                System.out.println("⬇ " + (step * 20) + "%");
            }
        }, cancelled);
        System.out.println("✔ Đã cài engine " + engine.getDisplayName());
        return exe;
    }

    /** Đưa một ảnh vào thư mục đầu vào của engine, đổi tạm sang png nếu engine không đọc được. */
    private static Path stage(Path image, Path in, String baseName) throws IOException {
        String ext = extensionOf(image);
        if (ENGINE_READABLE.contains(ext)) {
            return Files.copy(image, in.resolve(baseName + "." + ext));
        }
        // gif động / tif nhiều trang: đọc ra chỉ được khung đầu, ghi đè sẽ mất các khung còn lại.
        if (frameCount(image) > 1) {
            throw new IOException("ảnh động hoặc nhiều trang, bỏ qua để không mất khung hình");
        }
        Path png = in.resolve(baseName + ".png");
        if (!ImageIO.write(readImage(image), "png", png.toFile())) {
            throw new IOException("không có bộ ghi PNG");
        }
        return png;
    }

    /**
     * Chạy engine cho cả thư mục và chờ xong. Trong lúc chờ, đếm file đầu ra để báo
     * tiến độ; bị huỷ thì giết tiến trình ngay để GPU được giải phóng.
     */
    private void runEngine(Path exe, UpscaleOptions options, int scale, Path in, Path out, int expected)
            throws IOException, InterruptedException {

        List<String> command = options.getModel()
                .buildCommand(exe, in, out, scale, options.getDenoise());
        Process process = new ProcessBuilder(command)
                .directory(exe.getParent().toFile())
                .redirectErrorStream(true)
                .start();
        RUNNING.add(process);
        try {
            waitForEngine(process, options, out, expected);
        } finally {
            RUNNING.remove(process);
        }
    }

    private void waitForEngine(Process process, UpscaleOptions options, Path out, int expected)
            throws IOException, InterruptedException {

        // Phải đọc hết đầu ra, nếu không bộ đệm đầy sẽ làm engine treo. Chỉ giữ vài dòng
        // cuối để kèm vào thông báo lỗi.
        Deque<String> tail = new ArrayDeque<>();
        Thread reader = new Thread(() -> drain(process, tail), "upscale-engine-output");
        reader.setDaemon(true);
        reader.start();

        int reported = 0;
        try {
            while (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                checkCancelled();
                // File xuất hiện ngay khi engine bắt đầu ghi, nên trừ 1 cho ảnh đang ghi dở.
                int done = Math.max(0, countFiles(out) - 1);
                if (expected > 1 && done > reported) {
                    reported = done;
                    System.out.println("⏳ " + done + "/" + expected);
                }
            }
        } catch (InterruptedException e) {
            kill(process);
            throw e;
        }
        reader.join(2000);

        if (process.exitValue() != 0) {
            String detail;
            synchronized (tail) {
                detail = String.join(" | ", tail);
            }
            throw new IOException("Engine " + options.getModel().getEngine().getDisplayName()
                    + " báo lỗi (mã " + process.exitValue() + ")"
                    + (detail.isEmpty() ? "" : ": " + detail));
        }
    }

    private static void drain(Process process, Deque<String> tail) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
            String line;
            while ((line = r.readLine()) != null) {
                String text = line.trim();
                // Bỏ thông tin GPU ("[0 NVIDIA ...]") và phần trăm từng mảnh ảnh.
                if (text.isEmpty() || text.startsWith("[") || text.endsWith("%")) continue;
                synchronized (tail) {
                    tail.addLast(text);
                    if (tail.size() > 6) tail.removeFirst();
                }
            }
        } catch (IOException ignored) {
            // Tiến trình bị giết thì luồng đọc đóng theo, không có gì phải làm.
        }
    }

    // ── Ghi đè ảnh gốc ──────────────────────────────────────────────────────

    /**
     * @param target kích thước cuối cùng, hoặc {@code null} để giữ nguyên ảnh engine xuất ra
     * @return file đã ghi (khác file gốc nếu đổi định dạng)
     */
    private static Path replaceWithUpscaled(Path original, Path png, String targetFormat, Dimension target)
            throws IOException {
        String originalExt = extensionOf(original);
        String format = chooseFormat(originalExt, targetFormat);

        // Cùng định dạng thì giữ nguyên tên gốc (kể cả đuôi viết hoa hay .jpeg), tránh
        // trên Windows "001.JPG" và "001.jpg" là một file nhưng bị coi là hai.
        Path destination = sameFormat(originalExt, format)
                ? original
                : replaceExtension(original, format);
        // Đổi đuôi (gif/bmp/tif → png) mà đã có sẵn một file khác mang tên mới thì không đè lên nó.
        if (!destination.equals(original) && "original".equals(targetFormat) && Files.exists(destination)) {
            throw new IOException("đã có file " + destination.getFileName() + ", bỏ qua để không ghi đè nhầm");
        }
        Path temp = destination.resolveSibling(destination.getFileName() + ".upscale-tmp");

        // Giải mã đầy đủ trước khi đụng tới ảnh gốc: engine vẫn trả mã 0 khi ghi png bị lỗi
        // giữa chừng (vd đầy ổ đĩa), chỉ kiểm tra dung lượng > 0 là không đủ.
        boolean gray = Resampler.isGrayscale(readImage(original));
        BufferedImage upscaled = finish(readImage(png), gray, target);

        try {
            // File tạm sót lại từ lần chạy bị ngắt: bộ ghi ImageIO không cắt ngắn file có sẵn,
            // ghi chồng lên sẽ để lại rác ở đuôi file.
            Files.deleteIfExists(temp);
            switch (format) {
                case "png":
                    if (target == null && !gray) {
                        Files.copy(png, temp, StandardCopyOption.REPLACE_EXISTING);
                    } else if (!ImageIO.write(upscaled, "png", temp.toFile())) {
                        throw new IOException("không có bộ ghi PNG");
                    }
                    break;
                case "jpg":
                    ImageFormatConverter.writeJpg(upscaled, temp);
                    break;
                case "webp":
                    writeWebp(upscaled, temp);
                    break;
                default:
                    throw new IOException("Không ghi được định dạng " + format);
            }
            moveReplacing(temp, destination);
        } finally {
            Files.deleteIfExists(temp);
        }

        if (!destination.equals(original)) {
            Files.deleteIfExists(original);
        }
        return destination;
    }

    /** Định dạng ghi ra: theo lựa chọn của người dùng, không thì theo gốc; gif/bmp/tif thành png. */
    private static String chooseFormat(String originalExt, String targetFormat) {
        if ("png".equals(targetFormat) || "jpg".equals(targetFormat)) {
            return targetFormat;
        }
        switch (originalExt) {
            case "jpg":
            case "jpeg":
                return "jpg";
            case "webp":
                return "webp";
            default:
                // gif chỉ có 256 màu, bmp/tif thì quá nặng — png là lựa chọn không mất gì.
                return "png";
        }
    }

    private static boolean sameFormat(String originalExt, String format) {
        return originalExt.equals(format) || ("jpg".equals(format) && "jpeg".equals(originalExt));
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeWebp(BufferedImage image, Path out) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("webp");
        if (!writers.hasNext()) {
            throw new IOException("không có bộ ghi WebP");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        for (String type : param.getCompressionTypes()) {
            if (type.equalsIgnoreCase("Lossy")) param.setCompressionType(type);
        }
        param.setCompressionQuality(WEBP_QUALITY);

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out.toFile())) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    // ── Hoàn thiện ảnh sau AI ───────────────────────────────────────────────

    /**
     * Xử lý ảnh engine xuất ra trước khi ghi: trang trắng đen thì ép về xám để xoá màu lẫn
     * (mạng AI chạy trên RGB nên hay tô nhẹ màu quanh nét mực — file vẫn giữ RGB, chỉ là
     * R=G=B nên trang màu không bị ảnh hưởng), rồi đưa về đúng kích thước bằng Lanczos.
     */
    static BufferedImage finish(BufferedImage engineOutput, boolean grayscale, Dimension target) {
        BufferedImage image = grayscale ? Resampler.toGray(engineOutput) : engineOutput;
        if (target != null) {
            image = Resampler.resize(image, target.width, target.height);
        }
        return grayscale ? Resampler.toRgb(image) : image;
    }

    // ── Tiện ích ────────────────────────────────────────────────────────────

    private static int frameCount(Path image) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new IOException("không nhận ra định dạng ảnh (file hỏng?)");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return reader.getNumImages(true);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Đọc kích thước từ phần đầu file, không giải mã cả ảnh. */
    private static Dimension readSize(Path image) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new IOException("không nhận ra định dạng ảnh (file hỏng?)");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new Dimension(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage readImage(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("không đọc được ảnh (thiếu bộ giải mã?): " + file.getFileName());
        }
        return image;
    }

    private static int countFiles(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
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
