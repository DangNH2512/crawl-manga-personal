package org.example.fetch_manga.service.psd;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ghi một ảnh ra file .psd gồm hai lớp: "Background" (khoá) và "Layer 1" nhân bản
 * ở trên — đúng cấu trúc nhận được khi bấm Duplicate Layer trong Photoshop.
 *
 * <p>Định dạng theo tài liệu <em>Adobe Photoshop File Formats Specification</em>.
 * File gồm 5 phần nối tiếp:</p>
 * <ol>
 *   <li>Header 26 byte (chữ ký 8BPS, số kênh, kích thước, độ sâu, hệ màu)</li>
 *   <li>Color mode data — rỗng với ảnh RGB</li>
 *   <li>Image resources — rỗng</li>
 *   <li>Layer and mask information — phần chứa hai lớp</li>
 *   <li>Image data — ảnh hợp nhất mà phần mềm không đọc được lớp sẽ hiển thị</li>
 * </ol>
 *
 * <p>Dữ liệu điểm ảnh nén bằng RLE (PackBits) như Photoshop vẫn dùng; trang truyện
 * phần lớn là nền trắng nên nén rất tốt. Ba bản sao (nền, lớp nhân bản, ảnh hợp
 * nhất) dùng chung một lần nén để khỏi tốn công và tốn bộ nhớ.</p>
 */
public final class PsdWriter {

    private static final String BACKGROUND_NAME = "Background";
    private static final String DUPLICATE_NAME = "Layer 1";

    /** PSD thường (không phải PSB) giới hạn mỗi chiều 30000 điểm ảnh. */
    private static final int MAX_DIMENSION = 30000;

    private static final int CHANNELS = 3;
    private static final int COMPRESSION_RLE = 1;

    /** Cờ ở bản ghi lớp: bit 0 = khoá vùng trong suốt, tạo biểu tượng ổ khoá của lớp nền. */
    private static final int FLAG_TRANSPARENCY_LOCKED = 0x01;
    private static final int FLAG_NONE = 0x00;

    private PsdWriter() { }

    /** Ghi {@code source} thành file .psd hai lớp tại {@code output}. */
    public static void write(BufferedImage source, Path output) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();

        if (width <= 0 || height <= 0) {
            throw new IOException("Ảnh có kích thước không hợp lệ: " + width + "x" + height);
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("Ảnh " + width + "x" + height
                    + " vượt giới hạn " + MAX_DIMENSION + " điểm ảnh của định dạng PSD.");
        }

        byte[][] planes = toRgbPlanes(source);

