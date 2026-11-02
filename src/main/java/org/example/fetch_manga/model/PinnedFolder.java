package org.example.fetch_manga.model;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Một thư mục người dùng đã "ghim" để chọn lại nhanh, thay cho việc set cứng
 * đường dẫn trong mã nguồn.
 */
public final class PinnedFolder {

    private final String label;
    private final String path;

    public PinnedFolder(String label, String path) {
        this.path = Objects.requireNonNull(path, "path").trim();
        String name = (label == null || label.isBlank()) ? defaultLabel(this.path) : label.trim();
        this.label = name;
    }

    public PinnedFolder(String path) {
        this(null, path);
    }

    private static String defaultLabel(String path) {
        Path p = Paths.get(path);
        Path name = p.getFileName();
        return name == null ? path : name.toString();
    }

    public String getLabel() { return label; }
    public String getPath()  { return path; }
    public Path toPath()     { return Paths.get(path); }

    /** Hiển thị trong JComboBox: nhãn ngắn kèm đường dẫn đầy đủ để khỏi nhầm. */
    @Override
    public String toString() {
        return label.equals(path) ? path : label + "  —  " + path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PinnedFolder)) return false;
        return path.equalsIgnoreCase(((PinnedFolder) o).path);
    }

    @Override
    public int hashCode() {
        return path.toLowerCase().hashCode();
    }
}
