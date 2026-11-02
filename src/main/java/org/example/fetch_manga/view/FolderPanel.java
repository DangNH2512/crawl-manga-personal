package org.example.fetch_manga.view;

import org.example.fetch_manga.controller.FetchController;
import org.example.fetch_manga.model.PinnedFolder;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Khối "Thư mục lưu": chọn một thư mục đã ghim, chọn tiếp thư mục con bằng menu
 * duyệt cây, và xem trước đường dẫn cuối cùng.
 *
 * <p>Thay cho đường dẫn set cứng trong mã nguồn, người dùng tự ghim các thư mục
 * hay dùng trên máy mình; danh sách này được lưu lại nên lần mở sau vẫn còn.</p>
 */
class FolderPanel extends JPanel {

    /** Mục đầu danh sách: không chọn gì, để mỗi nguồn tự áp quy ước riêng. */
    private static final String NO_PIN = "-- Không chọn: dùng quy ước mặc định của nguồn --";

    /** Chặn menu phình quá to khi thư mục có hàng nghìn mục con. */
    private static final int MAX_ITEMS_PER_LEVEL = 400;

    /** Giới hạn độ sâu để menu lồng nhau không đi mãi (vd gặp junction vòng lặp). */
    private static final int MAX_DEPTH = 12;

    private final FetchController controller;
    private final Runnable onChange;

    private final JComboBox<Object> pinnedCombo = new JComboBox<>();
    private final JTextField subFolderField = new JTextField();
    private final JButton browseSubButton = new JButton("Chọn thư mục con");
    private final JButton createSubButton = new JButton("Tạo thư mục...");
    private final JLabel preview = new JLabel();

    /**
     * Tên thư mục lần gần nhất được đoán từ link. Dùng để biết nội dung đang có
     * trong ô là do ứng dụng điền hay do người dùng tự gõ — chỉ ghi đè cái do
     * ứng dụng điền.
     */
    private String lastSuggestion = "";

    FolderPanel(FetchController controller, Runnable onChange) {
        super(new BorderLayout(0, 6));
        this.controller = controller;
        this.onChange = onChange;
        setBorder(UiTheme.section("Thư mục lưu"));

        // Đọc cấu hình cũ TRƯỚC khi dựng giao diện: thao tác đổ dữ liệu vào combo
        // sẽ kích hoạt refreshPreview, mà hàm đó ghi đè cấu hình bằng nội dung ô
        // đang trống — đọc sau thì mất luôn giá trị của phiên trước.
        String savedPinnedPath = controller.getSettings().getLastPinnedPath();
        String savedSubFolder = controller.getSettings().getLastSubFolder();

        buildLayout();
        reloadPinned();
        restoreLastUsed(savedPinnedPath, savedSubFolder);
        refreshPreview();
    }

    private void buildLayout() {
        JButton browse = new JButton("Duyệt & ghim...");
        browse.setToolTipText("Chọn một thư mục trên máy và ghim lại để lần sau chọn nhanh");
        browse.addActionListener(e -> browseAndPin());

        JButton unpin = new JButton("Bỏ ghim");
        unpin.addActionListener(e -> unpinSelected());

        JButton open = new JButton("Mở thư mục");
        open.addActionListener(e -> openResolvedFolder());

        pinnedCombo.setToolTipText("Danh sách thư mục bạn đã ghim");
        pinnedCombo.addActionListener(e -> refreshPreview());

        subFolderField.setToolTipText(
                "Chọn bằng nút bên cạnh, hoặc gõ tay nếu thư mục chưa tồn tại (vd: Chap 12)");
        subFolderField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshPreview(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshPreview(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshPreview(); }
        });

        browseSubButton.setToolTipText("Duyệt các thư mục con đang có; trỏ vào một mục để xem tiếp cấp sâu hơn");
        browseSubButton.addActionListener(e -> showSubFolderMenu());

