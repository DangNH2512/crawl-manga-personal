package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.PixivComic;

/** Nguồn pixiv Comic. */
public class PixivComicFetcher extends AbstractMangaFetcher {

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("pixivcomic", "pixiv Comic")
                .example("https://comic.pixiv.net/viewer/stories/239638")
                .hosts("comic.pixiv.net")
                .chapterNotInUrl()
                .field(CommonFields.cookie())
                .field(CommonFields.seriesFolder())
                .field(CommonFields.chapLabel())
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        PixivComic scraper = prepare(new PixivComic(), request);
        scraper.setCookie(request.get(CommonFields.COOKIE));
        scraper.setFolderOverride(request.get(CommonFields.SERIES));
        scraper.setChapOverride(request.get(CommonFields.CHAP));
        scraper.setOutputFolder(folderOrNull(request));
        scraper.fetch(request.getUrl());
    }
}
