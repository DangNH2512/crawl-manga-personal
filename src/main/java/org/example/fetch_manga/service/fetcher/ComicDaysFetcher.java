package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.ComicDays;

/** Nguồn Comic Days (GigaViewer) — ảnh bị xáo trộn nên phải ghép lại sau khi tải. */
public class ComicDaysFetcher extends AbstractMangaFetcher {

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("comicdays", "Comic Days")
                .note("Chương trả phí cần cookie của tài khoản đã mua.")
                .example("https://comic-days.com/episode/2550912965546773158")
                .hosts("comic-days.com")
                .chapterNotInUrl()
                .field(CommonFields.cookie())
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        ComicDays scraper = prepare(new ComicDays(), request);
        scraper.setCookie(request.get(CommonFields.COOKIE));
        scraper.setOutputFolder(folderOrNull(request));
        scraper.fetch(request.getUrl());
    }
}
