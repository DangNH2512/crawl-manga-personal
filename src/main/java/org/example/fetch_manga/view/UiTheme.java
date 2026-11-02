package org.example.fetch_manga.view;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Gom toàn bộ quyết định về phông chữ, màu và khung viền vào một chỗ, để mọi
 * panel trông đồng nhất và sau này muốn đổi giao diện chỉ phải sửa một file.
 */
public final class UiTheme {

    static final Color ACCENT = new Color(0x1F6FEB);
    static final Color OK = new Color(0x1A7F37);
    static final Color ERROR = new Color(0xCF222E);
    static final Color MUTED = new Color(0x57606A);

    /**
     * Dùng phông <b>logic</b> ({@code Dialog}, {@code Monospaced}) chứ không phải
     * phông vật lý như "Segoe UI" hay "Consolas".
     *
     * <p>Lý do: Java 2D chỉ ghép phông dự phòng cho phông logic. Đặt một phông vật
     * lý thì ký tự nào phông đó không có sẽ hiện ra ô vuông — tên thư mục tiếng
     * Nhật (ヨーコー) là ví dụ, vì Segoe UI không có glyph kanji/kana. Ngược lại,
     * các phông Nhật (Yu Gothic UI, Meiryo UI...) lại thiếu nguyên âm tiếng Việt
     * như "ươ" và vẽ dấu {@code \} thành {@code ¥}. Chỉ phông logic phủ được cả
     * tiếng Việt lẫn tiếng Nhật cùng lúc.</p>
     */
    private static final String UI_FAMILY = Font.DIALOG;

    /** Phông đều nét cho ô Nhật ký và dòng xem trước đường dẫn. */
    private static final String MONO_FAMILY = Font.MONOSPACED;

    /** Cỡ chữ dùng khi không lấy được cỡ gốc của Look and Feel. */
    private static final int DEFAULT_SIZE = 13;

    private UiTheme() { }

    /**
     * Dùng giao diện gốc của Windows, rồi thay họ phông của mọi thành phần bằng
     * phông logic — giữ nguyên kiểu chữ (đậm/nghiêng) và cỡ chữ vốn có.
     */
    public static void apply() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Không đổi được look and feel thì dùng mặc định, không đáng để dừng ứng dụng.
        }

        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        // Sao chép danh sách khoá trước: đọc một giá trị có thể làm bảng tự thay
        // LazyValue bằng giá trị thật, duyệt trực tiếp sẽ ném ConcurrentModificationException.
        List<Object> keys = new ArrayList<>(defaults.keySet());

        for (Object key : keys) {
            if (!(key instanceof String) || !((String) key).endsWith(".font")) continue;

            Object value = defaults.get(key);
            Font original = (value instanceof Font) ? (Font) value : null;

            UIManager.put(key, original == null
                    ? uiFont(Font.PLAIN, DEFAULT_SIZE)
                    : new Font(UI_FAMILY, original.getStyle(), original.getSize()));
        }
    }

    static Font uiFont(int style, int size) {
        return new Font(UI_FAMILY, style, size);
    }

    static Font monoFont(int size) {
        return new Font(MONO_FAMILY, Font.PLAIN, size);
    }

    /** Khung có tiêu đề cho từng khối chức năng, giúp cửa sổ dễ đọc. */
    static Border section(String title) {
        Border line = BorderFactory.createMatteBorder(1, 1, 1, 1, new Color(0xD0D7DE));
        Border titled = BorderFactory.createTitledBorder(line, "  " + title + "  ",
                TitledBorder.LEFT, TitledBorder.TOP, uiFont(Font.BOLD, DEFAULT_SIZE), ACCENT);
        return BorderFactory.createCompoundBorder(titled, BorderFactory.createEmptyBorder(6, 8, 8, 8));
    }

    static <T extends JComponent> T muted(T component) {
        component.setFont(uiFont(Font.PLAIN, 12));
        component.setForeground(MUTED);
        return component;
    }
}
