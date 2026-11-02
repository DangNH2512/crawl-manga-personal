package org.example.fetch_manga.service.upscale;

import java.util.Locale;

/**
 * Các chương trình upscale AI mà ứng dụng biết cách gọi.
 *
 * <p>Cả ba đều là bản dựng <b>ncnn + Vulkan</b> chính chủ trên GitHub: một file exe
 * nhỏ kèm thư mục mô hình, chạy thẳng trên GPU (NVIDIA, AMD lẫn Intel) mà không cần
 * cài Python, CUDA hay driver gì thêm. Đây cũng là bộ lõi mà Upscayl và nhiều trang
 * upscale dùng.</p>
 *
 * <p>Mỗi bản ghim đúng một phiên bản kèm mã SHA-256 của file zip, để file tải về
 * mà bị hỏng hay bị tráo thì ứng dụng từ chối cài chứ không chạy một exe lạ.</p>
 */
public enum UpscaleEngine {

    REALCUGAN("Real-CUGAN", "realcugan-ncnn-vulkan.exe",
            "https://github.com/nihui/realcugan-ncnn-vulkan/releases/download/20220728/"
                    + "realcugan-ncnn-vulkan-20220728-windows.zip",
            "c6e08d46c11704b1e3a1ada9ddd591cb5005f52f132136c8633ba25def400e01",
            45_977_449L),

    REALESRGAN("Real-ESRGAN", "realesrgan-ncnn-vulkan.exe",
            "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/"
                    + "realesrgan-ncnn-vulkan-20220424-windows.zip",
            "abc02804e17982a3be33675e4d471e91ea374e65b70167abc09e31acb412802d",
            45_474_481L),

    WAIFU2X("waifu2x", "waifu2x-ncnn-vulkan.exe",
            "https://github.com/nihui/waifu2x-ncnn-vulkan/releases/download/20250915/"
                    + "waifu2x-ncnn-vulkan-20250915-windows.zip",
            "7425be94b94e4c8f37a1e433ac0e0100c43790e2c37418f4b65d8235adfbdc87",
            35_497_352L);

    private final String displayName;
    private final String exeName;
    private final String downloadUrl;
    private final String sha256;
    private final long zipSize;

    UpscaleEngine(String displayName, String exeName, String downloadUrl, String sha256, long zipSize) {
        this.displayName = displayName;
        this.exeName = exeName;
        this.downloadUrl = downloadUrl;
        this.sha256 = sha256;
        this.zipSize = zipSize;
    }

    public String getDisplayName() { return displayName; }
    public String getExeName()     { return exeName; }
    public String getDownloadUrl() { return downloadUrl; }
    public String getSha256()      { return sha256; }
    public long getZipSize()       { return zipSize; }

    /** Tên thư mục cài đặt, vd {@code realcugan}. */
    public String folderName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Dung lượng tải về, làm tròn theo MB để hiện cho người dùng. */
    public long sizeMb() {
        return Math.round(zipSize / (1024.0 * 1024.0));
    }
}
