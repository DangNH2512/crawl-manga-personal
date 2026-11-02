package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.NicoManga;

/** Nguồn NicoManga. */
public class NicoMangaFetcher extends AbstractMangaFetcher {

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("nicomanga", "NicoManga")
                .example("https://nicomanga.com/read-ten-truyen-chapter-84.html")
                .hosts("nicomanga.com")
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        NicoManga scraper = prepare(new NicoManga(), request);
        scraper.fetchChapter(request.getUrl(), folderOrNull(request));
    }
}
