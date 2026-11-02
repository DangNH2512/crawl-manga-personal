package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.ComixTo;

/** Nguồn Comix.to. */
public class ComixToFetcher extends AbstractMangaFetcher {

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("comixto", "Comix.to")
                .example("https://comix.to/title/n85jy-ten-truyen/3240974-chapter-156")
                .hosts("comix.to")
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        ComixTo scraper = prepare(new ComixTo(), request);
        scraper.setFolderName(folderOrNull(request));
        scraper.fetch(request.getUrl());
    }
}