        createSubButton.setToolTipText("Tạo ngay một thư mục mới bên trong thư mục đã ghim");
        createSubButton.addActionListener(e -> createSubFolder());

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 0, 3, 6);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        grid.add(new JLabel("Thư mục đã ghim:"), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        grid.add(pinnedCombo, c);

        JPanel pinButtons = new JPanel();
        pinButtons.setLayout(new BoxLayout(pinButtons, BoxLayout.X_AXIS));
        pinButtons.add(browse);
        pinButtons.add(Box.createHorizontalStrut(4));
        pinButtons.add(unpin);
        pinButtons.add(Box.createHorizontalStrut(4));
        pinButtons.add(open);

        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        grid.add(pinButtons, c);

        c.gridx = 0;
        c.gridy = 1;
        grid.add(new JLabel("Thư mục con:"), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        grid.add(subFolderField, c);

        JPanel subButtons = new JPanel();
        subButtons.setLayout(new BoxLayout(subButtons, BoxLayout.X_AXIS));
        subButtons.add(browseSubButton);
        subButtons.add(Box.createHorizontalStrut(4));
        subButtons.add(createSubButton);

        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        grid.add(subButtons, c);

        add(grid, BorderLayout.CENTER);

        preview.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));
        preview.setFont(UiTheme.monoFont(12));
        add(preview, BorderLayout.SOUTH);
    }

    // -- Danh sách ghim -------------------------------------------------------

    private void reloadPinned() {
        Object selected = pinnedCombo.getSelectedItem();
        DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
        model.addElement(NO_PIN);
        for (PinnedFolder folder : controller.getSettings().getPinnedFolders()) {
            model.addElement(folder);
        }
        pinnedCombo.setModel(model);
        if (selected != null) {
            pinnedCombo.setSelectedItem(selected);
        }
        pinnedCombo.setPreferredSize(new Dimension(380, pinnedCombo.getPreferredSize().height));
    }

    /** Khôi phục lựa chọn của phiên trước từ giá trị đã đọc sẵn ở hàm dựng. */
    private void restoreLastUsed(String pinnedPath, String subFolder) {
        if (!pinnedPath.isEmpty()) {
            selectByPath(pinnedPath);
        }
        // Đặt sau selectByPath, vì thao tác chọn combo làm refreshPreview chạy và xoá ô này.
        subFolderField.setText(subFolder);
    }

    private void browseAndPin() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn thư mục lưu truyện");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setCurrentDirectory(startDirectory());

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        String path = chooser.getSelectedFile().getAbsolutePath();
        controller.pinFolder(path);
        reloadPinned();
        selectByPath(path);
        refreshPreview();
    }

    private File startDirectory() {
        String current = selectedPinnedPath();
        if (!current.isEmpty() && Files.isDirectory(Paths.get(current))) {
            return new File(current);
        }
        File base = new File(controller.getSettings().getBasePath());
        return base.isDirectory() ? base : null;
    }

    private void selectByPath(String path) {
        for (int i = 0; i < pinnedCombo.getItemCount(); i++) {
            Object item = pinnedCombo.getItemAt(i);
            if (item instanceof PinnedFolder
                    && ((PinnedFolder) item).getPath().equalsIgnoreCase(path)) {
                pinnedCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    private void unpinSelected() {
        Object item = pinnedCombo.getSelectedItem();
        if (!(item instanceof PinnedFolder)) {
            JOptionPane.showMessageDialog(this, "Hãy chọn một thư mục đã ghim trước đã.",
                    "Chưa chọn gì", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        PinnedFolder folder = (PinnedFolder) item;
        int answer = JOptionPane.showConfirmDialog(this,
                "Bỏ ghim thư mục này?\n" + folder.getPath()
                        + "\n\n(Chỉ xoá khỏi danh sách, không xoá dữ liệu trên ổ đĩa.)",
                "Bỏ ghim", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;

        controller.unpinFolder(folder);
        reloadPinned();
        pinnedCombo.setSelectedIndex(0);
        refreshPreview();
    }

    private void openResolvedFolder() {
        String target = getResolvedFolder();
        if (target.isEmpty()) {
            target = controller.getSettings().getBasePath();
        }

        // Thư mục chương chỉ được tạo lúc tải, nên lùi dần lên thư mục cha đang tồn tại.
        Path path = Paths.get(target);
        while (path != null && !Files.isDirectory(path)) {
            path = path.getParent();
        }

        if (path == null) {
            JOptionPane.showMessageDialog(this, "Chưa có thư mục nào để mở.",
                    "Không mở được", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Không mở được thư mục:\n" + ex.getMessage(),
                    "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    // -- Menu duyệt thư mục con ----------------------------------------------

    /**
     * Mở menu liệt kê thư mục con của thư mục đã ghim. Mỗi mục lại là một menu
     * con, nạp nội dung khi được trỏ tới, nên có thể đi sâu bao nhiêu cấp cũng được
     * mà không phải quét toàn bộ cây ngay từ đầu.
     */
    private void showSubFolderMenu() {
        String root = selectedPinnedPath();
        if (root.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Hãy chọn một thư mục đã ghim trước, rồi mới chọn được thư mục con.",
                    "Chưa chọn thư mục gốc", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Path rootPath = Paths.get(root);
        if (!Files.isDirectory(rootPath)) {
            JOptionPane.showMessageDialog(this,
                    "Thư mục đã ghim không còn tồn tại:\n" + root,
                    "Không mở được", JOptionPane.WARNING_MESSAGE);
            return;
        }

        buildSubFolderMenu(rootPath).show(browseSubButton, 0, browseSubButton.getHeight());
    }

    /** Dựng menu cho một thư mục gốc. Tách khỏi phần hiển thị để kiểm thử được. */
    JPopupMenu buildSubFolderMenu(Path rootPath) {
        JPopupMenu popup = new JPopupMenu();

        JMenuItem useRoot = new JMenuItem("Lưu thẳng vào thư mục đã ghim");
        useRoot.addActionListener(e -> setSubFolder(""));
        popup.add(useRoot);
        popup.addSeparator();

        if (!fillLevel(popup::add, rootPath, "", 0)) {
            popup.add(disabledItem("(không có thư mục con)"));
        }
        return popup;
    }

    /**
     * Thêm mỗi thư mục con của {@code dir} thành một mục menu.
     *
     * @param adder    nơi nhận mục menu (popup gốc hoặc một menu con)
     * @param dir      thư mục đang liệt kê
     * @param relative đường dẫn tương đối của {@code dir} so với thư mục đã ghim
     * @param depth    độ sâu hiện tại, dùng để chặn đệ quy quá sâu
     * @return true nếu có ít nhất một thư mục con được thêm
     */
    private boolean fillLevel(Consumer<JMenuItem> adder, Path dir, String relative, int depth) {
        List<Path> children = listSubDirectories(dir);
        if (children.isEmpty()) return false;

        int shown = 0;
        for (Path child : children) {
            if (shown >= MAX_ITEMS_PER_LEVEL) {
                adder.accept(disabledItem("(còn " + (children.size() - shown) + " thư mục nữa...)"));
                break;
            }
            adder.accept(buildEntry(child, relative, depth));
            shown++;
        }
        return true;
    }

    /** Một thư mục con: bấm để chọn, hoặc trỏ vào để xem tiếp các cấp bên trong. */
    private JMenuItem buildEntry(Path dir, String parentRelative, int depth) {
        String name = dir.getFileName().toString();
        String relative = parentRelative.isEmpty() ? name : parentRelative + File.separator + name;

        if (depth + 1 >= MAX_DEPTH) {
            JMenuItem leaf = new JMenuItem(name);
            leaf.addActionListener(e -> setSubFolder(relative));
            return leaf;
        }

        JMenu menu = new JMenu(name);

        JMenuItem pick = new JMenuItem("Chọn thư mục này");
        pick.setFont(UiTheme.uiFont(Font.BOLD, 12));
        pick.addActionListener(e -> setSubFolder(relative));
        menu.add(pick);
        menu.addSeparator();

        // Nạp cấp sâu hơn đúng một lần, vào lúc người dùng thực sự trỏ tới.
        menu.addMenuListener(new MenuListener() {
            private boolean loaded;

            @Override
            public void menuSelected(MenuEvent e) {
                if (loaded) return;
                loaded = true;
                if (!fillLevel(menu::add, dir, relative, depth + 1)) {
                    menu.add(disabledItem("(không có thư mục con)"));
                }
            }

            @Override public void menuDeselected(MenuEvent e) { }
            @Override public void menuCanceled(MenuEvent e) { }
        });

        return menu;
    }

    private JMenuItem disabledItem(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setEnabled(false);
        return item;
    }

    /** Các thư mục con trực tiếp, sắp xếp tự nhiên để "Chap 2" đứng trước "Chap 10". */
    private List<Path> listSubDirectories(Path dir) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path path : stream) {
                result.add(path);
            }
        } catch (IOException | RuntimeException e) {
            // Thư mục bị khoá quyền hoặc vừa bị xoá: coi như không có mục con.
            return List.of();
        }
        result.sort((a, b) -> compareNatural(
                a.getFileName().toString(), b.getFileName().toString()));
        return result;
    }

    /** So sánh có hiểu số: "Chap 2" &lt; "Chap 10" thay vì ngược lại theo bảng chữ cái. */
    static int compareNatural(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            char a = left.charAt(i);
            char b = right.charAt(j);

            if (Character.isDigit(a) && Character.isDigit(b)) {
                int startA = i;
                int startB = j;
                while (i < left.length() && Character.isDigit(left.charAt(i))) i++;
                while (j < right.length() && Character.isDigit(right.charAt(j))) j++;

                // Bỏ số 0 ở đầu rồi so độ dài trước, để không tràn với số rất dài.
                String numA = left.substring(startA, i).replaceFirst("^0+(?=.)", "");
                String numB = right.substring(startB, j).replaceFirst("^0+(?=.)", "");
                if (numA.length() != numB.length()) return numA.length() - numB.length();
                int cmp = numA.compareTo(numB);
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.compare(Character.toLowerCase(a), Character.toLowerCase(b));
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        return (left.length() - i) - (right.length() - j);
    }

    private void setSubFolder(String relative) {
        subFolderField.setText(relative);
        refreshPreview();
    }

    // -- Tạo thư mục / gợi ý tên theo link ------------------------------------

    /**
     * Điền tên thư mục đoán được từ link vào ô thư mục con.
     *
     * <p>Chỉ điền khi ô đang trống hoặc đang chứa đúng gợi ý cũ — thứ người dùng
     * tự gõ hay tự chọn trong menu không bao giờ bị ghi đè.</p>
     */
    void suggestSubFolder(String suggestion) {
        if (suggestion == null || suggestion.isBlank()) return;

        String current = subFolderField.getText().trim();
        if (!current.isEmpty() && !current.equals(lastSuggestion)) return;

        lastSuggestion = suggestion;
        setSubFolder(suggestion);
    }

    /** Tạo ngay một thư mục con trên đĩa, tên mặc định lấy theo gợi ý từ link. */
    private void createSubFolder() {
        String root = selectedPinnedPath();
        if (root.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Hãy chọn một thư mục đã ghim trước đã.",
                    "Chưa chọn thư mục gốc", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String suggested = subFolderField.getText().trim();
        if (suggested.isEmpty()) suggested = lastSuggestion;

        String answer = (String) JOptionPane.showInputDialog(this,
                "Tạo thư mục mới bên trong:\n" + root
                        + "\n\nCó thể gõ nhiều cấp, vd: Chap 11" + File.separator + "11.2",
                "Tạo thư mục", JOptionPane.PLAIN_MESSAGE, null, null, suggested);

        if (answer == null) return;

        String relative = answer.trim();
        if (relative.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Tên thư mục không được để trống.",
                    "Chưa hợp lệ", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Path target = Paths.get(root).resolve(relative);
        // Chặn kiểu ".." leo ra ngoài thư mục đã ghim.
        if (!target.normalize().startsWith(Paths.get(root).normalize())) {
            JOptionPane.showMessageDialog(this,
                    "Tên thư mục không được trỏ ra ngoài thư mục đã ghim.",
                    "Chưa hợp lệ", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            boolean existed = Files.isDirectory(target);
            Files.createDirectories(target);
            setSubFolder(relative);
            lastSuggestion = relative;
            JOptionPane.showMessageDialog(this,
                    (existed ? "Thư mục đã có sẵn, đã chọn luôn:\n" : "Đã tạo:\n") + target,
                    "Xong", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this,
                    "Không tạo được thư mục:\n" + target + "\n\n" + ex.getMessage(),
                    "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    // -- Trạng thái hiện tại --------------------------------------------------

    private String selectedPinnedPath() {
        Object item = pinnedCombo.getSelectedItem();
        return (item instanceof PinnedFolder) ? ((PinnedFolder) item).getPath() : "";
    }

    /** Đường dẫn lưu cuối cùng; rỗng nghĩa là để nguồn tự quyết định. */
    String getResolvedFolder() {
        return controller.resolveFolder(selectedPinnedPath(), subFolderField.getText());
    }

    private void rememberSelection() {
        controller.getSettings().setLastPinnedPath(selectedPinnedPath());
        controller.getSettings().setLastSubFolder(subFolderField.getText().trim());
    }

    private void refreshPreview() {
        rememberSelection();

        boolean hasPin = !selectedPinnedPath().isEmpty();
        subFolderField.setEnabled(hasPin);
        browseSubButton.setEnabled(hasPin);
        createSubButton.setEnabled(hasPin);

        String resolved = getResolvedFolder();
        if (resolved.isEmpty()) {
            preview.setForeground(UiTheme.MUTED);
            preview.setFont(UiTheme.uiFont(Font.ITALIC, 12));
            preview.setText("Sẽ lưu theo quy ước mặc định của nguồn, trong: "
                    + controller.getSettings().getBasePath());
        } else {
            boolean exists = Files.isDirectory(Paths.get(resolved));
            preview.setForeground(UiTheme.OK);
            preview.setFont(UiTheme.monoFont(12));
            preview.setText("-> Lưu vào:  " + resolved + (exists ? "" : "   (sẽ tạo mới)"));
        }
        if (onChange != null) {
            onChange.run();
        }
    }

    /** Gọi khi thư mục gốc trong cấu hình vừa thay đổi. */
    void refreshAfterSettingsChange() {
        reloadPinned();
        refreshPreview();
    }

}
