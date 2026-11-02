package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.MangaFire;

/** Nguồn MangaFire — tự tách chapter id từ URL reader. */
public class MangaFireUrlFetcher extends AbstractMangaFetcher {

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("mangafire", "MangaFire (theo link)")
                .note("Cách dùng thông thường: chỉ cần dán link đọc chương.")
                .example("https://mangafire.to/read/ten-truyen.2o1l5/en/chapter-155")
                .hosts("mangafire.to")
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        MangaFire scraper = prepare(new MangaFire(), request);
        scraper.fetchChapter(request.getUrl(), folderOrNull(request));
    }
}
