package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.FieldSpec;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.SundayWebry;

/** Nguồn Sunday Webry (GigaViewer). */
public class SundayWebryFetcher extends AbstractMangaFetcher {

    private static final String FORMAT = "format";

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("sundaywebry", "Sunday Webry")
                .note("Episode trả phí cần cookie của tài khoản đã đăng nhập và đã mua point.\n"
                        + "Ảnh bị scramble phải vẽ lại nên buộc mã hoá lại — chọn PNG để khỏi nén chồng nén.\n"
                        + "Ảnh không scramble luôn được ghi nguyên bản, không phụ thuộc ô này.")
                .example("https://www.sunday-webry.com/episode/XXXXX")
                .hosts("sunday-webry.com")
                .chapterNotInUrl()
                .field(CommonFields.cookie())
                .field(FieldSpec.of(FORMAT, "Định dạng ảnh descramble")
                        .choices("png (không mất chất lượng)", "jpg (nhẹ file)")
                        .defaultValue("png (không mất chất lượng)")
                        .remembered()
                        .hint("PNG lớn hơn nhưng giữ đúng pixel sau khi xếp lại ô."))
                .field(CommonFields.seriesFolder())
                .field(CommonFields.chapLabel())
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        SundayWebry scraper = prepare(new SundayWebry(), request);
        scraper.setCookie(request.get(CommonFields.COOKIE));
        scraper.setImageFormat(normalizeFormat(request.get(FORMAT)));
        scraper.setFolderOverride(request.get(CommonFields.SERIES));
        scraper.setChapOverride(request.get(CommonFields.CHAP));
        scraper.setOutputFolder(folderOrNull(request));
        scraper.fetch(request.getUrl());
    }

    /** Ô chọn hiển thị nhãn dễ đọc, còn scraper chỉ hiểu đuôi file thuần. */
    private String normalizeFormat(String label) {
        return label.startsWith("jpg") ? "jpg" : "png";
    }
}
