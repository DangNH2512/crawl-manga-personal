package org.example.fetch_manga.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Mô tả MỘT nguồn truyện: tên hiển thị, ghi chú hướng dẫn, URL ví dụ, danh sách
 * ô nhập liệu và các tên miền dùng để tự nhận diện nguồn khi người dùng dán link.
 *
 * <p>Model thuần: không phụ thuộc Swing, không phụ thuộc code tải ảnh.</p>
 */
public final class SourceDescriptor {

    private final String id;
    private final String displayName;
    private final String note;
    private final String example;
    private final boolean needsUrl;
    private final boolean chapterInUrl;
    private final List<FieldSpec> fields;
    private final List<String> hosts;

    private SourceDescriptor(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.displayName = Objects.requireNonNull(b.displayName, "displayName");
        this.note = b.note == null ? "" : b.note;
        this.example = b.example == null ? "" : b.example;
        this.needsUrl = b.needsUrl;
        this.chapterInUrl = b.chapterInUrl;
        this.fields = Collections.unmodifiableList(new ArrayList<>(b.fields));
        this.hosts = Collections.unmodifiableList(new ArrayList<>(b.hosts));
    }

    public String getId()               { return id; }
    public String getDisplayName()      { return displayName; }
    public String getNote()             { return note; }
    public String getExample()          { return example; }
    public List<FieldSpec> getFields()  { return fields; }
    public List<String> getHosts()      { return hosts; }

    /** Nguồn này có dùng ô "Link chương" chung ở đầu form không. */
    public boolean needsUrl()           { return needsUrl; }

    /**
     * Link của nguồn này có chứa số chương không. Trang nào chỉ nhét id vào URL
     * (vd {@code /episode/2550912965546773158}) thì trả về false, để ứng dụng khỏi
     * gợi ý một tên thư mục vô nghĩa.
     */
    public boolean hasChapterInUrl()    { return chapterInUrl; }

    /** Dùng để tự chọn nguồn khi người dùng dán link vào ô URL. */
    public boolean matchesUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        for (String host : hosts) {
            if (lower.contains(host)) return true;
        }
        return false;
    }

    /** Tra một ô nhập liệu theo khoá, trả về {@code null} nếu nguồn không có ô đó. */
    public FieldSpec field(String key) {
        for (FieldSpec f : fields) {
            if (f.getKey().equals(key)) return f;
        }
        return null;
    }

    /** JList/JComboBox hiển thị trực tiếp đối tượng này nên toString phải đẹp. */
    @Override
    public String toString() { return displayName; }

    public static Builder of(String id, String displayName) {
        return new Builder(id, displayName);
    }

    /** Builder cho {@link SourceDescriptor}. */
    public static final class Builder {
        private final String id;
        private final String displayName;
        private String note;
        private String example;
        private boolean needsUrl = true;
        private boolean chapterInUrl = true;
        private final List<FieldSpec> fields = new ArrayList<>();
        private final List<String> hosts = new ArrayList<>();

        private Builder(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public Builder note(String note)        { this.note = note; return this; }
        public Builder example(String example)  { this.example = example; return this; }

        /** Nguồn không nhận URL (vd: dán cURL, nhập Chapter ID). */
        public Builder noUrl()                  { this.needsUrl = false; return this; }

        /** Link chỉ chứa id chứ không có số chương, đừng gợi ý tên thư mục từ link. */
        public Builder chapterNotInUrl()        { this.chapterInUrl = false; return this; }

        public Builder hosts(String... hosts) {
            Collections.addAll(this.hosts, hosts);
            return this;
        }

        public Builder field(FieldSpec.Builder field) {
            this.fields.add(field.build());
            return this;
        }

        public SourceDescriptor build() { return new SourceDescriptor(this); }
    }
}
