package org.example.fetch_manga.view;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Ô Nhật ký: hiển thị mọi dòng tiến độ mà tầng scraper in ra.
 *
 * <p>Các luồng tải gọi {@link #append(String)} rất dồn dập, nên dòng log được xếp
 * vào hàng đợi và cứ 120 ms mới đổ một lượt lên giao diện — vừa an toàn luồng,
 * vừa không làm treo cửa sổ.</p>
 */
class LogPanel extends JPanel {

    /** Giữ tối đa ngần này dòng để không phình bộ nhớ khi tải nhiều chương liên tiếp. */
    private static final int MAX_LINES = 4000;

    private final JTextArea area = new JTextArea();
    private final JToggleButton autoScroll = new JToggleButton("Tự cuộn", true);
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();

    LogPanel() {
        super(new BorderLayout(0, 6));
        setBorder(UiTheme.section("Nhật ký"));

        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(UiTheme.monoFont(12));

        JScrollPane scroll = new JScrollPane(area,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);

        JButton clear = new JButton("Xoá nhật ký");
        clear.addActionListener(e -> clear());

        JButton copy = new JButton("Chép toàn bộ");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(area.getText()), null));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(autoScroll);
        actions.add(copy);
        actions.add(clear);
        add(actions, BorderLayout.SOUTH);

        new Timer(120, e -> drain()).start();
    }

    /** An toàn để gọi từ bất kỳ luồng nào. */
    void append(String line) {
        pending.add(line);
    }

    void clear() {
        pending.clear();
        area.setText("");
    }

    private void drain() {
        if (pending.isEmpty()) return;

        List<String> batch = new ArrayList<>();
        for (String line = pending.poll(); line != null; line = pending.poll()) {
            batch.add(line);
        }

        StringBuilder sb = new StringBuilder();
        for (String line : batch) sb.append(line).append('\n');
        area.append(sb.toString());

        trimIfTooLong();

        if (autoScroll.isSelected()) {
            area.setCaretPosition(area.getDocument().getLength());
        }
    }

    private void trimIfTooLong() {
        int lines = area.getLineCount();
        if (lines <= MAX_LINES) return;
        try {
            int cut = area.getLineEndOffset(lines - MAX_LINES - 1);
            area.replaceRange("", 0, cut);
        } catch (javax.swing.text.BadLocationException ignored) {
            // Không cắt được thì cứ để nguyên, không ảnh hưởng chức năng chính.
        }
    }
}
