package org.example.fetch_manga.service;

import org.example.fetch_manga.model.AppSettings;
import org.example.fetch_manga.model.PinnedFolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Đọc/ghi {@link AppSettings} xuống đĩa dưới dạng file properties.
 *
 * <p>File nằm tại {@code %USERPROFILE%\.fetch_manga\settings.properties}, nhờ vậy
 * thư mục ghim, thư mục gốc và cookie được giữ lại giữa các lần mở ứng dụng —
 * không còn đường dẫn set cứng trong mã nguồn.</p>
 */
public final class SettingsStore {

    private static final String DIR_NAME = ".fetch_manga";
    private static final String FILE_NAME = "settings.properties";

    private static final String KEY_BASE_PATH = "basePath";
    private static final String KEY_LAST_SOURCE = "lastSourceId";
    private static final String KEY_LAST_SUBFOLDER = "lastSubFolder";
    private static final String KEY_LAST_PINNED = "lastPinnedPath";
    private static final String KEY_CREATE_PSD = "createPsd";
    private static final String KEY_IMAGE_FORMAT = "imageFormat";
    private static final String KEY_UPSCALE_AFTER_FETCH = "upscaleAfterFetch";
    private static final String KEY_UPSCALE_MODEL = "upscaleModel";
    private static final String KEY_UPSCALE_SCALE = "upscaleScale";
    private static final String KEY_UPSCALE_DENOISE = "upscaleDenoise";
    private static final String KEY_UPSCALE_SIZE_MODE = "upscaleSizeMode";
    private static final String KEY_UPSCALE_TARGET_PIXELS = "upscaleTargetPixels";
    private static final String PREFIX_PINNED = "pinned.";
    private static final String PREFIX_REMEMBER = "remember.";

    private final Path file;

    public SettingsStore() {
        this(Paths.get(System.getProperty("user.home", "."), DIR_NAME, FILE_NAME));
    }

    public SettingsStore(Path file) {
        this.file = file;
    }

    public Path getFile() {
        return file;
    }

    /** Nạp cấu hình; nếu chưa có file thì trả về cấu hình mặc định. */
    public AppSettings load() {
        AppSettings settings = new AppSettings();
        if (!Files.isRegularFile(file)) return settings;

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("⚠ Không đọc được cấu hình (" + file + "): " + e.getMessage());
            return settings;
        }

        settings.setBasePath(props.getProperty(KEY_BASE_PATH));
        settings.setLastSourceId(props.getProperty(KEY_LAST_SOURCE, ""));
        settings.setLastSubFolder(props.getProperty(KEY_LAST_SUBFOLDER, ""));
        settings.setLastPinnedPath(props.getProperty(KEY_LAST_PINNED, ""));
        settings.setCreatePsd(Boolean.parseBoolean(props.getProperty(KEY_CREATE_PSD, "false")));
        settings.setImageFormat(props.getProperty(KEY_IMAGE_FORMAT, "original"));
        settings.setUpscaleAfterFetch(Boolean.parseBoolean(props.getProperty(KEY_UPSCALE_AFTER_FETCH, "false")));
        settings.setUpscaleModel(props.getProperty(KEY_UPSCALE_MODEL));
        settings.setUpscaleScale(parseInt(props.getProperty(KEY_UPSCALE_SCALE), settings.getUpscaleScale()));
        settings.setUpscaleDenoise(parseInt(props.getProperty(KEY_UPSCALE_DENOISE), settings.getUpscaleDenoise()));
        settings.setUpscaleSizeMode(props.getProperty(KEY_UPSCALE_SIZE_MODE));
        settings.setUpscaleTargetPixels(parseInt(props.getProperty(KEY_UPSCALE_TARGET_PIXELS),
                settings.getUpscaleTargetPixels()));

        readPinned(props, settings);
        readRemembered(props, settings);
        return settings;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void readPinned(Properties props, AppSettings settings) {
        // Gom các chỉ số pinned.<i>.* rồi đọc theo thứ tự tăng dần.
        TreeSet<Integer> indexes = new TreeSet<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(PREFIX_PINNED)) continue;
            String rest = name.substring(PREFIX_PINNED.length());
            int dot = rest.indexOf('.');
            if (dot <= 0) continue;
            try {
                indexes.add(Integer.parseInt(rest.substring(0, dot)));
            } catch (NumberFormatException ignored) {
                // Bỏ qua khoá hỏng thay vì làm hỏng cả file cấu hình.
            }
        }
        for (int i : indexes) {
            String path = props.getProperty(PREFIX_PINNED + i + ".path");
            if (path == null || path.isBlank()) continue;
            settings.pin(new PinnedFolder(props.getProperty(PREFIX_PINNED + i + ".label"), path));
        }
    }

    private void readRemembered(Properties props, AppSettings settings) {
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(PREFIX_REMEMBER)) continue;
            String key = name.substring(PREFIX_REMEMBER.length());
            String value = props.getProperty(name);
            if (value != null && !value.isEmpty()) {
                settings.getRememberedValues().put(key, value);
            }
        }
    }

    /** Ghi cấu hình xuống đĩa, tự tạo thư mục cha nếu chưa có. */
    public void save(AppSettings settings) {
        Properties props = new Properties();
        props.setProperty(KEY_BASE_PATH, settings.getBasePath());
        props.setProperty(KEY_LAST_SOURCE, settings.getLastSourceId());
        props.setProperty(KEY_LAST_SUBFOLDER, settings.getLastSubFolder());
        props.setProperty(KEY_LAST_PINNED, settings.getLastPinnedPath());
        props.setProperty(KEY_CREATE_PSD, Boolean.toString(settings.isCreatePsd()));
        props.setProperty(KEY_IMAGE_FORMAT, settings.getImageFormat());
        props.setProperty(KEY_UPSCALE_AFTER_FETCH, Boolean.toString(settings.isUpscaleAfterFetch()));
        props.setProperty(KEY_UPSCALE_MODEL, settings.getUpscaleModel());
        props.setProperty(KEY_UPSCALE_SCALE, Integer.toString(settings.getUpscaleScale()));
        props.setProperty(KEY_UPSCALE_DENOISE, Integer.toString(settings.getUpscaleDenoise()));
        props.setProperty(KEY_UPSCALE_SIZE_MODE, settings.getUpscaleSizeMode());
        props.setProperty(KEY_UPSCALE_TARGET_PIXELS, Integer.toString(settings.getUpscaleTargetPixels()));

        int i = 0;
        for (PinnedFolder folder : settings.getPinnedFolders()) {
            props.setProperty(PREFIX_PINNED + i + ".label", folder.getLabel());
            props.setProperty(PREFIX_PINNED + i + ".path", folder.getPath());
            i++;
        }

        for (Map.Entry<String, String> e : settings.getRememberedValues().entrySet()) {
            props.setProperty(PREFIX_REMEMBER + e.getKey(), e.getValue());
        }

        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Fetch Manga - cau hinh nguoi dung");
            }
        } catch (IOException e) {
            System.err.println("⚠ Không lưu được cấu hình (" + file + "): " + e.getMessage());
        }
    }
}
