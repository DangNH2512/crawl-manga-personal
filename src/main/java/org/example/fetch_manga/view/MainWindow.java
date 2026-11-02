package org.example.fetch_manga.view;

import org.example.fetch_manga.controller.FetchController;
import org.example.fetch_manga.model.FetchOutcome;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.ConsoleBridge;
import org.example.fetch_manga.service.JobListener;
import org.example.fetch_manga.service.upscale.UpscaleOptions;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JList;
import javax.swing.JFormattedTextField;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.DefaultFormatter;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.Map;

/**
 * Cửa sổ chính — tầng View của ứng dụng.
 *
 * <p>Luồng thao tác đúng như ý tưởng ban đầu: dán link ở trên, chọn thư mục đã
 * ghim ở giữa, bấm Tải. Cửa sổ không hề biết cách tải ảnh; nó chỉ thu thập dữ
 * liệu rồi giao cho {@link FetchController} và nhận lại tiến độ qua
 * {@link JobListener}.</p>
 */
public class MainWindow extends JFrame implements JobListener {

    private final FetchController controller;

    private final JComboBox<SourceDescriptor> sourceCombo = new JComboBox<>();
    private final JTextField urlField = new JTextField();
    private final JLabel detectedLabel = new JLabel();

    private final FolderPanel folderPanel;
    private final SourceFormPanel formPanel;
    private final LogPanel logPanel = new LogPanel();

    private final JButton fetchButton = new JButton("Tải chương");
    private final JButton cancelButton = new JButton("Huỷ");
    private final JCheckBox psdCheckBox = new JCheckBox("Tạo kèm file .psd cho mỗi ảnh");
    private final JCheckBox upscaleCheckBox = new JCheckBox("Upscale AI sau khi tải (ghi đè ảnh)");
    private final JComboBox<UpscaleOptions.SizeMode> upscaleModeCombo = new JComboBox<>(UpscaleOptions.SizeMode.values());
    private final JComboBox<Integer> upscaleScaleCombo = new JComboBox<>();
    private final JSpinner upscalePixelSpinner = new JSpinner(new SpinnerNumberModel(
            UpscaleOptions.DEFAULT_TARGET_PIXELS, UpscaleOptions.MIN_TARGET_PIXELS,
            UpscaleOptions.MAX_TARGET_PIXELS, 10));
    private final JLabel upscalePixelUnit = new JLabel("px (tối đa " + UpscaleOptions.MAX_TARGET_PIXELS + ")");
    private final JComboBox<Integer> upscaleDenoiseCombo = new JComboBox<>();
    /** Đang đổ giá trị từ cấu hình vào các ô kích thước — bỏ qua sự kiện. */
    private boolean syncingUpscale;
    private final JComboBox<String> formatCombo = new JComboBox<>();
    private final JLabel statusLabel = new JLabel("Sẵn sàng.");
    private final JProgressBar progressBar = new JProgressBar();

    /** Chặn vòng lặp khi việc tự nhận diện nguồn làm combo phát sự kiện. */
    private boolean syncing;

    public MainWindow(FetchController controller) {
        super("Fetch Manga");
        this.controller = controller;
        this.folderPanel = new FolderPanel(controller, this::refreshStatusHint);
        this.formPanel = new SourceFormPanel(controller.getSettings());

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setJMenuBar(buildMenuBar());
        setLayout(new BorderLayout(0, 0));

        add(buildTopArea(), BorderLayout.NORTH);
        add(buildCenterArea(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        fillSources();
        ConsoleBridge.setSink(logPanel::append);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmAndClose();
            }
        });

