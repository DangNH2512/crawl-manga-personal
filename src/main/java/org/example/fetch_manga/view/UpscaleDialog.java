package org.example.fetch_manga.view;

import org.example.fetch_manga.controller.FetchController;
import org.example.fetch_manga.service.JobListener;
import org.example.fetch_manga.service.upscale.ImageUpscaler;
import org.example.fetch_manga.service.upscale.UpscaleEngine;
import org.example.fetch_manga.service.upscale.UpscaleModel;
import org.example.fetch_manga.service.upscale.UpscaleOptions;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JFormattedTextField;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.DefaultFormatter;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Cửa sổ upscale ảnh bằng AI, dựng theo kiểu các trang upscale: chọn hoặc kéo thả ảnh,
 * chọn mô hình và mức phóng to, xem thử một trang với thanh so sánh trước/sau, rồi
 * mới chạy cho cả thư mục.
 *
 * <p>Việc chạy thật được giao cho {@link FetchController} như mọi công việc nền khác,
 * nên tiến độ hiện ở ô Nhật ký của cửa sổ chính và bấm Huỷ ở đó là dừng được.</p>
 */
final class UpscaleDialog extends JDialog {

    private static final String[] IMAGE_EXTENSIONS = {"jpg", "jpeg", "png", "webp", "bmp", "gif", "tif", "tiff"};

    private final FetchController controller;
    private final JobListener jobListener;

    private final JTextField sourceField = new JTextField();
    private final JButton folderButton = new JButton("Chọn thư mục...");
    private final JButton filesButton = new JButton("Chọn ảnh lẻ...");
    private final JCheckBox recursiveBox = new JCheckBox("Gồm cả thư mục con");
    private final JLabel countLabel = new JLabel(" ");

    private final JComboBox<UpscaleModel> modelCombo = new JComboBox<>(UpscaleModel.values());
    private final JComboBox<UpscaleOptions.SizeMode> sizeModeCombo = new JComboBox<>(UpscaleOptions.SizeMode.values());
    private final JComboBox<Integer> scaleCombo = new JComboBox<>();
    private final JSpinner pixelSpinner = new JSpinner(new SpinnerNumberModel(
            UpscaleOptions.DEFAULT_TARGET_PIXELS, UpscaleOptions.MIN_TARGET_PIXELS,
            UpscaleOptions.MAX_TARGET_PIXELS, 10));
    private final JLabel pixelHint = new JLabel("px (tối đa " + UpscaleOptions.MAX_TARGET_PIXELS + ")");
    private final JComboBox<Integer> denoiseCombo = new JComboBox<>();
    private final JLabel noteLabel = new JLabel(" ");
    private final JLabel engineLabel = new JLabel(" ");
    private final JButton installButton = new JButton("Tải engine");
    private final JProgressBar installBar = new JProgressBar(0, 100);

    private final JComboBox<Path> previewCombo = new JComboBox<>();
    private final JButton previewButton = new JButton("Xem thử");
    private final JLabel previewInfo = new JLabel(" ");
    private final CompareView compareView = new CompareView();

    private final JButton startButton = new JButton("Upscale & ghi đè");

    /** Thư mục đang chọn, hoặc {@code null} khi người dùng chọn ảnh lẻ. */
    private Path folder;
    private List<Path> images = List.of();
    /** Tăng mỗi lần đổi nguồn ảnh, để kết quả quét cũ về muộn không đè lên kết quả mới. */
    private int scanGeneration;
    /** Đang đổ lại các combo theo mô hình — bỏ qua sự kiện để không lưu giá trị dở dang. */
    private boolean filling;
    /** Đang tải engine hoặc đang xem thử. */
    private boolean busy;
    /** Mô hình và chế độ kích thước hiện tại có cho chọn khử nhiễu không. */
    private boolean denoiseApplicable = true;

