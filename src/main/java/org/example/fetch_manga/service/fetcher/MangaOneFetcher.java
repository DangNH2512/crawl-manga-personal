package org.example.fetch_manga.service.fetcher;

import org.example.fetch_manga.model.FetchRequest;
import org.example.fetch_manga.model.FieldSpec;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.AbstractMangaFetcher;
import org.example.fetch_manga.service.site.MangaOne;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Nguồn Manga One / マンガワン (manga-one.com, Shogakukan). */
public class MangaOneFetcher extends AbstractMangaFetcher {

    private static final String MODE = "mode";
    private static final String MODE_APP = "Tải từ app iOS (960px, cần secret)";
    private static final String MODE_WEB = "Tải từ web (720px, cần cookie)";
    private static final String APP_SECRET = "appSecret";

    /** Resource bị .gitignore (repo public) chứa secret điền sẵn; xem README. */
    private static final String BUNDLED_SECRET_FILE = "/mangaone-app.properties";

    @Override
    protected SourceDescriptor describe() {
        return SourceDescriptor.of("mangaone", "Manga One / マンガワン")
                .note("Dán được link chương (/chapter/), link chia sẻ từ app (/title/) lẫn link ảnh bonus (/choitashi/).\n"
                        + "App iOS: ảnh 960px không mã hoá, chỉ cần secret. Chỉ tải chương tài khoản đã mở sẵn "
                        + "trên app — không bao giờ tự tiêu coin/ライフ.\n"
                        + "Web: ảnh 720px giải mã AES, BẮT BUỘC có cookie.\n"
                        + "Ảnh bonus choitashi luôn đi đường web (960px, cần cookie).")
                .example("https://manga-one.com/manga/28498/chapter/359449")
                .hosts("manga-one.com")
                .chapterNotInUrl()
                .field(FieldSpec.of(MODE, "Tải từ")
                        .choices(MODE_APP, MODE_WEB)
                        .defaultValue(MODE_APP)
                        .remembered())
                .field(FieldSpec.of(APP_SECRET, "Secret app iOS")
                        .defaultValue(bundledSecret())
                        .remembered()
                        .hint("Xoá đi rồi dán secret mới nếu secret cũ hết hiệu lực. "
                                + "Nhận 32 ký tự hex hoặc nguyên URL request của app có secret=..."))
                .field(CommonFields.cookie())
                .build();
    }

    @Override
    public void fetch(FetchRequest request) throws Exception {
        MangaOne scraper = prepare(new MangaOne(), request);
        if (!MODE_WEB.equals(request.get(MODE))) {
            if (!request.has(APP_SECRET)) {
                throw new IllegalArgumentException(
                        "Tải từ app cần Secret app iOS — dán secret, hoặc chọn \"" + MODE_WEB + "\".");
            }
            scraper.setAppSecret(request.get(APP_SECRET));
        }
        scraper.setCookie(request.get(CommonFields.COOKIE));
        scraper.setOutputFolder(folderOrNull(request));
        scraper.fetch(request.getUrl());
    }

    /** Secret đóng gói kèm bản build, hoặc chuỗi rỗng nếu bản build không có file đó. */
    private static String bundledSecret() {
        try (InputStream in = MangaOneFetcher.class.getResourceAsStream(BUNDLED_SECRET_FILE)) {
            if (in == null) return "";
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("appSecret", "").trim();
        } catch (IOException e) {
            return "";
        }
    }
}
