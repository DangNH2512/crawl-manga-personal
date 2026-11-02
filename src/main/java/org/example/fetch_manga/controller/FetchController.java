package org.example.fetch_manga.controller;

import org.example.fetch_manga.model.AppSettings;
import org.example.fetch_manga.model.FetchOutcome;
import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.FieldSpec;
import org.example.fetch_manga.model.PinnedFolder;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.FetcherRegistry;
import org.example.fetch_manga.service.ImageFormatConverter;
import org.example.fetch_manga.service.JobListener;
import org.example.fetch_manga.service.MangaFetcher;
import org.example.fetch_manga.service.SettingsStore;
import org.example.fetch_manga.service.psd.PsdBatchGenerator;
import org.example.fetch_manga.service.upscale.EngineInstaller;
import org.example.fetch_manga.service.upscale.ImageUpscaler;
import org.example.fetch_manga.service.upscale.UpscaleEngine;
import org.example.fetch_manga.service.upscale.UpscaleModel;
import org.example.fetch_manga.service.upscale.UpscaleOptions;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Bộ điều khiển: nhận dữ liệu thô từ View, kiểm tra hợp lệ, dựng
 * {@link FetchRequest}, chạy nguồn tương ứng trên luồng nền rồi báo kết quả về.
 *
 * <p>Đây là lớp duy nhất biết cả Model, Service lẫn View — View chỉ biết
 * Controller, còn Service không biết View là ai.</p>
 */
public class FetchController {

    private final FetcherRegistry registry;
    private final SettingsStore settingsStore;
    private final AppSettings settings;

    /** Một luồng nền duy nhất: mỗi lúc chỉ chạy một lượt tải, tránh log lẫn lộn. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fetch-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile Future<?> currentJob;
    /** Người dùng đã bấm Huỷ lượt đang chạy. Đặt lại mỗi khi bắt đầu một lượt mới. */
    private volatile boolean cancelRequested;

    private final EngineInstaller engineInstaller = new EngineInstaller();
    private final ImageUpscaler upscaler = new ImageUpscaler(engineInstaller, () -> cancelRequested);
    /** Xem thử không thuộc lượt chạy nào nên không dùng chung cờ Huỷ với công việc nền. */
    private final ImageUpscaler previewer = new ImageUpscaler(engineInstaller);

    public FetchController() {
        this(new FetcherRegistry(), new SettingsStore());
    }

    public FetchController(FetcherRegistry registry, SettingsStore settingsStore) {
        this.registry = registry;
        this.settingsStore = settingsStore;
        this.settings = settingsStore.load();
    }

    // ── Truy cập dữ liệu cho View ───────────────────────────────────────────

    public List<SourceDescriptor> getSources() {
        return registry.descriptors();
    }

    public AppSettings getSettings() {
        return settings;
    }

    public SettingsStore getSettingsStore() {
        return settingsStore;
    }

    /** Nguồn khớp với link vừa dán, hoặc {@code null} nếu không nhận ra trang nào. */
    public SourceDescriptor detectSource(String url) {
        return registry.detect(url).map(MangaFetcher::getDescriptor).orElse(null);
    }

    public SourceDescriptor sourceById(String id) {
        return registry.byId(id).map(MangaFetcher::getDescriptor).orElse(null);
    }

    /**
     * Tên thư mục chương đoán được từ link (vd {@code "Chap 94"}), hoặc chuỗi rỗng
     * nếu link của nguồn đó không mang số chương.
     */
    public String suggestChapterFolder(SourceDescriptor source, String url) {
        if (source == null || url == null || url.isBlank()) return "";
        return registry.byId(source.getId())
                .map(fetcher -> fetcher.suggestChapterFolder(url))
                .orElse("");
    }

    // ── Thư mục ghim ────────────────────────────────────────────────────────

    public boolean pinFolder(String path) {
        if (path == null || path.isBlank()) return false;
        boolean added = settings.pin(new PinnedFolder(path));
        if (added) saveSettings();
        return added;
    }

    public boolean unpinFolder(PinnedFolder folder) {
        boolean removed = settings.unpin(folder);
        if (removed) saveSettings();
        return removed;
    }