    UpscaleDialog(JFrame owner, FetchController controller, JobListener jobListener, String suggestedFolder) {
        super(owner, "Upscale ảnh bằng AI", true);
        this.controller = controller;
        this.jobListener = jobListener;

        // Đang tải engine hay đang xem thử thì không cho đóng: engine vẫn chạy ngầm trên GPU mà
        // không ai thấy, rồi giành bộ nhớ GPU với lượt upscale chạy sau.
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeIfIdle();
            }
        });

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(buildSourceSection());
        top.add(Box.createVerticalStrut(6));
        top.add(buildModelSection());

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
        content.add(top, BorderLayout.NORTH);
        content.add(buildPreviewSection(), BorderLayout.CENTER);
        content.add(buildActionRow(), BorderLayout.SOUTH);
        setContentPane(content);

        installDropSupport();
        getRootPane().registerKeyboardAction(e -> closeIfIdle(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        applyOptions(controller.getUpscaleOptions());
        compareView.showMessage("Kéo thả ảnh hoặc thư mục vào đây,\nhoặc chọn ở mục 1 rồi bấm Xem thử");

        Path suggested = suggestedFolder == null || suggestedFolder.isBlank() ? null : Path.of(suggestedFolder);
        if (suggested != null && Files.isDirectory(suggested)) {
            setFolder(suggested);
        } else {
            updateButtons();
        }

        setMinimumSize(new Dimension(760, 640));
        setSize(960, 860);
        setLocationRelativeTo(owner);
    }

    // -- Dựng giao diện -------------------------------------------------------

    private JPanel buildSourceSection() {
        sourceField.setEditable(false);
        sourceField.setFont(UiTheme.monoFont(13));

        folderButton.addActionListener(e -> chooseFolder());
        filesButton.addActionListener(e -> chooseFiles());
        recursiveBox.addActionListener(e -> scan());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(folderButton);
        buttons.add(filesButton);

        JPanel pathRow = new JPanel(new BorderLayout(6, 0));
        pathRow.add(sourceField, BorderLayout.CENTER);
        pathRow.add(buttons, BorderLayout.EAST);

        JPanel optionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        optionRow.add(recursiveBox);
        optionRow.add(Box.createHorizontalStrut(12));
        optionRow.add(UiTheme.muted(countLabel));

        JPanel section = new JPanel(new BorderLayout(0, 4));
        section.setBorder(UiTheme.section("1. Ảnh cần upscale"));
        section.add(pathRow, BorderLayout.NORTH);
        section.add(optionRow, BorderLayout.SOUTH);
        return section;
    }

    private JPanel buildModelSection() {
        modelCombo.setMaximumRowCount(10);
        modelCombo.addActionListener(e -> onOptionChanged());
        sizeModeCombo.addActionListener(e -> onOptionChanged());
        scaleCombo.addActionListener(e -> onOptionChanged());
        pixelSpinner.addChangeListener(e -> onOptionChanged());
        denoiseCombo.addActionListener(e -> onOptionChanged());

        // Gõ tới đâu nhận tới đó (trong khoảng hợp lệ); gõ quá 2000 thì ô tự trả về giá trị cũ.
        // Định dạng "#" để hiện 2000 thay vì 2,000.
        pixelSpinner.setEditor(new JSpinner.NumberEditor(pixelSpinner, "#"));
        JFormattedTextField pixelField = ((JSpinner.DefaultEditor) pixelSpinner.getEditor()).getTextField();
        pixelField.setColumns(5);
        ((DefaultFormatter) pixelField.getFormatter()).setCommitsOnValidEdit(true);
        pixelSpinner.setToolTipText("Kích thước muốn có, từ " + UpscaleOptions.MIN_TARGET_PIXELS + " đến "
                + UpscaleOptions.MAX_TARGET_PIXELS + " px. Ảnh đã đủ lớn thì được giữ nguyên.");

        scaleCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value == null ? "" : value + "x",
                        index, selected, focus);
            }
        });
        denoiseCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                String text = "";
                if (value != null) {
                    text = denoiseApplicable
                            ? selectedModel().denoiseLabel((Integer) value)
                            : "Không áp dụng cho mô hình này";
                }
                return super.getListCellRendererComponent(list, text, index, selected, focus);
            }
        });
        denoiseCombo.setToolTipText("Khử nhiễu xoá vết nén JPEG, nhưng mức cao có thể xoá luôn tram chấm "
                + "của manga. Ảnh đã khá sạch thì để Không khử nhiễu.");

        installButton.addActionListener(e -> {
            UpscaleEngine engine = selectedModel().getEngine();
            if (confirmInstall(engine)) installEngine(engine, null);
        });
        installBar.setStringPainted(true);
        installBar.setVisible(false);
        installBar.setPreferredSize(new Dimension(180, 18));

        JPanel section = new JPanel(new GridBagLayout());
        section.setBorder(UiTheme.section("2. Mô hình AI"));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 0, 2, 8);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0;
        section.add(new JLabel("Mô hình:"), c);
        c.gridx = 1; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        section.add(modelCombo, c);

        // Chọn tỉ lệ thì hiện combo x2/x3/x4; chọn theo pixel thì hiện ô nhập số.
        JPanel sizeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        sizeRow.add(sizeModeCombo);
        sizeRow.add(scaleCombo);
        sizeRow.add(pixelSpinner);
        sizeRow.add(UiTheme.muted(pixelHint));

        c.gridy = 1; c.gridx = 0; c.gridwidth = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        section.add(new JLabel("Kích thước:"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        section.add(sizeRow, c);

        c.gridy = 2; c.gridx = 0; c.gridwidth = 1; c.weightx = 0;
        section.add(new JLabel("Khử nhiễu:"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        section.add(denoiseCombo, c);

        c.gridy = 3; c.gridx = 1; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL;
        section.add(UiTheme.muted(noteLabel), c);

        JPanel engineRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        engineRow.add(engineLabel);
        engineRow.add(Box.createHorizontalStrut(10));
        engineRow.add(installButton);
        engineRow.add(installBar);
        c.gridy = 4;
        section.add(engineRow, c);
        return section;
    }

    private JPanel buildPreviewSection() {
        previewCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value == null ? "" : displayName((Path) value),
                        index, selected, focus);
            }
        });
        previewCombo.setMaximumRowCount(20);
        previewCombo.addActionListener(e -> updateButtons());
        previewButton.addActionListener(e -> runPreview());
        previewInfo.setFont(UiTheme.uiFont(Font.PLAIN, 12));

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(new JLabel("Ảnh:"));
        row.add(Box.createHorizontalStrut(6));
        row.add(previewCombo);
        row.add(Box.createHorizontalStrut(6));
        row.add(previewButton);
        row.add(Box.createHorizontalStrut(12));
        row.add(previewInfo);
        row.add(Box.createHorizontalGlue());
        Dimension comboSize = new Dimension(280, previewCombo.getPreferredSize().height);
        previewCombo.setPreferredSize(comboSize);
        previewCombo.setMaximumSize(comboSize);

        JPanel section = new JPanel(new BorderLayout(0, 6));
        section.setBorder(UiTheme.section("3. Xem thử trước / sau"));
        section.add(row, BorderLayout.NORTH);
        section.add(compareView, BorderLayout.CENTER);
        return section;
    }

    private JPanel buildActionRow() {
        JLabel warning = new JLabel("⚠ Ảnh gốc sẽ bị ghi đè bằng bản upscale và không lấy lại được.");
        warning.setForeground(UiTheme.ERROR);

        startButton.setFont(UiTheme.uiFont(Font.BOLD, 14));
        startButton.setBackground(UiTheme.ACCENT);
        startButton.setForeground(java.awt.Color.WHITE);
        startButton.setOpaque(true);
        startButton.setBorderPainted(false);
        startButton.setPreferredSize(new Dimension(230, 36));
        startButton.addActionListener(e -> startUpscale());

        JButton close = new JButton("Đóng");
        close.setPreferredSize(new Dimension(100, 36));
        close.addActionListener(e -> closeIfIdle());

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        row.add(warning);
        row.add(Box.createHorizontalGlue());
        row.add(startButton);
        row.add(Box.createHorizontalStrut(8));
        row.add(close);
        return row;
    }

    /** Kéo thả ảnh hoặc thư mục từ Explorer vào khung xem thử hay ô đường dẫn. */
    private void installDropSupport() {
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return !busy && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    acceptDropped((List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor));
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        };
        compareView.setTransferHandler(handler);
        sourceField.setTransferHandler(handler);
    }

    // -- Chọn ảnh -------------------------------------------------------------

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn thư mục chứa ảnh cần upscale");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        setStartDirectory(chooser);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            setFolder(chooser.getSelectedFile().toPath());
        }
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn ảnh cần upscale (giữ Ctrl để chọn nhiều)");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Ảnh (jpg, png, webp...)", IMAGE_EXTENSIONS));
        setStartDirectory(chooser);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            List<Path> picked = new ArrayList<>();
            for (File file : chooser.getSelectedFiles()) picked.add(file.toPath());
            setFiles(picked);
        }
    }

    private void setStartDirectory(JFileChooser chooser) {
        Path start = folder;
        if (start == null && !images.isEmpty()) start = images.get(0).getParent();
        if (start == null) start = Path.of(controller.getSettings().getBasePath());
        while (start != null && !Files.isDirectory(start)) start = start.getParent();
        if (start != null) chooser.setCurrentDirectory(start.toFile());
    }

    private void acceptDropped(List<File> files) {
        if (files.size() == 1 && files.get(0).isDirectory()) {
            setFolder(files.get(0).toPath());
            return;
        }
        List<Path> picked = new ArrayList<>();
        for (File file : files) {
            if (file.isFile()) picked.add(file.toPath());
        }
        setFiles(picked);
    }

    private void setFolder(Path dir) {
        folder = dir;
        sourceField.setText(dir.toString());
        recursiveBox.setEnabled(true);
        scan();
    }

    private void setFiles(List<Path> files) {
        List<Path> picked = new ArrayList<>();
        for (Path file : files) {
            if (ImageUpscaler.isImage(file)) picked.add(file);
        }
        if (picked.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Không có file ảnh nào trong những gì vừa chọn.",
                    "Chưa có ảnh", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        picked.sort(Comparator.comparing(Path::toString, String::compareToIgnoreCase));

        folder = null;
        scanGeneration++;
        recursiveBox.setEnabled(false);
        sourceField.setText(picked.size() == 1
                ? picked.get(0).toString()
                : picked.size() + " ảnh trong " + picked.get(0).getParent());
        setImages(picked);
    }

    /** Đếm ảnh trong thư mục trên luồng nền — thư mục lớn có "gồm thư mục con" có thể mất vài giây. */
    private void scan() {
        if (folder == null) return;
        int generation = ++scanGeneration;
        Path dir = folder;
        boolean recursive = recursiveBox.isSelected();
        setImages(List.of());
        countLabel.setText("Đang đếm ảnh...");

        new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() throws IOException {
                return controller.listImages(dir, recursive);
            }

            @Override
            protected void done() {
                if (generation != scanGeneration) return;
                try {
                    setImages(get());
                } catch (ExecutionException ex) {
                    setImages(List.of());
                    countLabel.setText("Không đọc được thư mục: " + messageOf(ex.getCause()));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void setImages(List<Path> list) {
        images = list;
        countLabel.setText(list.isEmpty() ? "Không có ảnh nào" : list.size() + " ảnh");
        previewCombo.setModel(new DefaultComboBoxModel<>(list.toArray(new Path[0])));
        if (!list.isEmpty()) {
            compareView.showMessage("Chọn một ảnh ở trên rồi bấm Xem thử\nđể so sánh trước khi chạy cả loạt");
            previewInfo.setText(" ");
        }
        updateButtons();
    }

    private String displayName(Path image) {
        if (folder != null && image.startsWith(folder)) {
            return folder.relativize(image).toString();
        }
        return image.getFileName().toString();
    }

    // -- Mô hình --------------------------------------------------------------

    private UpscaleModel selectedModel() {
        UpscaleModel model = (UpscaleModel) modelCombo.getSelectedItem();
        return model == null ? UpscaleModel.REALCUGAN_SE : model;
    }

    private int selectedScale() {
        Integer scale = (Integer) scaleCombo.getSelectedItem();
        return scale == null ? 2 : scale;
    }

    private UpscaleOptions currentOptions() {
        Integer denoise = (Integer) denoiseCombo.getSelectedItem();
        return UpscaleOptions.of(selectedModel(), (UpscaleOptions.SizeMode) sizeModeCombo.getSelectedItem(),
                selectedScale(), ((Number) pixelSpinner.getValue()).intValue(),
                denoise == null ? UpscaleModel.NO_DENOISE : denoise);
    }

    private void onOptionChanged() {
        if (filling) return;
        UpscaleOptions options = currentOptions();
        applyOptions(options);
        controller.saveUpscaleOptions(options);
    }

    /** Đổ lại các combo đúng theo mô hình: chỉ đưa ra mức phóng to/khử nhiễu có file mô hình. */
    private void applyOptions(UpscaleOptions options) {
        filling = true;
        try {
            UpscaleModel model = options.getModel();
            modelCombo.setSelectedItem(model);

            DefaultComboBoxModel<Integer> scales = new DefaultComboBoxModel<>();
            for (int s : model.getScales()) scales.addElement(s);
            scaleCombo.setModel(scales);
            scaleCombo.setSelectedItem(options.getScale());

            sizeModeCombo.setSelectedItem(options.getSizeMode());
            pixelSpinner.setValue(options.getTargetPixels());
            scaleCombo.setVisible(options.isScaleMode());
            pixelSpinner.setVisible(!options.isScaleMode());
            pixelHint.setVisible(!options.isScaleMode());

            int[] levels = options.denoiseLevels();
            denoiseApplicable = levels.length > 0;
            DefaultComboBoxModel<Integer> denoise = new DefaultComboBoxModel<>();
            if (levels.length == 0) {
                denoise.addElement(UpscaleModel.NO_DENOISE);
            } else {
                for (int level : levels) denoise.addElement(level);
            }
            denoiseCombo.setModel(denoise);
            denoiseCombo.setSelectedItem(options.getDenoise());

            noteLabel.setText(options.isScaleMode()
                    ? model.getNote()
                    : "<html>" + model.getNote() + "<br>AI phóng " + scalesText(model)
                    + " lên khoảng gấp đôi " + options.getTargetPixels() + "px rồi thu về đúng cỡ bằng Lanczos "
                    + "(nét mượt hơn). Ảnh đã đủ " + options.getTargetPixels() + "px thì giữ nguyên.</html>");
            revalidate();
            refreshEnabled();
            refreshEngineStatus();
        } finally {
            filling = false;
        }
    }

    private static String scalesText(UpscaleModel model) {
        StringBuilder text = new StringBuilder();
        for (int s : model.getScales()) {
            if (text.length() > 0) text.append('/');
            text.append(s).append('x');
        }
        return text.toString();
    }

    private void refreshEngineStatus() {
        UpscaleEngine engine = selectedModel().getEngine();
        boolean installed = controller.isEngineInstalled(engine);
        if (!installBar.isVisible()) {
            engineLabel.setText(installed
                    ? "Engine " + engine.getDisplayName() + ": đã cài ✔"
                    : "Engine " + engine.getDisplayName() + ": chưa cài - cần tải khoảng "
                    + engine.sizeMb() + " MB (chỉ một lần)");
            engineLabel.setForeground(installed ? UiTheme.OK : UiTheme.MUTED);
        }
        installButton.setVisible(!installed && !installBar.isVisible());
    }

    private boolean confirmInstall(UpscaleEngine engine) {
        int answer = JOptionPane.showConfirmDialog(this,
                "Engine " + engine.getDisplayName() + " chưa có trên máy.\n"
                        + "Tải về khoảng " + engine.sizeMb() + " MB từ GitHub (chỉ tải một lần)?",
                "Tải engine", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        return answer == JOptionPane.YES_OPTION;
    }

    private void installEngine(UpscaleEngine engine, Runnable then) {
        setBusy(true);
        installBar.setValue(0);
        installBar.setVisible(true);
        installButton.setVisible(false);
        engineLabel.setForeground(UiTheme.MUTED);
        engineLabel.setText("Đang tải engine " + engine.getDisplayName() + "...  ");

        new SwingWorker<Void, Integer>() {
            @Override
            protected Void doInBackground() throws Exception {
                controller.installEngine(engine, (done, total) ->
                        publish((int) (done * 100 / Math.max(1, total))));
                return null;
            }

            @Override
            protected void process(List<Integer> chunks) {
                installBar.setValue(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                installBar.setVisible(false);
                setBusy(false);
                refreshEngineStatus();
                try {
                    get();
                    if (then != null && isDisplayable()) then.run();
                } catch (ExecutionException ex) {
                    JOptionPane.showMessageDialog(UpscaleDialog.this,
                            "Không tải được engine:\n" + messageOf(ex.getCause()),
                            "Lỗi", JOptionPane.ERROR_MESSAGE);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    // -- Xem thử và chạy ------------------------------------------------------

    private void runPreview() {
        Path image = (Path) previewCombo.getSelectedItem();
        if (image == null || busy) return;

        UpscaleOptions options = currentOptions();
        UpscaleEngine engine = options.getModel().getEngine();
        if (!controller.isEngineInstalled(engine)) {
            if (confirmInstall(engine)) installEngine(engine, this::runPreview);
            return;
        }

        setBusy(true);
        previewInfo.setForeground(UIManager.getColor("Label.foreground"));
        previewInfo.setText(" ");
        compareView.showMessage("Đang upscale " + image.getFileName() + " bằng " + options.describe() + "...");
        long startedAt = System.currentTimeMillis();

        new SwingWorker<BufferedImage[], Void>() {
            @Override
            protected BufferedImage[] doInBackground() throws Exception {
                BufferedImage before = ImageIO.read(image.toFile());
                if (before == null) {
                    throw new IOException("Không đọc được ảnh " + image.getFileName());
                }
                return new BufferedImage[]{before, controller.previewUpscale(image, options)};
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    BufferedImage[] result = get();
                    compareView.setImages(result[0], result[1]);
                    previewInfo.setText(String.format("%d×%d → %d×%d   ·   %s   ·   %.1f giây",
                            result[0].getWidth(), result[0].getHeight(),
                            result[1].getWidth(), result[1].getHeight(),
                            options.describe(), (System.currentTimeMillis() - startedAt) / 1000.0));
                } catch (ExecutionException ex) {
                    compareView.showMessage("Không xem thử được:\n" + messageOf(ex.getCause()));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void startUpscale() {
        if (images.isEmpty() || busy) return;

        UpscaleOptions options = currentOptions();
        UpscaleEngine engine = options.getModel().getEngine();
        String download = controller.isEngineInstalled(engine)
                ? ""
                : "•  Lần đầu sẽ tải engine " + engine.getDisplayName() + " (khoảng " + engine.sizeMb() + " MB).\n";

        int answer = JOptionPane.showConfirmDialog(this,
                "Upscale " + images.size() + " ảnh bằng " + options.describe() + " và GHI ĐÈ lên ảnh gốc?\n\n"
                        + "•  Ảnh gốc sẽ bị thay bằng bản upscale, không lấy lại được.\n"
                        + (options.isScaleMode()
                        ? "•  Chạy lại trên cùng ảnh sẽ phóng to thêm một lần nữa.\n"
                        : "•  Ảnh đã đủ " + options.getTargetPixels() + "px được giữ nguyên, chạy lại cũng không sao.\n")
                        + download
                        + "\nTiến độ hiện ở ô Nhật ký của cửa sổ chính.",
                "Xác nhận ghi đè ảnh gốc", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;

        try {
            controller.saveUpscaleOptions(options);
            controller.startUpscale(images, options, jobListener);
            dispose();
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Chưa chạy được", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void closeIfIdle() {
        if (busy) {
            previewInfo.setForeground(UiTheme.ERROR);
            previewInfo.setText("Đang tải engine / xem thử, đợi xong rồi đóng.");
            return;
        }
        dispose();
    }

    private void setBusy(boolean value) {
        busy = value;
        refreshEnabled();
        updateButtons();
    }

    private void refreshEnabled() {
        folderButton.setEnabled(!busy);
        filesButton.setEnabled(!busy);
        recursiveBox.setEnabled(!busy && folder != null);
        modelCombo.setEnabled(!busy);
        sizeModeCombo.setEnabled(!busy);
        scaleCombo.setEnabled(!busy && selectedModel().getScales().length > 1);
        pixelSpinner.setEnabled(!busy);
        denoiseCombo.setEnabled(!busy && denoiseApplicable);
        installButton.setEnabled(!busy);
    }

    private void updateButtons() {
        boolean hasImages = !images.isEmpty();
        startButton.setEnabled(hasImages && !busy);
        startButton.setText(hasImages ? "Upscale & ghi đè " + images.size() + " ảnh" : "Upscale & ghi đè");
        previewButton.setEnabled(previewCombo.getSelectedItem() != null && !busy);
    }

    private static String messageOf(Throwable error) {
        if (error == null) return "lỗi không rõ";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
