package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.FieldSpec;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.CuuTruyen;

/** Nguồn CuuTruyen — dùng URL trang sửa chương của nhóm dịch. */
public class CuuTruyenFetcher extends AbstractMangaFetcher {

    private static final String TOKEN = "m4uToken";
    private static final String UID = "m4uUid";

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("cuutruyen", "CuuTruyen")
                .note("Dán URL trang edit của một chương.")
                .example("https://cuutruyen.net/team-management/475/chapters/57440/edit")
                .hosts("cuutruyen.net")
                .chapterNotInUrl()
                .field(FieldSpec.of(TOKEN, "m4u_token")
                        .remembered()
                        .hint("Để trống = dùng token mặc định trong mã nguồn."))
                .field(FieldSpec.of(UID, "m4u_uid")
                        .remembered()
                        .hint("Để trống = dùng uid mặc định trong mã nguồn."))
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        CuuTruyen scraper = prepare(new CuuTruyen(), request);
        if (request.has(TOKEN)) scraper.setM4uToken(request.get(TOKEN));
        if (request.has(UID)) scraper.setM4uUid(request.get(UID));
        scraper.fetchFromUrl(request.getUrl(), request.getFolder());
    }
}
