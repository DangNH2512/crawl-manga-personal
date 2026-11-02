package org.example.fetch_manga.service.upscale;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;

/**
 * Đổi kích thước ảnh bằng bộ lọc Lanczos-3, tách hai chiều.
 *
 * <p>Vì sao không dùng {@code Graphics2D.drawImage}: bộ vẽ của Java chỉ nội suy quanh
 * một điểm lấy mẫu, không gom các điểm ảnh bị bỏ qua khi thu nhỏ, nên nét mực mảnh và
 * tram chấm của manga bị răng cưa và lốm đốm. Lanczos mở rộng cửa sổ lọc theo tỉ lệ thu
 * nhỏ nên mọi điểm ảnh nguồn đều góp phần, nét thu về mượt mà vẫn sắc.</p>
 *
 * <p>Ảnh xám xử lý trên một kênh, ảnh màu trên ba kênh; alpha bị bỏ (ảnh upscale ra từ
 * engine không có alpha).</p>
 */
final class Resampler {

    private static final int LOBES = 3;

    private Resampler() { }

    static BufferedImage resize(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) return source;

        boolean gray = source.getType() == BufferedImage.TYPE_BYTE_GRAY;
        int channels = gray ? 1 : 3;
        float[][] planes = toPlanes(source, channels);

