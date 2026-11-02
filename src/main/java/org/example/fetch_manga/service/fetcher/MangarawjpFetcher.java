package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.Mangarawjp;

/** Nguồn Mangarawjp (mangarawjp.me). */
public class MangarawjpFetcher extends AbstractMangaFetcher {

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("mangarawjp", "Mangarawjp")
                .note("Link chương bắt buộc chứa '-wa' thì mới tách được số chương.")
                .example("https://mangarawjp.me/manga/aomugi-to-hototogisu/4-wa")
                .hosts("mangarawjp", "himaten")
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        Mangarawjp scraper = prepare(new Mangarawjp(), request);
        // fetch(name, url): name rỗng → scraper giữ quy ước thư mục mặc định của nó.
        scraper.fetch(folderOrNull(request), request.getUrl());
    }
}