    /**
     * Ghép thư mục đã ghim với thư mục con để ra đường dẫn lưu cuối cùng.
     * Trả về chuỗi rỗng nếu người dùng chưa chọn gì — khi đó mỗi nguồn tự áp
     * quy ước thư mục riêng dựa trên thư mục gốc trong cấu hình.
     */
    public String resolveFolder(String pinnedPath, String subFolder) {
        String root = pinnedPath == null ? "" : pinnedPath.trim();
        String sub = subFolder == null ? "" : subFolder.trim();
        if (root.isEmpty()) return "";
        Path path = Paths.get(root);
        if (!sub.isEmpty()) path = path.resolve(sub);
        return path.toString();
    }

    public void saveSettings() {
        settingsStore.save(settings);
    }

    // ── Chạy một lượt tải ───────────────────────────────────────────────────

    /**
     * Kiểm tra dữ liệu rồi chạy nguồn trên luồng nền.
     *
     * @throws IllegalArgumentException nếu thiếu dữ liệu bắt buộc hoặc đang có
     *                                  một lượt tải khác chưa xong
     */
    public void start(SourceDescriptor source, String url, String folder,
                      Map<String, String> extras, JobListener listener) {

        if (isRunning()) {
            throw new IllegalArgumentException("Đang có một lượt tải chưa xong, hãy đợi hoặc bấm Huỷ.");
        }

        MangaFetcher fetcher = registry.byId(source.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nguồn: " + source.getId()));

        FetchRequest request = buildRequest(source, url, folder, extras);
        rememberValues(source, request);

        // Chốt lựa chọn upscale ngay lúc bấm Tải: đổi mô hình trong cửa sổ upscale khi chương
        // còn đang tải không được làm thay đổi cách lượt này ghi đè ảnh.
        UpscaleOptions upscaleOptions = settings.isUpscaleAfterFetch() ? getUpscaleOptions() : null;
        String imageFormat = settings.getImageFormat();

        JobListener sink = listener == null ? JobListener.NOOP : listener;
        cancelRequested = false;
        currentJob = worker.submit(() -> runJob(fetcher, request, upscaleOptions, imageFormat, sink));
    }

    private FetchRequest buildRequest(SourceDescriptor source, String url, String folder,
                                      Map<String, String> extras) {

        if (source.needsUrl() && (url == null || url.isBlank())) {
            throw new IllegalArgumentException("Hãy dán link chương vào ô Link.");
        }

        FetchRequest.Builder builder = FetchRequest.builder(source)
                .put(FetchRequest.URL, url)
                .put(FetchRequest.FOLDER, folder)
                .put(FetchRequest.BASE_PATH, settings.getBasePath());

        for (FieldSpec field : source.getFields()) {
            String value = extras == null ? null : extras.get(field.getKey());
            if (field.isRequired() && (value == null || value.isBlank())) {
                throw new IllegalArgumentException("Thiếu thông tin bắt buộc: " + field.getLabel());
            }
            builder.put(field.getKey(), value);
        }

        return builder.build();
    }

    /** Lưu lại cookie/token đã nhập để lần sau khỏi phải dán lại. */
    private void rememberValues(SourceDescriptor source, FetchRequest request) {
        boolean changed = false;
        for (FieldSpec field : source.getFields()) {
            if (!field.isRemembered()) continue;
            settings.remember(source.getId(), field.getKey(), request.get(field.getKey()));
            changed = true;
        }
        settings.setLastSourceId(source.getId());
        if (changed || !settings.getLastSourceId().isEmpty()) saveSettings();
    }

    private void runJob(MangaFetcher fetcher, FetchRequest request, UpscaleOptions upscaleOptions,
                        String imageFormat, JobListener listener) {
        run(request.getSource().getDisplayName(), listener, () -> {
            long startedAt = System.currentTimeMillis();
            fetcher.fetch(request);
            // Upscale TRƯỚC khi đổi định dạng: upscale đọc ảnh gốc chưa qua nén lại và ghi
            // thẳng ra định dạng đích, nên ảnh chỉ bị mã hoá một lần; bước đổi định dạng
            // sau đó thấy ảnh đã đúng đuôi thì bỏ qua.
            upscaleIfRequested(request, startedAt, upscaleOptions, imageFormat);
            // Đổi định dạng TRƯỚC khi tạo psd, để psd dựng từ ảnh đã đổi.
            convertFormatIfRequested(request);
            createPsdIfRequested(request);
        });
    }

    /** Khung chạy chung cho mọi công việc nền: báo bắt đầu, bắt lỗi, báo kết quả. */
    private void run(String jobName, JobListener listener, Job job) {
        long start = System.currentTimeMillis();
        listener.onStarted(jobName);
        try {
            job.execute();
            listener.onFinished(FetchOutcome.success(jobName, System.currentTimeMillis() - start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onFinished(FetchOutcome.cancelled(jobName, System.currentTimeMillis() - start));
        } catch (Throwable e) {
            listener.onFinished(FetchOutcome.failure(jobName, System.currentTimeMillis() - start, e));
        }
    }

    /** Một việc chạy nền có thể ném ngoại lệ. */
    @FunctionalInterface
    private interface Job {
        void execute() throws Exception;
    }

    // ── Upscale ảnh ─────────────────────────────────────────────────────────

    /** Bộ lựa chọn upscale đang lưu trong cấu hình, đã nắn về tổ hợp hợp lệ. */
    public UpscaleOptions getUpscaleOptions() {
        return UpscaleOptions.of(UpscaleModel.fromId(settings.getUpscaleModel()),
                UpscaleOptions.SizeMode.fromId(settings.getUpscaleSizeMode()),
                settings.getUpscaleScale(), settings.getUpscaleTargetPixels(), settings.getUpscaleDenoise());
    }

    public void saveUpscaleOptions(UpscaleOptions options) {
        settings.setUpscaleModel(options.getModel().getId());
        settings.setUpscaleSizeMode(options.getSizeMode().getId());
        settings.setUpscaleScale(options.getScale());
        settings.setUpscaleTargetPixels(options.getTargetPixels());
        settings.setUpscaleDenoise(options.getDenoise());
        saveSettings();
    }

    public boolean isEngineInstalled(UpscaleEngine engine) {
        return engineInstaller.isInstalled(engine);
    }

    /** Tải và cài engine. Chạy lâu — chỉ gọi từ luồng nền. */
    public void installEngine(UpscaleEngine engine, EngineInstaller.Progress progress)
            throws IOException, InterruptedException {
        engineInstaller.install(engine, progress);
    }

    /** Danh sách ảnh trong thư mục mà công cụ upscale sẽ xử lý. Có thể chậm với thư mục lớn. */
    public List<Path> listImages(Path folder, boolean recursive) throws IOException {
        return ImageUpscaler.collectImages(folder, recursive, 0);
    }

    /** Upscale một ảnh ra bộ nhớ để so sánh trước/sau. Chạy lâu — chỉ gọi từ luồng nền. */
    public BufferedImage previewUpscale(Path image, UpscaleOptions options)
            throws IOException, InterruptedException {
        return previewer.preview(image, options);
    }

    /**
     * Tải xong thì upscale các ảnh vừa tải. Chỉ lấy file ghi sau lúc bắt đầu lượt tải,
     * để ảnh cũ đã upscale từ trước trong cùng thư mục không bị phóng to thêm lần nữa.
     *
     * @param options lựa chọn đã chốt lúc bấm Tải, {@code null} nếu không bật upscale
     */
    private void upscaleIfRequested(FetchRequest request, long startedAt, UpscaleOptions options,
                                    String imageFormat) throws Exception {
        if (options == null) return;

        String folder = request.getFolder();
        if (folder.isEmpty()) {
            System.out.println("ℹ Bỏ qua upscale: chưa chọn thư mục lưu nên không biết ảnh nằm ở đâu.");
            System.out.println("   Chọn thư mục ở mục Thư mục lưu, hoặc dùng Công cụ > Upscale ảnh (AI)...");
            return;
        }
        // Lùi 2 giây vì ổ FAT32/exFAT làm tròn thời gian sửa file xuống.
        List<Path> images = ImageUpscaler.collectImages(Paths.get(folder), false, startedAt - 2000);
        upscaler.upscale(images, options, imageFormat);
    }

    /** Chạy riêng việc upscale (ghi đè ảnh gốc) cho một danh sách ảnh có sẵn. */
    public void startUpscale(List<Path> images, UpscaleOptions options, JobListener listener) {
        if (isRunning()) {
            throw new IllegalArgumentException("Đang có một việc chưa xong, hãy đợi hoặc bấm Huỷ.");
        }
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("Chưa có ảnh nào để upscale.");
        }
        List<Path> snapshot = List.copyOf(images);
        JobListener sink = listener == null ? JobListener.NOOP : listener;
        cancelRequested = false;
        currentJob = worker.submit(() ->
                run("Upscale ảnh", sink, () -> upscaler.upscale(snapshot, options, "original")));
    }

    // ── Đổi định dạng ảnh ───────────────────────────────────────────────────

    /**
     * Sau khi tải, nếu người dùng chọn png/jpg thì chuyển mọi ảnh trong thư mục về
     * định dạng đó. Cùng ràng buộc như tạo .psd: phải biết thư mục lưu.
     */
    private void convertFormatIfRequested(FetchRequest request) throws Exception {
        if (!settings.convertsImages()) return;

        String folder = request.getFolder();
        if (folder.isEmpty()) {
            System.out.println("ℹ Bỏ qua đổi định dạng: chưa chọn thư mục lưu nên không biết ảnh nằm ở đâu.");
            return;
        }
        ImageFormatConverter.convert(Paths.get(folder), settings.getImageFormat(), false);
    }

    // ── Tạo file .psd ───────────────────────────────────────────────────────

    /**
     * Sau khi tải xong, nếu người dùng bật tuỳ chọn thì tạo luôn file .psd hai lớp
     * cho từng ảnh vừa tải.
     *
     * <p>Chỉ làm được khi biết thư mục lưu. Người dùng để trống ô thư mục thì mỗi
     * nguồn tự chọn chỗ lưu theo quy ước riêng, ứng dụng không biết ảnh nằm ở đâu
     * nên báo lại thay vì đoán mò.</p>
     */
    private void createPsdIfRequested(FetchRequest request) throws Exception {
        if (!settings.isCreatePsd()) return;

        String folder = request.getFolder();
        if (folder.isEmpty()) {
            System.out.println("ℹ Bỏ qua tạo .psd: chưa chọn thư mục lưu nên không biết ảnh nằm ở đâu.");
            System.out.println("   Chọn thư mục ở mục Thư mục lưu, hoặc dùng Công cụ > Tạo .psd cho thư mục...");
            return;
        }
        PsdBatchGenerator.generate(Paths.get(folder), false, false);
    }

    /** Chạy riêng việc tạo .psd cho một thư mục có sẵn ảnh. */
    public void startPsdBatch(Path folder, boolean recursive, JobListener listener) {
        if (isRunning()) {
            throw new IllegalArgumentException("Đang có một việc chưa xong, hãy đợi hoặc bấm Huỷ.");
        }
        JobListener sink = listener == null ? JobListener.NOOP : listener;
        currentJob = worker.submit(() ->
                run("Tạo file .psd", sink, () -> PsdBatchGenerator.generate(folder, recursive, false)));
    }

    public boolean isRunning() {
        Future<?> job = currentJob;
        return job != null && !job.isDone();
    }

    /**
     * Ngắt lượt tải đang chạy. Đây là huỷ "cố gắng hết sức": các ảnh đã bắt đầu
     * tải trong pool riêng của scraper vẫn có thể chạy nốt.
     */
    public void cancel() {
        Future<?> job = currentJob;
        if (job != null) {
            cancelRequested = true;
            job.cancel(true);
        }
    }

    /** Gọi khi đóng ứng dụng. */
    public void shutdown() {
        saveSettings();
        worker.shutdownNow();
    }
}
