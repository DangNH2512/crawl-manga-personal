package org.example.fetch_manga.model;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Toàn bộ cấu hình người dùng: thư mục gốc, danh sách thư mục đã ghim, thư mục
 * con dùng lần gần nhất và các giá trị được ghi nhớ (cookie, token...).
 *
 * <p>Đây là Model — nó không tự đọc/ghi file. Việc nạp và lưu do
 * {@code org.example.fetch_manga.service.SettingsStore} đảm nhiệm, đúng nguyên
 * tắc tách dữ liệu khỏi cơ chế lưu trữ.</p>
 */
public final class AppSettings {

    /**
     * Thư mục gốc mặc định, chỉ dùng cho lần chạy đầu tiên khi chưa có file cấu
     * hình. Viết bằng dấu gạch chéo xuôi rồi để {@code Paths} chuẩn hoá, nên trên
     * Windows vẫn ra đúng dạng {@code E:\Manga Stuff\Page}.
     */
    public static final String DEFAULT_BASE_PATH = Paths.get("E:/Manga Stuff/Page").toString();

    private String basePath = DEFAULT_BASE_PATH;
    private String lastSourceId = "";
    private String lastSubFolder = "";
    private String lastPinnedPath = "";

    /** Tải xong thì tạo luôn file .psd hai lớp cho từng ảnh. */
    private boolean createPsd;

    /**
     * Định dạng ảnh đích: {@code "original"} giữ nguyên như tải về, hoặc
     * {@code "png"} / {@code "jpg"} — hai loại Photoshop mở được (WebP thì không).
     */
    private String imageFormat = "original";

    /** Tải xong thì upscale luôn các ảnh vừa tải bằng mô hình đã chọn bên dưới. */
    private boolean upscaleAfterFetch;

    /** Mô hình, mức phóng to và mức khử nhiễu dùng chung cho công cụ upscale lẫn bước tự upscale. */
    private String upscaleModel = "realcugan-se";
    private int upscaleScale = 2;
    /** 0 = "bảo toàn nét" (conservative) của Real-CUGAN: dọn vết nén nhưng giữ tram. */
    private int upscaleDenoise = 0;
    /** {@code "scale"} theo tỉ lệ, {@code "width"} / {@code "height"} theo số pixel. */
    private String upscaleSizeMode = "scale";
    private int upscaleTargetPixels = 960;

    private final List<PinnedFolder> pinnedFolders = new ArrayList<>();

    /** Khoá dạng {@code <sourceId>.<fieldKey>} → giá trị đã nhập lần trước. */
    private final Map<String, String> rememberedValues = new LinkedHashMap<>();

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        if (basePath != null && !basePath.isBlank()) {
            this.basePath = basePath.trim();
        }
    }

    public String getLastSourceId() {
        return lastSourceId;
    }

    public void setLastSourceId(String id) {
        this.lastSourceId = id == null ? "" : id;
    }

    public String getLastSubFolder() {
        return lastSubFolder;
    }

    public void setLastSubFolder(String value) {
        this.lastSubFolder = value == null ? "" : value;
    }

    public String getLastPinnedPath() {
        return lastPinnedPath;
    }

    public void setLastPinnedPath(String value) {
        this.lastPinnedPath = value == null ? "" : value;
    }

    public boolean isCreatePsd() {
        return createPsd;
    }

    public void setCreatePsd(boolean createPsd) {
        this.createPsd = createPsd;
    }

    public String getImageFormat() {
        return imageFormat;
    }

    public void setImageFormat(String format) {
        if (format == null) return;
        String f = format.trim().toLowerCase();
        if (f.equals("jpeg")) f = "jpg";
        if (f.equals("original") || f.equals("png") || f.equals("jpg")) {
            this.imageFormat = f;
        }
    }

    /** Có cần chuyển định dạng sau khi tải không (khác {@code "original"}). */
    public boolean convertsImages() {
        return !imageFormat.equals("original");
    }

    public boolean isUpscaleAfterFetch() {
        return upscaleAfterFetch;
    }

    public void setUpscaleAfterFetch(boolean upscaleAfterFetch) {
        this.upscaleAfterFetch = upscaleAfterFetch;
    }

    public String getUpscaleModel() {
        return upscaleModel;
    }

    public void setUpscaleModel(String id) {
        if (id != null && !id.isBlank()) this.upscaleModel = id.trim();
    }

    public int getUpscaleScale() {
        return upscaleScale;
    }

    public void setUpscaleScale(int scale) {
        this.upscaleScale = scale;
    }

    public int getUpscaleDenoise() {
        return upscaleDenoise;
    }

    public void setUpscaleDenoise(int level) {
        this.upscaleDenoise = level;
    }

    public String getUpscaleSizeMode() {
        return upscaleSizeMode;
    }

    public void setUpscaleSizeMode(String mode) {
        if (mode != null && !mode.isBlank()) this.upscaleSizeMode = mode.trim();
    }

    public int getUpscaleTargetPixels() {
        return upscaleTargetPixels;
    }

    public void setUpscaleTargetPixels(int pixels) {
        this.upscaleTargetPixels = pixels;
    }

    public List<PinnedFolder> getPinnedFolders() {
        return pinnedFolders;
    }

    /** Ghim thêm một thư mục; bỏ qua nếu đã có (so sánh không phân biệt hoa thường). */
    public boolean pin(PinnedFolder folder) {
        if (folder == null || pinnedFolders.contains(folder)) {
            return false;
        }
        pinnedFolders.add(folder);
        return true;
    }

    public boolean unpin(PinnedFolder folder) {
        return pinnedFolders.remove(folder);
    }

    public Map<String, String> getRememberedValues() {
        return rememberedValues;
    }

    public String remembered(String sourceId, String fieldKey, String fallback) {
        String value = rememberedValues.get(sourceId + "." + fieldKey);
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    public void remember(String sourceId, String fieldKey, String value) {
        String key = sourceId + "." + fieldKey;
        if (value == null || value.isBlank()) {
            rememberedValues.remove(key);
        } else {
            rememberedValues.put(key, value);
        }
    }
}
