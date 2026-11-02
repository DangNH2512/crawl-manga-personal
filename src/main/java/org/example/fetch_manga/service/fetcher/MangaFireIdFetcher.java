package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.FieldSpec;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.MangaFire;

/** Nguồn MangaFire — nhập thẳng chapter id khi link reader không parse được. */
public class MangaFireIdFetcher extends AbstractMangaFetcher {

    private static final String CHAPTER_ID = "chapterId";

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("mangafire-id", "MangaFire (nhập Chapter ID)")
                .noUrl()
                .note("Lấy id ở F12 → Network → request /ajax/read/chapter/XXXXX.\n"
                        + "Nguồn này bắt buộc phải chọn thư mục lưu.")
                .field(FieldSpec.of(CHAPTER_ID, "Chapter ID")
                        .required()
                        .hint("Chỉ gồm chữ số."))
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        String folder = request.getFolder();
        if (folder.isEmpty()) {
            throw new IllegalArgumentException("Nguồn này cần thư mục lưu cụ thể — hãy chọn ở mục Thư mục lưu.");
        }
        MangaFire scraper = prepare(new MangaFire(), request);
        scraper.fetchById(request.get(CHAPTER_ID), folder);
    }
}