        // Nén một lần, dùng lại cho cả hai lớp lẫn ảnh hợp nhất.
        ChannelData[] channels = new ChannelData[CHANNELS];
        for (int c = 0; c < CHANNELS; c++) {
            channels[c] = ChannelData.compress(planes[c], width, height);
            planes[c] = null; // giải phóng sớm, ảnh truyện khá nặng
        }

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(output), 1 << 16))) {

            writeHeader(out, width, height);

            out.writeInt(0); // Color mode data: rỗng với RGB
            out.writeInt(0); // Image resources: không cần gì

            writeLayerSection(out, channels, width, height);
            writeCompositeImage(out, channels, height);
        }
    }

    // -- Phần 1: header -------------------------------------------------------

    private static void writeHeader(DataOutputStream out, int width, int height) throws IOException {
        out.write("8BPS".getBytes(StandardCharsets.US_ASCII));
        out.writeShort(1);              // phiên bản 1 = PSD
        out.write(new byte[6]);         // 6 byte dành riêng, luôn bằng 0
        out.writeShort(CHANNELS);
        out.writeInt(height);
        out.writeInt(width);
        out.writeShort(8);              // 8 bit mỗi kênh
        out.writeShort(3);              // hệ màu RGB
    }

    // -- Phần 4: hai lớp ------------------------------------------------------

    private static void writeLayerSection(DataOutputStream out, ChannelData[] channels,
                                          int width, int height) throws IOException {

        ByteArrayOutputStream layerInfo = new ByteArrayOutputStream();
        DataOutputStream layers = new DataOutputStream(layerInfo);

        layers.writeShort(2); // hai lớp, xếp từ dưới lên

        writeLayerRecord(layers, BACKGROUND_NAME, FLAG_TRANSPARENCY_LOCKED, channels, width, height);
        writeLayerRecord(layers, DUPLICATE_NAME, FLAG_NONE, channels, width, height);

        // Dữ liệu điểm ảnh của các lớp, đúng thứ tự bản ghi ở trên.
        for (int layer = 0; layer < 2; layer++) {
            for (ChannelData channel : channels) {
                channel.writeTo(layers);
            }
        }
        layers.flush();

        byte[] layerBytes = layerInfo.toByteArray();
        int padding = layerBytes.length % 2; // độ dài phần layer info phải chẵn

        // Tổng phần 4 = 4 byte độ dài layer info + nội dung + 4 byte global mask.
        out.writeInt(4 + layerBytes.length + padding + 4);
        out.writeInt(layerBytes.length + padding);
        out.write(layerBytes);
        if (padding > 0) {
            out.write(0);
        }
        out.writeInt(0); // global layer mask info: không dùng
    }

    private static void writeLayerRecord(DataOutputStream out, String name, int flags,
                                         ChannelData[] channels, int width, int height)
            throws IOException {

        out.writeInt(0);        // top
        out.writeInt(0);        // left
        out.writeInt(height);   // bottom
        out.writeInt(width);    // right

        out.writeShort(CHANNELS);
        for (int c = 0; c < CHANNELS; c++) {
            out.writeShort(c);  // 0 = đỏ, 1 = lục, 2 = lam
            out.writeInt(channels[c].byteLength());
        }

        out.write("8BIM".getBytes(StandardCharsets.US_ASCII));
        out.write("norm".getBytes(StandardCharsets.US_ASCII)); // chế độ hoà trộn Normal
        out.write(255);         // độ mờ đục
        out.write(0);           // không cắt theo lớp dưới
        out.write(flags);
        out.write(0);           // byte đệm

        byte[] extra = buildLayerExtraData(name);
        out.writeInt(extra.length);
        out.write(extra);
    }

    /** Phần mở rộng của bản ghi lớp: mặt nạ, dải hoà trộn và tên lớp. */
    private static byte[] buildLayerExtraData(String name) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);

        out.writeInt(0); // layer mask data: không có
        out.writeInt(0); // layer blending ranges: không có

        // Tên lớp dạng Pascal string, tổng độ dài đệm cho tròn bội số của 4.
        byte[] raw = name.getBytes(StandardCharsets.US_ASCII);
        out.write(raw.length);
        out.write(raw);
        int padding = (4 - ((raw.length + 1) % 4)) % 4;
        for (int i = 0; i < padding; i++) {
            out.write(0);
        }

        out.flush();
        return buffer.toByteArray();
    }

    // -- Phần 5: ảnh hợp nhất -------------------------------------------------

    private static void writeCompositeImage(DataOutputStream out, ChannelData[] channels,
                                            int height) throws IOException {
        out.writeShort(COMPRESSION_RLE);

        // Khác với dữ liệu lớp: bảng độ dài dòng của TẤT CẢ các kênh đứng trước,
        // rồi mới đến dữ liệu nén của từng kênh.
        for (ChannelData channel : channels) {
            channel.writeRowLengths(out, height);
        }
        for (ChannelData channel : channels) {
            channel.writeRows(out);
        }
    }

    // -- Tách kênh màu --------------------------------------------------------

    /**
     * Đưa ảnh về RGB 8 bit rồi tách thành ba mặt phẳng màu.
     * Ảnh có kênh trong suốt được ghép lên nền trắng, vì PSD ở đây không dùng alpha.
     */
    private static byte[][] toRgbPlanes(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();

        BufferedImage rgb;
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            rgb = source;
        } else {
            rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, null);
            g.dispose();
        }

        byte[][] planes = new byte[CHANNELS][width * height];
        int[] row = new int[width];

        for (int y = 0; y < height; y++) {
            rgb.getRGB(0, y, width, 1, row, 0, width);
            int base = y * width;
            for (int x = 0; x < width; x++) {
                int pixel = row[x];
                planes[0][base + x] = (byte) (pixel >> 16);
                planes[1][base + x] = (byte) (pixel >> 8);
                planes[2][base + x] = (byte) pixel;
            }
        }
        return planes;
    }

    // -- Dữ liệu một kênh đã nén ---------------------------------------------

    /** Một kênh màu đã nén RLE theo từng dòng, kèm bảng độ dài dòng. */
    private static final class ChannelData {

        private final byte[][] rows;
        private final int packedLength;

        private ChannelData(byte[][] rows, int packedLength) {
            this.rows = rows;
            this.packedLength = packedLength;
        }

        static ChannelData compress(byte[] plane, int width, int height) throws IOException {
            byte[][] rows = new byte[height][];
            int total = 0;
            for (int y = 0; y < height; y++) {
                rows[y] = packBits(plane, y * width, width);
                if (rows[y].length > 0xFFFF) {
                    throw new IOException("Dòng ảnh nén ra quá dài, không ghi được vào PSD.");
                }
                total += rows[y].length;
            }
            return new ChannelData(rows, total);
        }

        /** Độ dài ghi trong bản ghi lớp: gồm cả 2 byte kiểu nén và bảng độ dài dòng. */
        int byteLength() {
            return 2 + rows.length * 2 + packedLength;
        }

        void writeTo(DataOutputStream out) throws IOException {
            out.writeShort(COMPRESSION_RLE);
            writeRowLengths(out, rows.length);
            writeRows(out);
        }

        void writeRowLengths(DataOutputStream out, int height) throws IOException {
            for (int y = 0; y < height; y++) {
                out.writeShort(rows[y].length);
            }
        }

        void writeRows(DataOutputStream out) throws IOException {
            for (byte[] row : rows) {
                out.write(row);
            }
        }
    }

    /**
     * Nén PackBits một dòng ảnh.
     *
     * <p>Quy tắc: gặp chuỗi n byte giống nhau (2..128) thì ghi {@code 257-n} rồi ghi
     * byte đó; ngược lại ghi {@code n-1} rồi n byte nguyên bản (1..128).</p>
     */
    static byte[] packBits(byte[] data, int offset, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length + length / 64 + 2);
        int end = offset + length;
        int i = offset;

        while (i < end) {
            int runLength = 1;
            while (i + runLength < end && runLength < 128 && data[i + runLength] == data[i]) {
                runLength++;
            }

            if (runLength >= 2) {
                out.write(257 - runLength);
                out.write(data[i]);
                i += runLength;
                continue;
            }

            int start = i;
            int literalLength = 0;
            while (i < end && literalLength < 128) {
                // Dừng khi thấy ba byte giống nhau: chỗ đó nén theo chuỗi lợi hơn.
                if (i + 2 < end && data[i] == data[i + 1] && data[i + 1] == data[i + 2]) {
                    break;
                }
                i++;
                literalLength++;
            }
            out.write(literalLength - 1);
            out.write(data, start, literalLength);
        }
        return out.toByteArray();
    }
}
