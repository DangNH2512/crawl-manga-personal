package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.MangaPlus;

/** Nguồn MangaPlus (Shueisha) — đọc danh sách trang qua API protobuf. */
public class MangaPlusFetcher extends AbstractMangaFetcher {

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("mangaplus", "MangaPlus")
                .note("Dán link viewer, phần số cuối URL chính là chapter id.")
                .example("https://mangaplus.shueisha.co.jp/viewer/1028483")
                .hosts("mangaplus.shueisha.co.jp")
                .chapterNotInUrl()
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        MangaPlus scraper = prepare(new MangaPlus(), request);
        scraper.setFolderName(folderOrNull(request));
        scraper.fetch(request.getUrl());
    }
}
