package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.MangaPill;

/** Nguồn MangaPill. */
public class MangaPillFetcher extends AbstractMangaFetcher {

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("mangapill", "MangaPill")
                .example("https://mangapill.com/chapters/6675-10094000/ten-truyen-chapter-94")
                .hosts("mangapill.com")
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        MangaPill scraper = prepare(new MangaPill(), request);
        scraper.setFolderName(folderOrNull(request));
        scraper.fetch(request.getUrl());
    }
}
