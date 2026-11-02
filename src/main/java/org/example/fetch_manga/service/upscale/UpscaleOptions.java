package org.example.fetch_manga.service.upscale;

import java.awt.Dimension;
import java.util.Locale;

/**
 * Một bộ lựa chọn upscale đã hợp lệ: mô hình, cách chọn kích thước, mức khử nhiễu.
 *
 * <p>Có hai cách chọn kích thước:</p>
 * <ul>
 *   <li><b>Theo tỉ lệ</b> — x2, x3, x4 đúng như mô hình hỗ trợ.</li>
 *   <li><b>Theo số pixel</b> — vd rộng 960px (tối đa {@value #MAX_TARGET_PIXELS}px). Mô hình AI
 *       chỉ phóng được đúng các tỉ lệ cố định, nên mỗi ảnh chạy AI ở tỉ lệ nhỏ nhất vượt
 *       mục tiêu rồi thu lại cho khớp từng pixel. Ảnh đã đủ lớn thì giữ nguyên.</li>
 * </ul>
 *
 * <p>Giá trị đọc từ file cấu hình có thể đã cũ hoặc sai (đổi mô hình mà mức phóng to cũ
 * không còn hỗ trợ), nên {@link #of} tự nắn về tổ hợp gần nhất có file mô hình thay vì để
 * engine báo lỗi lúc chạy.</p>
 */
public final class UpscaleOptions {

    /** Cách chọn kích thước đầu ra. */
    public enum SizeMode {
        SCALE("Theo tỉ lệ (x2, x3...)"),
        WIDTH("Theo chiều rộng (px)"),
        HEIGHT("Theo chiều cao (px)");

        private final String label;

        SizeMode(String label) {
            this.label = label;
        }

