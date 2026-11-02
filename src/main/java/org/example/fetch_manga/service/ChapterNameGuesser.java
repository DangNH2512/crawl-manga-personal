package org.example.fetch_manga.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đoán tên thư mục chương từ link, để người dùng khỏi phải tự gõ "Chap 94".
 *
 * <p>Chỉ đoán khi link thật sự chứa số chương. Nhiều trang chỉ nhét <em>id</em> vào
 * URL (vd {@code /episode/2550912965546773158}) — đoán bừa sẽ ra tên thư mục vô
 * nghĩa, nên các nguồn đó tự khai báo {@code chapterNotInUrl()} và hàm này không
 * được gọi tới.</p>
 */
public final class ChapterNameGuesser {

    /** Tiền tố đặt trước số chương, khớp với quy ước thư mục sẵn có của dự án. */
    private static final String PREFIX = "Chap ";

    private static final Pattern[] PATTERNS = {
            // .../chapter-94  .../chapter_94  .../read-abc-chapter-84.html
            // Bắt buộc có dấu nối nên "chapters/57440" (id của CuuTruyen) không lọt.
            Pattern.compile("(?:chapter|chap)[-_]([0-9]+(?:[._][0-9]+)?)", Pattern.CASE_INSENSITIVE),

            // .../aomugi-to-hototogisu/4-wa  (kiểu đánh số của mangarawjp)
            Pattern.compile("([0-9]+(?:[._][0-9]+)?)-wa(?![a-z0-9])", Pattern.CASE_INSENSITIVE),

            // .../ch-12  .../ch.12  .../c/12
            Pattern.compile("/c[h]?[-_.]?([0-9]+(?:[._][0-9]+)?)(?:/|$)", Pattern.CASE_INSENSITIVE),
    };

    private ChapterNameGuesser() { }

    /**
     * @return tên thư mục gợi ý dạng {@code "Chap 94"}, hoặc chuỗi rỗng nếu link
     *         không có số chương nhận ra được
     */
    public static String guess(String url) {
        if (url == null || url.isBlank()) return "";

        String path = stripQuery(url.trim());

        for (Pattern pattern : PATTERNS) {
            String number = lastMatch(pattern, path);
            if (!number.isEmpty()) {
                return PREFIX + normalize(number);
            }
        }
        return "";
    }

    private static String stripQuery(String url) {
        int cut = url.length();
        int question = url.indexOf('?');
        if (question >= 0) cut = Math.min(cut, question);
        int hash = url.indexOf('#');
        if (hash >= 0) cut = Math.min(cut, hash);
        return url.substring(0, cut);
    }

    /** Lấy lần khớp CUỐI vì số chương thường nằm ở cuối đường dẫn. */
    private static String lastMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        String found = "";
        while (matcher.find()) {
            found = matcher.group(1);
        }
        return found;
    }

    /** "094" thành "94", "12_5" thành "12.5" cho giống cách người ta đặt tên thư mục. */
    private static String normalize(String number) {
        String cleaned = number.replace('_', '.');
        int dot = cleaned.indexOf('.');
        String whole = dot < 0 ? cleaned : cleaned.substring(0, dot);
        String rest = dot < 0 ? "" : cleaned.substring(dot);

        whole = whole.replaceFirst("^0+(?=[0-9])", "");
        return whole + rest;
    }
}
