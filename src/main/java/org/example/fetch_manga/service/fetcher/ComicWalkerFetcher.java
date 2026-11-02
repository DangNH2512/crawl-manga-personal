package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.FieldSpec;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.ComicWalker;

/** Nguồn Comic Walker / カドコミ. */
public class ComicWalkerFetcher extends AbstractMangaFetcher {

    private static final String FORMAT = "format";

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("comicwalker", "Comic Walker / カドコミ")
                .example("https://comic-walker.com/detail/KC_008602_S/episodes/KC_0086020000200011_E")
                .hosts("comic-walker.com")
                .chapterNotInUrl()
                .field(CommonFields.cookie())
                .field(FieldSpec.of(FORMAT, "Định dạng ảnh")
                        .choices("webp (giữ nguyên gốc)", "jpg", "png")
                        .defaultValue("webp (giữ nguyên gốc)")
                        .remembered())
                .field(CommonFields.seriesFolder())
                .field(FieldSpec.of(CommonFields.CHAP, "Tên folder chương")
                        .hint("Để trống = \"Chap <episodeNo>\"."))
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        ComicWalker scraper = prepare(new ComicWalker(), request);
        scraper.setCookie(request.get(CommonFields.COOKIE));
        scraper.setImageFormat(normalizeFormat(request.get(FORMAT)));
        scraper.setFolderOverride(request.get(CommonFields.SERIES));
        scraper.setChapOverride(request.get(CommonFields.CHAP));
        scraper.setOutputFolder(folderOrNull(request));
        scraper.fetch(request.getUrl());
    }

    /** Ô chọn hiển thị nhãn dễ đọc, còn scraper chỉ hiểu đuôi file thuần. */
    private String normalizeFormat(String label) {
        if (label.startsWith("jpg")) return "jpg";
        if (label.startsWith("png")) return "png";
        return "webp";
    }
}
