package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.MangaOne;

/** Nguồn Manga One / マンガワン (manga-one.com, Shogakukan). */
public class MangaOneFetcher extends AbstractMangaFetcher {

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("mangaone", "Manga One / マンガワン")
                .note("Dán được cả link chương (/chapter/) lẫn link ảnh bonus (/choitashi/).\n"
                        + "Ảnh bonus choitashi là bản 960px, nét hơn trang truyện thường (720px).\n"
                        + "BẮT BUỘC có cookie của phiên đang đọc được — không cookie thì danh sách trang rỗng.\n"
                        + "Ảnh tải về là .webp gốc, đã giải mã AES.")
                .example("https://manga-one.com/manga/28498/chapter/359449")
                .hosts("manga-one.com")
                .chapterNotInUrl()
                .field(CommonFields.cookie())
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        MangaOne scraper = prepare(new MangaOne(), request);
        scraper.setCookie(request.get(CommonFields.COOKIE));
        scraper.setOutputFolder(folderOrNull(request));
        scraper.fetch(request.getUrl());
    }
}