        public String getId() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static SizeMode fromId(String id) {
            for (SizeMode mode : values()) {
                if (mode.getId().equalsIgnoreCase(id == null ? "" : id.trim())) return mode;
            }
            return SCALE;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final int MIN_TARGET_PIXELS = 100;
    public static final int MAX_TARGET_PIXELS = 2000;
    public static final int DEFAULT_TARGET_PIXELS = 960;

    private final UpscaleModel model;
    private final SizeMode sizeMode;
    private final int scale;
    private final int targetPixels;
    private final int denoise;

    private UpscaleOptions(UpscaleModel model, SizeMode sizeMode, int scale, int targetPixels, int denoise) {
        this.model = model;
        this.sizeMode = sizeMode;
        this.scale = scale;
        this.targetPixels = targetPixels;
        this.denoise = denoise;
    }

    /** Mặc định Real-CUGAN 2x, khử nhiễu "bảo toàn nét": dọn vết nén của ảnh web mà không xoá tram. */
    public static UpscaleOptions defaults() {
        return of(UpscaleModel.WAIFU2X_CUNET, 2, 0);
    }

    /** Chế độ theo tỉ lệ. */
    public static UpscaleOptions of(UpscaleModel model, int scale, int denoise) {
        return of(model, SizeMode.SCALE, scale, DEFAULT_TARGET_PIXELS, denoise);
    }

    public static UpscaleOptions of(UpscaleModel model, SizeMode sizeMode, int scale,
                                    int targetPixels, int denoise) {
        UpscaleModel m = model == null ? UpscaleModel.WAIFU2X_CUNET : model;
        SizeMode mode = sizeMode == null ? SizeMode.SCALE : sizeMode;

        int s = scale;
        if (!m.supportsScale(s)) {
            s = m.supportsScale(2) ? 2 : m.getScales()[0];
        }

        int px = Math.max(MIN_TARGET_PIXELS, Math.min(MAX_TARGET_PIXELS, targetPixels));

        // Theo số pixel thì mỗi ảnh có thể chạy AI ở một tỉ lệ khác nhau, nên mức khử nhiễu
        // phải có file mô hình ở MỌI tỉ lệ.
        int[] levels = mode == SizeMode.SCALE ? m.denoiseLevels(s) : m.denoiseLevelsForAllScales();
        int d = denoise;
        if (levels.length == 0) {
            d = UpscaleModel.NO_DENOISE;
        } else if (!contains(levels, d)) {
            d = levels[0];
        }
        return new UpscaleOptions(m, mode, s, px, d);
    }

    public UpscaleModel getModel()  { return model; }
    public SizeMode getSizeMode()   { return sizeMode; }
    public int getScale()           { return scale; }
    public int getTargetPixels()    { return targetPixels; }
    public int getDenoise()         { return denoise; }

    public boolean isScaleMode() {
        return sizeMode == SizeMode.SCALE;
    }

    /** Các mức khử nhiễu chọn được với chế độ kích thước hiện tại. */
    public int[] denoiseLevels() {
        return isScaleMode() ? model.denoiseLevels(scale) : model.denoiseLevelsForAllScales();
    }

    /** Ở chế độ theo pixel: ảnh w×h đã đạt mục tiêu thì giữ nguyên, không upscale. */
    public boolean isLargeEnough(int width, int height) {
        return !isScaleMode() && measured(width, height) >= targetPixels;
    }

    /** Cạnh dài tối đa của ảnh trung gian do AI xuất ra, để không ngốn quá nhiều bộ nhớ. */
    public static final int MAX_ENGINE_LONG_SIDE = 6000;

    /**
     * Tỉ lệ AI cần chạy cho ảnh w×h ở chế độ theo pixel.
     *
     * <p>Ưu tiên phóng lên <b>gấp đôi</b> mục tiêu rồi thu về (siêu lấy mẫu): nét mực sau khi
     * thu bằng Lanczos mượt và sắc hơn hẳn so với phóng vừa đủ. Chỉ khi ảnh trung gian quá
     * lớn mới lùi về tỉ lệ nhỏ nhất vượt mục tiêu; không tỉ lệ nào đủ thì dùng tỉ lệ lớn nhất,
     * phần còn thiếu phóng tiếp bằng Lanczos.</p>
     */
    public int engineScale(int width, int height) {
        if (isScaleMode()) return scale;
        int current = Math.max(1, measured(width, height));
        int longSide = Math.max(width, height);
        int[] scales = model.getScales();
        for (int s : scales) {
            if ((long) s * current >= 2L * targetPixels && (long) s * longSide <= MAX_ENGINE_LONG_SIDE) return s;
        }
        for (int s : scales) {
            if ((long) s * current >= targetPixels) return s;
        }
        return scales[scales.length - 1];
    }

    /** Kích thước cuối cùng ở chế độ theo pixel (giữ tỉ lệ khung), hoặc {@code null} ở chế độ theo tỉ lệ. */
    public Dimension targetSize(int width, int height) {
        if (isScaleMode()) return null;
        if (sizeMode == SizeMode.WIDTH) {
            return new Dimension(targetPixels, Math.max(1, (int) Math.round(height * (double) targetPixels / width)));
        }
        return new Dimension(Math.max(1, (int) Math.round(width * (double) targetPixels / height)), targetPixels);
    }

    /** Mô tả ngắn cho nhật ký và tooltip, vd {@code "Real-CUGAN 2x, không khử nhiễu"}. */
    public String describe() {
        String text = model.getShortName();
        switch (sizeMode) {
            case WIDTH:  text += " → rộng " + targetPixels + "px"; break;
            case HEIGHT: text += " → cao " + targetPixels + "px"; break;
            default:     text += " " + scale + "x";
        }
        if (denoiseLevels().length > 0) {
            text += ", " + model.denoiseLabel(denoise).toLowerCase();
        }
        return text;
    }

    private int measured(int width, int height) {
        return sizeMode == SizeMode.HEIGHT ? height : width;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return describe();
    }
}
