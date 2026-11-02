import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Sinh file .ico cho bản đóng gói exe.
 *
 * <p>Chạy một lần bằng: {@code java tools/MakeIcon.java tools/app.ico}. Tách khỏi
 * mã nguồn chính vì đây là công cụ dựng, không phải chức năng của ứng dụng.</p>
 *
 * <p>Định dạng ICO từ Windows Vista trở đi cho phép nhúng thẳng dữ liệu PNG, nên
 * chỉ cần ghi phần đầu ICONDIR + ICONDIRENTRY rồi nối khối PNG vào sau.</p>
 */
public final class MakeIcon {

    private static final int SIZE = 256;

    private static final Color BACKGROUND = new Color(0x1F6FEB);
    private static final Color BACKGROUND_DARK = new Color(0x144E9C);
    private static final Color PAPER = Color.WHITE;
    private static final Color INK = new Color(0x0B3D91);

    public static void main(String[] args) throws IOException {
        Path output = Paths.get(args.length > 0 ? args[0] : "tools/app.ico");
        Files.createDirectories(output.toAbsolutePath().getParent());

        byte[] png = toPng(render());
        writeIco(output, png);

        System.out.println("Da tao icon: " + output.toAbsolutePath() + " (" + png.length + " bytes PNG)");
    }

    /** Nền bo tròn màu xanh, một trang truyện trắng và mũi tên tải xuống. */
    private static BufferedImage render() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setPaint(new java.awt.GradientPaint(0, 0, BACKGROUND, SIZE, SIZE, BACKGROUND_DARK));
        g.fill(new RoundRectangle2D.Float(0, 0, SIZE, SIZE, 56, 56));

        // Trang giấy
        g.setColor(PAPER);
        g.fill(new RoundRectangle2D.Float(56, 40, 144, 130, 14, 14));

        // Vài dòng chữ tượng trưng
        g.setColor(new Color(0xC7D6EA));
        for (int i = 0; i < 4; i++) {
            g.fillRoundRect(76, 66 + i * 22, i == 3 ? 70 : 104, 10, 5, 5);
        }

        // Mũi tên tải xuống
        g.setColor(INK);
        g.setStroke(new BasicStroke(18, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(128, 150, 128, 206);

        GeneralPath arrow = new GeneralPath();
        arrow.moveTo(92, 190);
        arrow.lineTo(128, 226);
        arrow.lineTo(164, 190);
        arrow.closePath();
        g.fill(arrow);

        // Khay hứng
        g.setStroke(new BasicStroke(16, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(66, 224, 190, 224);

        g.dispose();
        return image;
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    private static void writeIco(Path output, byte[] png) throws IOException {
        try (OutputStream out = Files.newOutputStream(output)) {
            // ICONDIR: reserved = 0, type = 1 (icon), số ảnh = 1
            writeLe16(out, 0);
            writeLe16(out, 1);
            writeLe16(out, 1);

            // ICONDIRENTRY: 0 ở ô kích thước nghĩa là 256 px
            out.write(0);            // width
            out.write(0);            // height
            out.write(0);            // số màu bảng màu
            out.write(0);            // reserved
            writeLe16(out, 1);       // color planes
            writeLe16(out, 32);      // bit trên mỗi điểm ảnh
            writeLe32(out, png.length);
            writeLe32(out, 22);      // vị trí bắt đầu dữ liệu ảnh

            out.write(png);
        }
    }

    private static void writeLe16(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeLe32(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
