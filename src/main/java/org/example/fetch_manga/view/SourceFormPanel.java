package org.example.fetch_manga.view;

import org.example.fetch_manga.model.AppSettings;
import org.example.fetch_manga.model.FieldSpec;
import org.example.fetch_manga.model.SourceDescriptor;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Form các ô nhập liệu riêng của từng nguồn, được dựng lại mỗi khi người dùng
 * đổi nguồn.
 *
 * <p>Panel này chỉ đọc {@link SourceDescriptor} rồi sinh component tương ứng, nên
 * thêm một nguồn mới với ô nhập mới không cần sửa lớp View nào cả.</p>
 */
class SourceFormPanel extends JPanel {

    private final AppSettings settings;

    private final JLabel noteLabel = new JLabel();
    private final JPanel fieldsPanel = new JPanel(new GridBagLayout());
    private final Map<String, JComponent> editors = new LinkedHashMap<>();

    private SourceDescriptor current;

    SourceFormPanel(AppSettings settings) {
        super(new BorderLayout(0, 6));
        this.settings = settings;
        setBorder(UiTheme.section("Thông tin của nguồn"));

        noteLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 2));
        add(noteLabel, BorderLayout.NORTH);
        add(fieldsPanel, BorderLayout.CENTER);
    }

    /** Dựng lại toàn bộ form theo nguồn được chọn. */
    void showSource(SourceDescriptor source) {
        this.current = source;
        editors.clear();
        fieldsPanel.removeAll();

        noteLabel.setText(buildNoteHtml(source));
        noteLabel.setFont(UiTheme.uiFont(Font.PLAIN, 12));
        noteLabel.setForeground(UiTheme.MUTED);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 0, 3, 6);
        c.anchor = GridBagConstraints.NORTHWEST;

        int row = 0;
        for (FieldSpec field : source.getFields()) {
            JComponent editor = createEditor(field);
            editors.put(field.getKey(), editor);

            c.gridx = 0;
            c.gridy = row;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            fieldsPanel.add(new JLabel(labelText(field)), c);

            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            fieldsPanel.add(wrap(field, editor), c);
            row++;
        }

        if (source.getFields().isEmpty()) {
            c.gridx = 0;
            c.gridy = 0;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            fieldsPanel.add(UiTheme.muted(new JLabel("Nguồn này không cần nhập thêm gì.")), c);
        }

        revalidate();
        repaint();
    }

    private String labelText(FieldSpec field) {
        return field.isRequired() ? field.getLabel() + " *:" : field.getLabel() + ":";
    }

    private String buildNoteHtml(SourceDescriptor source) {
        StringBuilder sb = new StringBuilder("<html>");
        if (!source.getNote().isEmpty()) {
            sb.append(escape(source.getNote()).replace("\n", "<br>"));
        }
        if (!source.getExample().isEmpty()) {
            if (sb.length() > 6) sb.append("<br>");
            sb.append("Ví dụ: <b>").append(escape(source.getExample())).append("</b>");
        }
        if (sb.length() == 6) {
            sb.append("Chỉ cần dán link và bấm Tải.");
        }
        return sb.append("</html>").toString();
    }

    private String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JComponent createEditor(FieldSpec field) {
        String initial = settings.remembered(current.getId(), field.getKey(), field.getDefaultValue());

        switch (field.getType()) {
            case TEXTAREA: {
                JTextArea area = new JTextArea(field.isRemembered() ? initial : "", 3, 40);
                area.setLineWrap(true);
                area.setWrapStyleWord(false);
                area.setFont(UiTheme.monoFont(12));
                area.setToolTipText(field.getHint());
                return area;
            }
            case CHOICE: {
                JComboBox<String> combo = new JComboBox<>(field.getOptions().toArray(new String[0]));
                combo.setSelectedItem(initial.isEmpty() ? field.getDefaultValue() : initial);
                combo.setToolTipText(field.getHint());
                return combo;
            }
            case TEXT:
            default: {
                JTextField textField = new JTextField(field.isRemembered() ? initial : "");
                textField.setToolTipText(field.getHint());
                return textField;
            }
        }
    }

    /** Bọc component để có thanh cuộn cho ô nhiều dòng và dòng gợi ý ở dưới. */
    private JComponent wrap(FieldSpec field, JComponent editor) {
        JComponent body = editor;
        if (editor instanceof JTextArea) {
            JScrollPane scroll = new JScrollPane(editor);
            scroll.setPreferredSize(new Dimension(420, 62));
            body = scroll;
        }

        if (field.getHint().isEmpty()) {
            return body;
        }

        JPanel holder = new JPanel(new BorderLayout(0, 2));
        holder.setOpaque(false);
        holder.add(body, BorderLayout.CENTER);
        holder.add(UiTheme.muted(new JLabel(field.getHint())), BorderLayout.SOUTH);
        return holder;
    }

    /** Giá trị người dùng đang nhập, khoá là {@code FieldSpec.getKey()}. */
    Map<String, String> getValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JComponent> entry : editors.entrySet()) {
            values.put(entry.getKey(), readEditor(entry.getValue()));
        }
        return values;
    }

    private String readEditor(JComponent editor) {
        if (editor instanceof JTextComponent) {
            return ((JTextComponent) editor).getText().trim();
        }
        if (editor instanceof JComboBox) {
            Object selected = ((JComboBox<?>) editor).getSelectedItem();
            return selected == null ? "" : selected.toString();
        }
        return "";
    }

    /** Xoá nội dung các ô không được ghi nhớ, giữ lại cookie/token đã lưu. */
    void clearTransientFields() {
        if (current == null) return;
        for (FieldSpec field : current.getFields()) {
            if (field.isRemembered()) continue;
            JComponent editor = editors.get(field.getKey());
            if (editor instanceof JTextComponent) {
                ((JTextComponent) editor).setText("");
            }
        }
    }
}
