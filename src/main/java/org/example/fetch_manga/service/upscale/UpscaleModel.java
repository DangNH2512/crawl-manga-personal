package org.example.fetch_manga.service.upscale;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Các mô hình AI chọn được trên giao diện, mỗi mô hình thuộc một {@link UpscaleEngine}.
 *
 * <p>Mức phóng to và mức khử nhiễu hợp lệ khác nhau theo từng mô hình — chúng tương
 * ứng đúng với các file mô hình có trong bản phát hành. Chọn tổ hợp không có file
 * thì engine thoát với lỗi khó hiểu, nên giao diện chỉ được đưa ra các giá trị ở đây.</p>
 */
public enum UpscaleModel {

    WAIFU2X_CUNET("waifu2x-cunet", UpscaleEngine.WAIFU2X, "models-cunet", null,
            "waifu2x CUnet - manga (khuyên dùng, giữ tram)",
            "GIỮ ĐƯỢC hiệu ứng tram/screentone (lưới chấm) của manga — phóng to mà chấm "
                    + "không bị làm nhoè thành mảng xám. Hợp nhất cho trang đen trắng có tram.",
            2, 4),

    REALCUGAN_SE("realcugan-se", UpscaleEngine.REALCUGAN, "models-se", null,
            "Real-CUGAN - làm mượt (XOÁ tram)",
            "Nét mực đậm và mượt, NHƯNG làm nhoè mất tram/screentone thành mảng xám phẳng "
                    + "(kể cả mức khử nhiễu thấp nhất). Chỉ hợp tranh màu hoặc trang không có tram.",
            2, 3, 4),

    REALCUGAN_PRO("realcugan-pro", UpscaleEngine.REALCUGAN, "models-pro", null,
            "Real-CUGAN Pro - làm mượt (XOÁ tram)",
            "Như Real-CUGAN thường, cũng làm nhoè tram. Chỉ có 2x và 3x.",
            2, 3),

    REALESRGAN_ANIME_VIDEO("realesrgan-animevideo", UpscaleEngine.REALESRGAN, "models", "realesr-animevideov3",
            "Real-ESRGAN AnimeVideo v3 - nhanh",
            "Nhẹ và nhanh, làm nét mạnh tay hơn. Hợp tranh màu kiểu anime, không hợp tram.",
            2, 3, 4),

    REALESRGAN_ANIME("realesrgan-anime", UpscaleEngine.REALESRGAN, "models", "realesrgan-x4plus-anime",
            "Real-ESRGAN x4plus anime - làm mịn mạnh",
            "Tranh màu trông rất mượt, nhưng dễ xoá mất tram và nét mảnh của manga. Chỉ có 4x.",
            4),

    REALESRGAN_PHOTO("realesrgan-photo", UpscaleEngine.REALESRGAN, "models", "realesrgan-x4plus",
            "Real-ESRGAN x4plus - ảnh chụp",
            "Dành cho ảnh chụp đời thực, không hợp với tranh vẽ. Chỉ có 4x.",
            4);

    /** Mức khử nhiễu dùng khi mô hình không có tuỳ chọn này. */
    public static final int NO_DENOISE = -1;

    private final String id;
    private final UpscaleEngine engine;
    private final String modelFolder;
    /** Tên mô hình truyền qua {@code -n} — chỉ Real-ESRGAN dùng, engine khác dùng {@code -n} cho khử nhiễu. */
    private final String modelName;
    private final String label;
    private final String note;
    private final int[] scales;

    UpscaleModel(String id, UpscaleEngine engine, String modelFolder, String modelName,
                 String label, String note, int... scales) {
        this.id = id;
        this.engine = engine;
        this.modelFolder = modelFolder;
        this.modelName = modelName;
        this.label = label;
        this.note = note;
        this.scales = scales;
    }

    public String getId()             { return id; }
    public UpscaleEngine getEngine()  { return engine; }
    public String getLabel()          { return label; }
    public String getNote()           { return note; }

    /** Phần tên trước dấu gạch, vd {@code "Real-CUGAN Pro"}. */
    public String getShortName() {
        int dash = label.indexOf(" - ");
        return dash > 0 ? label.substring(0, dash) : label;
    }

    public int[] getScales() {
        return scales.clone();
    }

    public boolean supportsScale(int scale) {
        for (int s : scales) {
            if (s == scale) return true;
        }
        return false;
    }

    /**
     * Các mức khử nhiễu có file mô hình đi kèm, ứng với một mức phóng to.
     * Mảng rỗng nghĩa là mô hình không cho chỉnh khử nhiễu.
     */
    public int[] denoiseLevels(int scale) {
        switch (this) {
            case REALCUGAN_SE:
                return scale == 2 ? new int[]{-1, 0, 1, 2, 3} : new int[]{-1, 0, 3};
            case REALCUGAN_PRO:
                return new int[]{-1, 0, 3};
            case WAIFU2X_CUNET:
                return new int[]{-1, 0, 1, 2, 3};
            default:
                return new int[0];
        }
    }

    /** Các mức khử nhiễu có file mô hình ở mọi mức phóng to — dùng khi mỗi ảnh chạy một tỉ lệ khác nhau. */
    public int[] denoiseLevelsForAllScales() {
        int[] common = denoiseLevels(scales[0]);
        for (int s : scales) {
            int[] levels = denoiseLevels(s);
            common = java.util.Arrays.stream(common)
                    .filter(l -> java.util.Arrays.stream(levels).anyMatch(x -> x == l))
                    .toArray();
        }
        return common;
    }

    public boolean supportsDenoise(int scale, int level) {
        for (int l : denoiseLevels(scale)) {
            if (l == level) return true;
        }
        return false;
    }

    /** Nhãn dễ hiểu cho một mức khử nhiễu. */
    public String denoiseLabel(int level) {
        switch (level) {
            case -1: return "Không khử nhiễu";
            case 0:  return engine == UpscaleEngine.REALCUGAN ? "Bảo toàn nét (conservative)" : "Rất nhẹ";
            case 1:  return "Nhẹ";
            case 2:  return "Vừa";
            case 3:  return "Mạnh";
            default: return "Mức " + level;
        }
    }

    /**
     * Dòng lệnh chạy engine ở chế độ thư mục: xử lý mọi ảnh trong {@code input},
     * ghi ra {@code output} dưới dạng png cùng tên.
     */
    List<String> buildCommand(Path exe, Path input, Path output, int scale, int denoise) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.add("-i");
        cmd.add(input.toString());
        cmd.add("-o");
        cmd.add(output.toString());
        cmd.add("-s");
        cmd.add(Integer.toString(scale));
        cmd.add("-m");
        // Đường dẫn tuyệt đối: đường dẫn tương đối bị engine ghép với thư mục chứa exe.
        cmd.add(exe.getParent().resolve(modelFolder).toString());
        cmd.add("-n");
        cmd.add(modelName != null ? modelName : Integer.toString(denoise));
        cmd.add("-f");
        cmd.add("png");
        return cmd;
    }

    /** Tìm theo id đã lưu trong cấu hình; id lạ thì trả về mô hình mặc định. */
    public static UpscaleModel fromId(String id) {
        for (UpscaleModel model : values()) {
            if (model.id.equals(id)) return model;
        }
        return WAIFU2X_CUNET;
    }

    @Override
    public String toString() {
        return label;
    }
}
