package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.FieldSpec;
import org.example.fetch_manga.model.FieldType;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.HerosWeb;

/** Nguồn Heros-Web / Comici — nhận nguyên lệnh cURL copy từ DevTools. */
public class HerosWebFetcher extends AbstractMangaFetcher {

    private static final String CURL = "curl";

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("herosweb", "Heros-Web / Comici")
                .noUrl()
                .note("Mở chương đã mua trong trình duyệt đã đăng nhập →\n"
                        + "F12 → Network → lọc 'contentsInfo' → F5 → chuột phải request →\n"
                        + "Copy → Copy as cURL (bash), rồi dán toàn bộ vào ô dưới.\n"
                        + "Nguồn này bắt buộc phải chọn thư mục lưu.")
                .field(FieldSpec.of(CURL, "Lệnh cURL")
                        .type(FieldType.TEXTAREA)
                        .required()
                        .hint("Dán nguyên khối, kể cả các dòng -H '...'"))
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        String folder = request.getFolder();
        if (folder.isEmpty()) {
            throw new IllegalArgumentException("Nguồn này cần thư mục lưu cụ thể — hãy chọn ở mục Thư mục lưu.");
        }
        // HerosWeb không kế thừa Manga nên không có basePath để gắn.
        new HerosWeb().fetchFromCurl(request.get(CURL), folder);
    }
}