        int sw = source.getWidth();
        int sh = source.getHeight();
        float[][] horizontal = new float[channels][];
        for (int c = 0; c < channels; c++) {
            horizontal[c] = resampleRows(planes[c], sw, sh, width);
        }
        float[][] result = new float[channels][];
        for (int c = 0; c < channels; c++) {
            result[c] = resampleColumns(horizontal[c], width, sh, height);
        }
        return fromPlanes(result, width, height, gray);
    }

    // ── Lọc một chiều ───────────────────────────────────────────────────────

    /** Trọng số Lanczos cho từng điểm đích, cửa sổ lọc giãn ra khi thu nhỏ. */
    private static final class Weights {
        final int[] start;
        final float[][] taps;

        Weights(int sourceLength, int targetLength) {
            double scale = (double) targetLength / sourceLength;
            // Thu nhỏ thì lọc rộng ra theo tỉ lệ; phóng to thì giữ nguyên cửa sổ 3 thuỳ.
            double support = scale < 1 ? LOBES / scale : LOBES;
            double filterScale = scale < 1 ? scale : 1;

            start = new int[targetLength];
            taps = new float[targetLength][];
            for (int i = 0; i < targetLength; i++) {
                double center = (i + 0.5) / scale - 0.5;
                int from = (int) Math.floor(center - support);
                int to = (int) Math.ceil(center + support);
                from = Math.max(from, 0);
                to = Math.min(to, sourceLength - 1);
                start[i] = from;
                float[] w = new float[to - from + 1];
                double sum = 0;
                for (int j = from; j <= to; j++) {
                    double v = lanczos((j - center) * filterScale);
                    w[j - from] = (float) v;
                    sum += v;
                }
                for (int k = 0; k < w.length; k++) w[k] /= (float) sum;
                taps[i] = w;
            }
        }
    }

    private static double lanczos(double x) {
        if (x == 0) return 1;
        if (x <= -LOBES || x >= LOBES) return 0;
        double px = Math.PI * x;
        return LOBES * Math.sin(px) * Math.sin(px / LOBES) / (px * px);
    }

    private static float[] resampleRows(float[] src, int sw, int sh, int tw) {
        if (sw == tw) return src;
        Weights weights = new Weights(sw, tw);
        float[] out = new float[tw * sh];
        for (int y = 0; y < sh; y++) {
            int rowOffset = y * sw;
            for (int x = 0; x < tw; x++) {
                float[] w = weights.taps[x];
                int from = weights.start[x];
                float acc = 0;
                for (int k = 0; k < w.length; k++) {
                    acc += src[rowOffset + from + k] * w[k];
                }
                out[y * tw + x] = acc;
            }
        }
        return out;
    }

    private static float[] resampleColumns(float[] src, int w, int sh, int th) {
        if (sh == th) return src;
        Weights weights = new Weights(sh, th);
        float[] out = new float[w * th];
        for (int y = 0; y < th; y++) {
            float[] taps = weights.taps[y];
            int from = weights.start[y];
            int outOffset = y * w;
            for (int k = 0; k < taps.length; k++) {
                float weight = taps[k];
                int inOffset = (from + k) * w;
                for (int x = 0; x < w; x++) {
                    out[outOffset + x] += src[inOffset + x] * weight;
                }
            }
        }
        return out;
    }

    // ── Chuyển đổi ảnh ↔ mặt phẳng số thực ─────────────────────────────────

    private static float[][] toPlanes(BufferedImage image, int channels) {
        int w = image.getWidth();
        int h = image.getHeight();
        float[][] planes = new float[channels][w * h];

        if (channels == 1 && image.getRaster().getDataBuffer() instanceof DataBufferByte
                && image.getRaster().getNumBands() == 1) {
            byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < w * h; i++) planes[0][i] = data[i] & 0xFF;
            return planes;
        }

        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            int offset = y * w;
            for (int x = 0; x < w; x++) {
                int p = row[x];
                if (channels == 1) {
                    planes[0][offset + x] = luminance(p);
                } else {
                    planes[0][offset + x] = (p >> 16) & 0xFF;
                    planes[1][offset + x] = (p >> 8) & 0xFF;
                    planes[2][offset + x] = p & 0xFF;
                }
            }
        }
        return planes;
    }

    private static BufferedImage fromPlanes(float[][] planes, int w, int h, boolean gray) {
        if (gray) {
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < w * h; i++) data[i] = (byte) clamp(planes[0][i]);
            return image;
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] data = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < w * h; i++) {
            data[i] = (clamp(planes[0][i]) << 16) | (clamp(planes[1][i]) << 8) | clamp(planes[2][i]);
        }
        return image;
    }

    // ── Trắng đen ───────────────────────────────────────────────────────────

    /**
     * Ảnh có phải trắng đen không (kể cả khi lưu dưới dạng RGB). Lấy mẫu thưa cho nhanh.
     * Ngưỡng đủ rộng để bỏ qua sai số nén JPEG/WebP (vài mức quanh nét), nhưng một mảng
     * màu thật dù nhỏ (logo, khung màu) cũng đủ để coi là trang màu.
     */
    static boolean isGrayscale(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) return true;
        int w = image.getWidth();
        int h = image.getHeight();
        int step = Math.max(1, (int) Math.sqrt((double) w * h / 200_000));
        long samples = 0;
        long strong = 0;
        long chromaSum = 0;
        int[] row = new int[w];
        for (int y = 0; y < h; y += step) {
            image.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x += step) {
                int p = row[x];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int chroma = Math.max(Math.abs(r - g), Math.max(Math.abs(g - b), Math.abs(r - b)));
                samples++;
                chromaSum += chroma;
                if (chroma > 24) strong++;
            }
        }
        if (samples == 0) return true;
        return strong * 1000 < samples && chromaSum < 3 * samples;
    }

    /** Ép về xám theo độ sáng, xoá màu lẫn vào nét (mạng AI RGB hay tô nhẹ màu quanh nét mực). */
    static BufferedImage toGray(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) return image;
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        byte[] data = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            int offset = y * w;
            for (int x = 0; x < w; x++) {
                data[offset + x] = (byte) clamp(luminance(row[x]));
            }
        }
        return gray;
    }

    /** Ảnh xám → RGB với R=G=B: vẫn là file màu (Photoshop mở ở chế độ RGB) nhưng không có màu lẫn. */
    static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) return image;
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] out = ((DataBufferInt) rgb.getRaster().getDataBuffer()).getData();
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < w * h; i++) {
                int v = data[i] & 0xFF;
                out[i] = (v << 16) | (v << 8) | v;
            }
        } else {
            image.getRGB(0, 0, w, h, out, 0, w);
            for (int i = 0; i < w * h; i++) out[i] &= 0xFFFFFF;
        }
        return rgb;
    }

    static float luminance(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    private static int clamp(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}