        setMinimumSize(new Dimension(860, 640));
        setSize(1000, 780);
        setLocationRelativeTo(null);
    }

    // -- Dựng giao diện -------------------------------------------------------

    private JMenuBar buildMenuBar() {
        JMenuItem changeBase = new JMenuItem("Đổi thư mục gốc...");
        changeBase.addActionListener(e -> changeBasePath());

        JMenuItem openConfig = new JMenuItem("Mở thư mục cấu hình");
        openConfig.addActionListener(e -> openConfigFolder());

        JMenuItem forget = new JMenuItem("Quên cookie / token đã lưu");
        forget.addActionListener(e -> forgetRememberedValues());

        JMenu settings = new JMenu("Cấu hình");
        settings.add(changeBase);
        settings.add(openConfig);
        settings.addSeparator();
        settings.add(forget);

        JMenuItem psdBatch = new JMenuItem("Tạo .psd cho thư mục...");
        psdBatch.addActionListener(e -> runPsdBatch());

        JMenuItem upscale = new JMenuItem("Upscale ảnh (AI)...");
        upscale.addActionListener(e -> openUpscaleDialog());

        JMenu tools = new JMenu("Công cụ");
        tools.add(upscale);
        tools.add(psdBatch);

        JMenuItem about = new JMenuItem("Giới thiệu");
        about.addActionListener(e -> showAbout());

        JMenu help = new JMenu("Trợ giúp");
        help.add(about);

        JMenuBar bar = new JMenuBar();
        bar.add(settings);
        bar.add(tools);
        bar.add(help);
        return bar;
    }

    /** Tạo .psd cho một thư mục ảnh có sẵn, không cần tải lại chương. */
    private void runPsdBatch() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn thư mục chứa ảnh cần tạo .psd");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        String suggested = folderPanel.getResolvedFolder();
        File start = new File(suggested.isEmpty() ? controller.getSettings().getBasePath() : suggested);
        while (start != null && !start.isDirectory()) {
            start = start.getParentFile();
        }
        if (start != null) chooser.setCurrentDirectory(start);

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File folder = chooser.getSelectedFile();
        int answer = JOptionPane.showConfirmDialog(this,
                "Tạo .psd cho ảnh trong:\n" + folder
                        + "\n\nCó xử lý cả các thư mục con không?"
                        + "\n(Ảnh nào đã có .psd sẽ được bỏ qua.)",
                "Tạo file .psd", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) return;

        try {
            controller.startPsdBatch(folder.toPath(), answer == JOptionPane.YES_OPTION, this);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Chưa chạy được", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Mở cửa sổ upscale, điền sẵn thư mục đang chọn ở Bước 2 nếu thư mục đó đã có. */
    private void openUpscaleDialog() {
        new UpscaleDialog(this, controller, this, folderPanel.getResolvedFolder()).setVisible(true);
        // Cửa sổ upscale là modal: tới đây người dùng đã đóng nó, có thể đã đổi mô hình.
        refreshUpscaleSizeControls();
    }

    /** Dòng chọn kích thước upscale ngay dưới ô tick: tỉ lệ x2/x3/x4 hoặc số pixel (tối đa 2000). */
    private JPanel buildUpscaleSizeLine() {
        upscaleModeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                String text = "";
                if (value == UpscaleOptions.SizeMode.SCALE) text = "Theo tỉ lệ";
                else if (value == UpscaleOptions.SizeMode.WIDTH) text = "Chiều rộng";
                else if (value == UpscaleOptions.SizeMode.HEIGHT) text = "Chiều cao";
                return super.getListCellRendererComponent(list, text, index, selected, focus);
            }
        });
        upscaleScaleCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value == null ? "" : "x" + value,
                        index, selected, focus);
            }
        });

        upscalePixelSpinner.setEditor(new JSpinner.NumberEditor(upscalePixelSpinner, "#"));
        JFormattedTextField field = ((JSpinner.DefaultEditor) upscalePixelSpinner.getEditor()).getTextField();
        field.setColumns(4);
        ((DefaultFormatter) field.getFormatter()).setCommitsOnValidEdit(true);
        upscalePixelSpinner.setToolTipText("Số pixel muốn có, từ " + UpscaleOptions.MIN_TARGET_PIXELS + " đến "
                + UpscaleOptions.MAX_TARGET_PIXELS + ". Ảnh đã đủ lớn thì giữ nguyên.");

        upscaleDenoiseCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                String text = "";
                if (value != null) {
                    UpscaleOptions options = controller.getUpscaleOptions();
                    text = options.denoiseLevels().length == 0
                            ? "Không có"
                            : options.getModel().denoiseLabel((Integer) value);
                }
                return super.getListCellRendererComponent(list, text, index, selected, focus);
            }
        });
        upscaleDenoiseCombo.setToolTipText("Ảnh web nén mạnh (nét vỡ, viền lốm đốm) thì chọn Bảo toàn nét hoặc Mạnh. "
                + "Mức Mạnh có thể xoá bớt tram chấm.");

        upscaleModeCombo.addActionListener(e -> onUpscaleSizeChanged());
        upscaleScaleCombo.addActionListener(e -> onUpscaleSizeChanged());
        upscalePixelSpinner.addChangeListener(e -> onUpscaleSizeChanged());
        upscaleDenoiseCombo.addActionListener(e -> onUpscaleSizeChanged());

        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        line.add(Box.createHorizontalStrut(16));
        line.add(new JLabel("Cỡ:"));
        line.add(upscaleModeCombo);
        line.add(upscaleScaleCombo);
        line.add(upscalePixelSpinner);
        line.add(upscalePixelUnit);
        line.add(Box.createHorizontalStrut(8));
        line.add(new JLabel("Khử nhiễu:"));
        line.add(upscaleDenoiseCombo);
        refreshUpscaleSizeControls();
        return line;
    }

    private void onUpscaleSizeChanged() {
        if (syncingUpscale) return;
        UpscaleOptions current = controller.getUpscaleOptions();
        Integer scale = (Integer) upscaleScaleCombo.getSelectedItem();
        Integer denoise = (Integer) upscaleDenoiseCombo.getSelectedItem();
        controller.saveUpscaleOptions(UpscaleOptions.of(current.getModel(),
                (UpscaleOptions.SizeMode) upscaleModeCombo.getSelectedItem(),
                scale == null ? current.getScale() : scale,
                ((Number) upscalePixelSpinner.getValue()).intValue(),
                denoise == null ? current.getDenoise() : denoise));
        refreshUpscaleSizeControls();
    }

    /** Đổ lựa chọn kích thước đang lưu vào các ô, hiện ô tỉ lệ hoặc ô pixel tuỳ chế độ. */
    private void refreshUpscaleSizeControls() {
        syncingUpscale = true;
        try {
            UpscaleOptions options = controller.getUpscaleOptions();
            upscaleModeCombo.setSelectedItem(options.getSizeMode());

            DefaultComboBoxModel<Integer> scales = new DefaultComboBoxModel<>();
            for (int s : options.getModel().getScales()) scales.addElement(s);
            upscaleScaleCombo.setModel(scales);
            upscaleScaleCombo.setSelectedItem(options.getScale());
            upscalePixelSpinner.setValue(options.getTargetPixels());

            upscaleScaleCombo.setVisible(options.isScaleMode());
            upscalePixelSpinner.setVisible(!options.isScaleMode());
            upscalePixelUnit.setVisible(!options.isScaleMode());

            int[] levels = options.denoiseLevels();
            DefaultComboBoxModel<Integer> denoise = new DefaultComboBoxModel<>();
            if (levels.length == 0) {
                denoise.addElement(options.getDenoise());
            } else {
                for (int level : levels) denoise.addElement(level);
            }
            upscaleDenoiseCombo.setModel(denoise);
            upscaleDenoiseCombo.setSelectedItem(options.getDenoise());
            upscaleDenoiseCombo.setEnabled(levels.length > 0);
        } finally {
            syncingUpscale = false;
        }
        refreshUpscaleHint();
        revalidate();
        repaint();
    }

    private void refreshUpscaleHint() {
        UpscaleOptions options = controller.getUpscaleOptions();
        upscaleCheckBox.setToolTipText("<html>Tải xong sẽ upscale các ảnh vừa tải và <b>ghi đè</b> lên ảnh gốc.<br>"
                + "Đang dùng: " + options.describe() + "<br>"
                + "Đổi mô hình và xem thử ở Công cụ → Upscale ảnh (AI)...</html>");
    }

    private JPanel buildTopArea() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(UiTheme.section("Bước 1 - Dán link chương"));

        // Hiện đủ 14 nguồn trong một lần mở, khỏi phải cuộn tìm.
        sourceCombo.setMaximumRowCount(20);
        sourceCombo.setRenderer(new SourceListRenderer());
        sourceCombo.setToolTipText("Dán link vào ô trên là nguồn tự được chọn; danh sách này để chọn tay khi cần");
        sourceCombo.addActionListener(e -> {
            if (syncing) return;
            onSourceChanged();
        });

        JButton paste = new JButton("Dán");
        paste.setToolTipText("Dán link từ clipboard");
        paste.addActionListener(e -> pasteFromClipboard());

        urlField.setFont(UiTheme.monoFont(13));
        urlField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { autoDetectSource(); }
            @Override public void removeUpdate(DocumentEvent e) { autoDetectSource(); }
            @Override public void changedUpdate(DocumentEvent e) { autoDetectSource(); }
        });
        urlField.addActionListener(e -> startFetch());

        JPanel urlRow = new JPanel(new BorderLayout(6, 0));
        urlRow.add(new JLabel("Link chương:"), BorderLayout.WEST);
        urlRow.add(urlField, BorderLayout.CENTER);
        urlRow.add(paste, BorderLayout.EAST);

        JPanel sourceRow = new JPanel(new BorderLayout(6, 0));
        sourceRow.add(new JLabel("Nguồn:"), BorderLayout.WEST);
        sourceRow.add(sourceCombo, BorderLayout.CENTER);
        sourceRow.add(UiTheme.muted(detectedLabel), BorderLayout.EAST);

        panel.add(urlRow, BorderLayout.NORTH);
        panel.add(sourceRow, BorderLayout.SOUTH);
        return panel;
    }

    private JSplitPane buildCenterArea() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        form.add(labelled("Bước 2 - Chọn nơi lưu", folderPanel));
        form.add(labelled("Bước 3 - Thông tin thêm (nếu cần)", formPanel));
        form.add(buildActionRow());
        form.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(form,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, logPanel);
        split.setResizeWeight(0.62);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
    }

    /** Panel con đã tự có tiêu đề riêng; hàm này chỉ giữ chúng giãn đúng chiều ngang. */
    private JPanel labelled(String unusedTitle, JPanel content) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(content, BorderLayout.CENTER);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, content.getPreferredSize().height + 200));
        return holder;
    }

    private JPanel buildActionRow() {
        fetchButton.setFont(UiTheme.uiFont(Font.BOLD, 14));
        fetchButton.setBackground(UiTheme.ACCENT);
        fetchButton.setForeground(java.awt.Color.WHITE);
        fetchButton.setOpaque(true);
        fetchButton.setBorderPainted(false);
        fetchButton.setPreferredSize(new Dimension(170, 38));
        fetchButton.addActionListener(e -> startFetch());

        cancelButton.setEnabled(false);
        cancelButton.setPreferredSize(new Dimension(110, 38));
        cancelButton.setToolTipText("Ngắt lượt tải hiện tại (các ảnh đang tải dở vẫn có thể chạy nốt)");
        cancelButton.addActionListener(e -> cancelFetch());

        JButton clearForm = new JButton("Xoá form");
        clearForm.setPreferredSize(new Dimension(110, 38));
        clearForm.addActionListener(e -> {
            urlField.setText("");
            formPanel.clearTransientFields();
        });

        psdCheckBox.setSelected(controller.getSettings().isCreatePsd());
        psdCheckBox.setToolTipText("Tải xong sẽ tạo thêm 001.psd cạnh mỗi ảnh, gồm lớp Background "
                + "và một lớp nhân bản ở trên");
        psdCheckBox.addActionListener(e -> {
            controller.getSettings().setCreatePsd(psdCheckBox.isSelected());
            controller.saveSettings();
        });

        upscaleCheckBox.setSelected(controller.getSettings().isUpscaleAfterFetch());
        refreshUpscaleHint();
        upscaleCheckBox.addActionListener(e -> {
            controller.getSettings().setUpscaleAfterFetch(upscaleCheckBox.isSelected());
            controller.saveSettings();
            if (upscaleCheckBox.isSelected()) {
                UpscaleOptions options = controller.getUpscaleOptions();
                String engine = options.getModel().getEngine().getDisplayName();
                statusLabel.setForeground(UiTheme.MUTED);
                statusLabel.setText(controller.isEngineInstalled(options.getModel().getEngine())
                        ? "Tải xong sẽ upscale bằng " + options.describe() + "."
                        : "Tải xong sẽ upscale bằng " + options.describe() + " - lần đầu tự tải engine "
                        + engine + " (" + options.getModel().getEngine().sizeMb() + " MB).");
            }
        });

        initFormatCombo();

        // Hai ô tick xếp chồng để hàng nút không tràn khi cửa sổ hẹp.
        JPanel afterFetch = new JPanel();
        afterFetch.setLayout(new BoxLayout(afterFetch, BoxLayout.Y_AXIS));
        psdCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        upscaleCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel sizeLine = buildUpscaleSizeLine();
        sizeLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        afterFetch.add(psdCheckBox);
        afterFetch.add(upscaleCheckBox);
        afterFetch.add(sizeLine);

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(4, 8, 10, 8));
        row.add(fetchButton);
        row.add(Box.createHorizontalStrut(8));
        row.add(cancelButton);
        row.add(Box.createHorizontalStrut(8));
        row.add(clearForm);
        row.add(Box.createHorizontalStrut(16));
        row.add(new JLabel("Định dạng:"));
        row.add(Box.createHorizontalStrut(4));
        row.add(formatCombo);
        row.add(Box.createHorizontalStrut(14));
        row.add(afterFetch);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    /** Ba lựa chọn định dạng ảnh đích, ánh xạ nhãn dễ đọc ↔ mã lưu trong cấu hình. */
    private void initFormatCombo() {
        formatCombo.addItem("Giữ nguyên gốc");
        formatCombo.addItem("PNG (mở được trên PSD)");
        formatCombo.addItem("JPG (mở được trên PSD)");

        switch (controller.getSettings().getImageFormat()) {
            case "png": formatCombo.setSelectedIndex(1); break;
            case "jpg": formatCombo.setSelectedIndex(2); break;
            default:    formatCombo.setSelectedIndex(0);
        }

        formatCombo.setMaximumSize(formatCombo.getPreferredSize());
        formatCombo.setToolTipText("Chuyển ảnh sau khi tải sang PNG/JPG — hai loại Photoshop mở được. "
                + "WebP (một số nguồn tải về) thì Photoshop bản cũ không mở được.");
        formatCombo.addActionListener(e -> {
            String value = "original";
            if (formatCombo.getSelectedIndex() == 1) value = "png";
            else if (formatCombo.getSelectedIndex() == 2) value = "jpg";
            controller.getSettings().setImageFormat(value);
            controller.saveSettings();
        });
    }

    private JPanel buildStatusBar() {
        progressBar.setPreferredSize(new Dimension(180, 16));
        progressBar.setVisible(false);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
        bar.add(statusLabel, BorderLayout.CENTER);
        bar.add(progressBar, BorderLayout.EAST);
        return bar;
    }

    // -- Nguồn truyện ---------------------------------------------------------

    private void fillSources() {
        syncing = true;
        for (SourceDescriptor source : controller.getSources()) {
            sourceCombo.addItem(source);
        }
        SourceDescriptor last = controller.sourceById(controller.getSettings().getLastSourceId());
        if (last != null) {
            sourceCombo.setSelectedItem(last);
        }
        syncing = false;
        onSourceChanged();
    }

    private SourceDescriptor selectedSource() {
        return (SourceDescriptor) sourceCombo.getSelectedItem();
    }

    private void onSourceChanged() {
        SourceDescriptor source = selectedSource();
        if (source == null) return;
        formPanel.showSource(source);
        urlField.setEnabled(source.needsUrl());
        detectedLabel.setText(source.needsUrl() ? "" : "  (nguồn này không dùng ô link)");
        applyChapterSuggestion();
        refreshStatusHint();
    }

    /**
     * Đoán tên thư mục chương từ link rồi điền sẵn vào ô thư mục con, để không
     * phải tự gõ "Chap 94". Thư mục thật được tạo lúc tải, hoặc tạo ngay bằng nút
     * "Tạo thư mục..." nếu muốn.
     */
    private void applyChapterSuggestion() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) return;
        folderPanel.suggestSubFolder(controller.suggestChapterFolder(selectedSource(), url));
    }

    /** Dán link vào là tự chọn đúng nguồn, đỡ phải mở danh sách. */
    private void autoDetectSource() {
        if (syncing) return;
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            detectedLabel.setText("");
            return;
        }
        SourceDescriptor detected = controller.detectSource(url);
        if (detected == null) {
            detectedLabel.setText("  (chưa nhận ra trang, hãy tự chọn nguồn)");
            applyChapterSuggestion();
            return;
        }
        detectedLabel.setText("  (tự nhận: " + detected.getDisplayName() + ")");
        if (detected != selectedSource()) {
            syncing = true;
            sourceCombo.setSelectedItem(detected);
            syncing = false;
            onSourceChanged();
            return;
        }
        applyChapterSuggestion();
    }

    private void pasteFromClipboard() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
            if (data != null) urlField.setText(data.toString().trim());
        } catch (Exception ex) {
            statusLabel.setText("Không đọc được clipboard: " + ex.getMessage());
        }
    }

    // -- Chạy tải -------------------------------------------------------------

    private void startFetch() {
        SourceDescriptor source = selectedSource();
        if (source == null) return;

        Map<String, String> extras = formPanel.getValues();
        String folder = folderPanel.getResolvedFolder();

        try {
            controller.start(source, urlField.getText().trim(), folder, extras, this);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Chưa đủ thông tin", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void cancelFetch() {
        controller.cancel();
        logPanel.append("... Đã gửi yêu cầu huỷ. Ảnh đang tải dở vẫn có thể hoàn tất.");
    }

    // -- JobListener: gọi từ luồng nền nên phải đẩy về luồng giao diện --------

    @Override
    public void onStarted(String jobName) {
        SwingUtilities.invokeLater(() -> {
            fetchButton.setEnabled(false);
            cancelButton.setEnabled(true);
            progressBar.setVisible(true);
            progressBar.setIndeterminate(true);
            statusLabel.setForeground(UiTheme.MUTED);
            statusLabel.setText("Đang chạy: " + jobName + "...");
            logPanel.append("");
            logPanel.append("===== " + jobName + " =====");
        });
    }

    @Override
    public void onLog(String line) {
        logPanel.append(line);
    }

    @Override
    public void onFinished(FetchOutcome outcome) {
        SwingUtilities.invokeLater(() -> {
            fetchButton.setEnabled(true);
            cancelButton.setEnabled(false);
            progressBar.setIndeterminate(false);
            progressBar.setVisible(false);

            if (outcome.isSuccess()) {
                statusLabel.setForeground(UiTheme.OK);
                statusLabel.setText(String.format("Xong sau %.1fs - %s",
                        outcome.getDurationSeconds(), outcome.getJobName()));
            } else {
                statusLabel.setForeground(UiTheme.ERROR);
                statusLabel.setText("Lỗi: " + outcome.getMessage());
                logPanel.append("XX Lỗi: " + outcome.getMessage());
                if (outcome.getError() != null) {
                    logPanel.append("   (" + outcome.getError().getClass().getName() + ")");
                }
            }
        });
    }

    // -- Menu Cấu hình --------------------------------------------------------

    private void changeBasePath() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn thư mục gốc của thư viện truyện");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        File current = new File(controller.getSettings().getBasePath());
        if (current.isDirectory()) chooser.setCurrentDirectory(current);

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        controller.getSettings().setBasePath(chooser.getSelectedFile().getAbsolutePath());
        controller.saveSettings();
        folderPanel.refreshAfterSettingsChange();
        statusLabel.setText("Đã đổi thư mục gốc: " + controller.getSettings().getBasePath());
    }

    private void openConfigFolder() {
        try {
            File dir = controller.getSettingsStore().getFile().toAbsolutePath().getParent().toFile();
            if (!dir.isDirectory()) {
                controller.saveSettings();
            }
            Desktop.getDesktop().open(dir);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Không mở được thư mục cấu hình:\n" + ex.getMessage(),
                    "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void forgetRememberedValues() {
        int answer = JOptionPane.showConfirmDialog(this,
                "Xoá toàn bộ cookie và token đã lưu trong file cấu hình?",
                "Xác nhận", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;

        controller.getSettings().getRememberedValues().clear();
        controller.saveSettings();
        onSourceChanged();
        statusLabel.setText("Đã xoá cookie/token đã lưu.");
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                "Fetch Manga\n\n"
                        + "Số nguồn hỗ trợ: " + controller.getSources().size() + "\n"
                        + "Thư mục gốc: " + controller.getSettings().getBasePath() + "\n"
                        + "File cấu hình: " + controller.getSettingsStore().getFile() + "\n\n"
                        + "Lưu ý: cookie được lưu dạng văn bản thuần trong file cấu hình.",
                "Giới thiệu", JOptionPane.INFORMATION_MESSAGE);
    }

    private void refreshStatusHint() {
        if (controller.isRunning()) return;
        statusLabel.setForeground(UiTheme.MUTED);
        statusLabel.setText("Sẵn sàng.");
    }

    private void confirmAndClose() {
        if (controller.isRunning()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Đang có lượt tải chưa xong. Vẫn thoát?",
                    "Thoát", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
        }
        controller.shutdown();
        dispose();
        System.exit(0);
    }
}
