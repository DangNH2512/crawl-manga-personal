package org.example.fetch_manga.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Mô tả MỘT ô nhập liệu mà một nguồn truyện cần.
 *
 * <p>Đây là lớp thuộc tầng Model: nó chỉ mô tả dữ liệu, hoàn toàn không biết
 * Swing là gì. View đọc mô tả này để tự dựng form, còn Controller đọc để kiểm
 * tra dữ liệu bắt buộc. Nhờ vậy thêm một nguồn truyện mới không phải sửa View.</p>
 */
public final class FieldSpec {

    private final String key;
    private final String label;
    private final String hint;
    private final String defaultValue;
    private final FieldType type;
    private final boolean required;
    private final boolean remembered;
    private final List<String> options;

    private FieldSpec(Builder b) {
        this.key = Objects.requireNonNull(b.key, "key");
        this.label = Objects.requireNonNull(b.label, "label");
        this.hint = b.hint == null ? "" : b.hint;
        this.defaultValue = b.defaultValue == null ? "" : b.defaultValue;
        this.type = b.type;
        this.required = b.required;
        this.remembered = b.remembered;
        this.options = Collections.unmodifiableList(b.options);
    }

    public String getKey()          { return key; }
    public String getLabel()        { return label; }
    public String getHint()         { return hint; }
    public String getDefaultValue() { return defaultValue; }
    public FieldType getType()      { return type; }

    /** Controller sẽ chặn nếu ô bắt buộc bị bỏ trống. */
    public boolean isRequired()     { return required; }

    /** Giá trị có được ghi nhớ giữa các lần chạy không (cookie, token...). */
    public boolean isRemembered()   { return remembered; }

    /** Danh sách lựa chọn, chỉ có ý nghĩa với {@link FieldType#CHOICE}. */
    public List<String> getOptions() { return options; }

    @Override
    public String toString() {
        return "FieldSpec[" + key + ", " + type + "]";
    }

    public static Builder of(String key, String label) {
        return new Builder(key, label);
    }

    /** Builder cho {@link FieldSpec} — giúp phần khai báo nguồn đọc dễ như một bảng cấu hình. */
    public static final class Builder {
        private final String key;
        private final String label;
        private String hint;
        private String defaultValue;
        private FieldType type = FieldType.TEXT;
        private boolean required;
        private boolean remembered;
        private List<String> options = Collections.emptyList();

        private Builder(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public Builder hint(String hint)            { this.hint = hint; return this; }
        public Builder defaultValue(String value)   { this.defaultValue = value; return this; }
        public Builder type(FieldType type)         { this.type = type; return this; }
        public Builder required()                   { this.required = true; return this; }
        public Builder remembered()                 { this.remembered = true; return this; }

        public Builder choices(String... values) {
            this.type = FieldType.CHOICE;
            this.options = List.of(values);
            return this;
        }

        public FieldSpec build() { return new FieldSpec(this); }
    }
}
