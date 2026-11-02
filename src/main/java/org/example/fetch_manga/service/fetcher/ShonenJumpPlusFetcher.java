package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.ShonenJumpPlus;

/** Nguồn Shonen Jump+ (GigaViewer) — hỗ trợ cả episode lẻ lẫn magazine. */
public class ShonenJumpPlusFetcher extends AbstractMangaFetcher {

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("shonenjumpplus", "Shonen Jump+")
                .note("Episode miễn phí: /episode/XXXXX — Magazine cần cookie: /magazine/XXXXX")
                .example("https://shonenjumpplus.com/episode/XXXXX")
                .hosts("shonenjumpplus.com")
                .chapterNotInUrl()
                .field(CommonFields.cookie())
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        ShonenJumpPlus scraper = prepare(new ShonenJumpPlus(), request);
        scraper.setCookie(request.get(CommonFields.COOKIE));
        scraper.setFolderName(folderOrNull(request));
        scraper.fetch(request.getUrl());
    }
}
