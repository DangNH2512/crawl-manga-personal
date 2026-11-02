package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FieldSpec;
import org.example.fetch_manga.model.FieldType;

/**
 * Kho các ô nhập liệu lặp đi lặp lại giữa nhiều nguồn (cookie, tên series, số
 * chương...). Gom về một chỗ để nhãn và gợi ý luôn đồng nhất trên toàn ứng dụng.
 */
final class CommonFields {

    static final String COOKIE = "cookie";
    static final String SERIES = "series";
    static final String CHAP = "chap";

    private CommonFields() { }

    /** Cookie trình duyệt — được ghi nhớ để không phải dán lại mỗi lần. */
    static FieldSpec.Builder cookie() {
        return FieldSpec.of(COOKIE, "Cookie")
                .type(FieldType.TEXTAREA)
                .remembered()
                .hint("Để trống nếu chương miễn phí. F12 → Network → copy header Cookie.");
    }

    /** Tên thư mục series khi người dùng không tự chọn thư mục lưu. */
    static FieldSpec.Builder seriesFolder() {
        return FieldSpec.of(SERIES, "Tên folder series")
                .hint("Để trống = tự lấy theo tên tác phẩm trên trang.");
    }

    /** Số/nhãn chương ép thủ công. */
    static FieldSpec.Builder chapLabel() {
        return FieldSpec.of(CHAP, "Số chương")
                .hint("Để trống = tự lấy từ trang.");
    }
}
