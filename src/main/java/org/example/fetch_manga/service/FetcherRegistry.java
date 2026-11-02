package org.example.fetch_manga.service;

import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.fetcher.ComicDaysFetcher;
import org.example.fetch_manga.service.fetcher.ComicWalkerFetcher;
import org.example.fetch_manga.service.fetcher.ComixToFetcher;
import org.example.fetch_manga.service.fetcher.CuuTruyenFetcher;
import org.example.fetch_manga.service.fetcher.HerosWebFetcher;
import org.example.fetch_manga.service.fetcher.MangarawjpFetcher;
import org.example.fetch_manga.service.fetcher.MangaFireIdFetcher;
import org.example.fetch_manga.service.fetcher.MangaFireUrlFetcher;
import org.example.fetch_manga.service.fetcher.MangaOneFetcher;
import org.example.fetch_manga.service.fetcher.MangaPillFetcher;
import org.example.fetch_manga.service.fetcher.MangaPlusFetcher;
import org.example.fetch_manga.service.fetcher.NicoMangaFetcher;
import org.example.fetch_manga.service.fetcher.PixivComicFetcher;
import org.example.fetch_manga.service.fetcher.ShonenJumpPlusFetcher;
import org.example.fetch_manga.service.fetcher.SundayWebryFetcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nơi duy nhất khai báo danh sách nguồn truyện của ứng dụng.
 *
 * <p>Muốn thêm một trang mới, chỉ cần viết thêm một lớp adapter rồi đăng ký một
 * dòng ở đây — Controller và View tự cập nhật theo, không phải sửa dòng nào.</p>
 */
public final class FetcherRegistry {

    private final Map<String, MangaFetcher> byId = new LinkedHashMap<>();

    public FetcherRegistry() {
        register(new MangarawjpFetcher());
        register(new MangaPlusFetcher());
        register(new ComicDaysFetcher());
        register(new MangaPillFetcher());
        register(new ComixToFetcher());
        register(new MangaOneFetcher());
        register(new CuuTruyenFetcher());
        register(new MangaFireUrlFetcher());
        register(new MangaFireIdFetcher());
        register(new NicoMangaFetcher());
        register(new ShonenJumpPlusFetcher());
        register(new HerosWebFetcher());
        register(new SundayWebryFetcher());
        register(new PixivComicFetcher());
        register(new ComicWalkerFetcher());
    }

    private void register(MangaFetcher fetcher) {
        MangaFetcher previous = byId.put(fetcher.getId(), fetcher);
        if (previous != null) {
            throw new IllegalStateException("Trùng id nguồn truyện: " + fetcher.getId());
        }
    }

    /** Toàn bộ nguồn theo đúng thứ tự đăng ký. */
    public List<MangaFetcher> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    /** Danh sách mô tả, dùng để đổ vào combo box của View. */
    public List<SourceDescriptor> descriptors() {
        List<SourceDescriptor> list = new ArrayList<>(byId.size());
        for (MangaFetcher f : byId.values()) list.add(f.getDescriptor());
        return list;
    }

    public Optional<MangaFetcher> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Nguồn đầu tiên khớp tên miền của link — dùng để tự chọn nguồn khi dán link. */
    public Optional<MangaFetcher> detect(String url) {
        for (MangaFetcher f : byId.values()) {
            if (f.getDescriptor().matchesUrl(url)) return Optional.of(f);
        }
        return Optional.empty();
    }

    public MangaFetcher first() {
        return byId.values().iterator().next();
    }

    public int size() {
        return byId.size();
    }
}
