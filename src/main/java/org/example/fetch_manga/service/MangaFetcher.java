package org.example.fetch_manga.service;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.SourceDescriptor;

/**
 * Hợp đồng chung cho mọi nguồn truyện (mẫu thiết kế Strategy).
 *
 * <p>Trước đây mỗi nguồn được gọi bằng một nhánh {@code case} riêng trong hàm
 * {@code main}; giờ Controller chỉ cần cầm một {@code MangaFetcher} và gọi
 * {@link #fetch(FetchRequest)} — thêm nguồn mới không phải sửa Controller hay View.</p>
 */
public interface MangaFetcher {

    /** Mô tả nguồn: tên, hướng dẫn, các ô nhập liệu cần thiết. */
    SourceDescriptor getDescriptor();

    /** Thực hiện tải một chương theo dữ liệu người dùng đã nhập. */
    void fetch(FetchRequest request) throws Exception;

    /**
     * Tên thư mục chương gợi ý từ link, hoặc chuỗi rỗng nếu không đoán được.
     * Nguồn nào biết rõ quy ước URL của mình có thể ghi đè để đoán chuẩn hơn.
     */
    default String suggestChapterFolder(String url) {
        if (!getDescriptor().hasChapterInUrl()) return "";
        return ChapterNameGuesser.guess(url);
    }

    default String getId() { return getDescriptor().getId(); }
}
