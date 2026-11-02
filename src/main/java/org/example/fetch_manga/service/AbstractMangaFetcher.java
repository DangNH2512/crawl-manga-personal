package org.example.fetch_manga.service;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.site.Manga;

/**
 * Lớp cha cho mọi adapter nguồn truyện: giữ phần mô tả và các tiện ích lặp lại,
 * để lớp con chỉ còn phải viết đúng hai việc — khai báo form và gọi scraper.
 */
public abstract class AbstractMangaFetcher implements MangaFetcher {

    private final SourceDescriptor descriptor;

    protected AbstractMangaFetcher() {
        this.descriptor = describe();
    }

    /** Lớp con khai báo form của nguồn tại đây. Được gọi đúng một lần khi khởi tạo. */
    protected abstract SourceDescriptor describe();

    @Override
    public final SourceDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * Gắn thư mục gốc do người dùng cấu hình vào scraper, thay cho đường dẫn
     * từng được set cứng trong mã nguồn.
     */
    protected <T extends Manga> T prepare(T scraper, FetchRequest request) {
        scraper.setBasePath(request.getBasePath());
        return scraper;
    }

    /**
     * Thư mục lưu tuyệt đối người dùng chọn, hoặc {@code null} nếu để trống —
     * khi đó scraper tự áp quy ước thư mục riêng của nó.
     */
    protected String folderOrNull(FetchRequest request) {
        return request.getOrNull(FetchRequest.FOLDER);
    }

    @Override
    public String toString() {
        return descriptor.getDisplayName();
    }
}
