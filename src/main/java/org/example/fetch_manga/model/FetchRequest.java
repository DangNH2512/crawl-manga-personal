package org.example.fetch_manga.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dữ liệu người dùng nhập cho một lần tải, đã tách khỏi mọi component giao diện.
 *
 * <p>View gom giá trị các ô nhập thành đối tượng này rồi đưa cho Controller; nhờ
 * vậy tầng Service có thể chạy y hệt dù đầu vào đến từ cửa sổ Swing hay từ menu
 * dòng lệnh.</p>
 */
public final class FetchRequest {

    /** Khoá của ô "Link chương" dùng chung cho mọi nguồn. */
    public static final String URL = "url";

    /** Khoá của thư mục lưu tuyệt đối do người dùng chọn (có thể rỗng). */
    public static final String FOLDER = "folder";

    /** Khoá của thư mục gốc thư viện, do Controller bơm vào từ cấu hình. */
    public static final String BASE_PATH = "basePath";

    private final SourceDescriptor source;
    private final Map<String, String> values;

    private FetchRequest(SourceDescriptor source, Map<String, String> values) {
        this.source = source;
        this.values = Collections.unmodifiableMap(values);
    }

    public SourceDescriptor getSource() { return source; }

    public Map<String, String> getValues() { return values; }

    /** Lấy giá trị đã trim, không bao giờ trả về {@code null}. */
    public String get(String key) {
        String v = values.get(key);
        return v == null ? "" : v.trim();
    }

    /** Giá trị đã trim, hoặc {@code null} nếu rỗng — hợp với các setter kiểu "Enter để bỏ qua". */
    public String getOrNull(String key) {
        String v = get(key);
        return v.isEmpty() ? null : v;
    }

    public boolean has(String key) { return !get(key).isEmpty(); }

    public String getUrl()    { return get(URL); }
    public String getFolder() { return get(FOLDER); }

    public String getBasePath() { return get(BASE_PATH); }

    public static Builder builder(SourceDescriptor source) {
        return new Builder(source);
    }

    /** Builder cho {@link FetchRequest}. */
    public static final class Builder {
        private final SourceDescriptor source;
        private final Map<String, String> values = new LinkedHashMap<>();

        private Builder(SourceDescriptor source) {
            this.source = source;
        }

        public Builder put(String key, String value) {
            values.put(key, value == null ? "" : value);
            return this;
        }

        public FetchRequest build() { return new FetchRequest(source, values); }
    }
}
