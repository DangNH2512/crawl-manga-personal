package org.example.fetch_manga.view;

import org.example.fetch_manga.model.SourceDescriptor;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;

/**
 * Vẽ mỗi dòng trong danh sách nguồn thành "Tên nguồn — tên miền".
 *
 * <p>Danh sách chỉ có tên thì khó chọn: nhìn "Comix.to" hay "NicoManga" không đoán
 * ngay được nó ứng với link nào. Kèm tên miền vào thì đối chiếu với link đang cầm
 * là ra luôn.</p>
 */
class SourceListRenderer extends DefaultListCellRenderer {

    private static final Color HINT = new Color(0x6E7781);
    private static final Color HINT_SELECTED = new Color(0xD6E2F0);

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                  boolean isSelected, boolean cellHasFocus) {

        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        setFont(UiTheme.uiFont(Font.PLAIN, 13));

        if (!(value instanceof SourceDescriptor)) {
            return this;
        }

        SourceDescriptor source = (SourceDescriptor) value;
        String hint = describeHint(source);

        if (hint.isEmpty()) {
            setText(source.getDisplayName());
            return this;
        }

        // Dùng HTML để tên nguồn đậm còn phần gợi ý mờ đi, cho dễ lướt mắt.
        String hintColor = toHex(isSelected ? HINT_SELECTED : HINT);
        setText("<html><b>" + escape(source.getDisplayName()) + "</b>"
                + "&nbsp;&nbsp;<font color='" + hintColor + "'>" + escape(hint) + "</font></html>");
        return this;
    }

    /** Tên miền để đối chiếu với link, hoặc ghi chú với nguồn không dùng link. */
    private String describeHint(SourceDescriptor source) {
        if (!source.getHosts().isEmpty()) {
            return source.getHosts().get(0);
        }
        return source.needsUrl() ? "" : "không dùng link";
    }

    private String toHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
