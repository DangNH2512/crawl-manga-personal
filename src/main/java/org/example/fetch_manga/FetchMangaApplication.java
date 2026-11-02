package org.example.fetch_manga;

import org.example.fetch_manga.controller.FetchController;
import org.example.fetch_manga.service.ConsoleBridge;
import org.example.fetch_manga.view.ConsoleView;
import org.example.fetch_manga.view.MainWindow;
import org.example.fetch_manga.view.UiTheme;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;

/**
 * Điểm khởi động của ứng dụng.
 *
 * <p>Nhiệm vụ duy nhất của lớp này là dựng {@link FetchController} rồi gắn vào
 * một View. Mặc định mở cửa sổ Swing; thêm tham số {@code --cli} (hoặc chạy trên
 * máy không có màn hình đồ hoạ) thì chuyển sang menu dòng lệnh.</p>
 *
 * <pre>
 *   View  ──gọi──▶  Controller  ──gọi──▶  Service (FetcherRegistry → *Fetcher → site.*)
 *     ▲                                        │
 *     └────────── JobListener / Model ─────────┘
 * </pre>
 */
public final class FetchMangaApplication {

    private FetchMangaApplication() { }

    public static void main(String[] args) {
        ConsoleBridge.install();

        FetchController controller = new FetchController();

        if (useConsole(args)) {
            new ConsoleView(controller).run();
            return;
        }

        SwingUtilities.invokeLater(() -> {
            UiTheme.apply();
            new MainWindow(controller).setVisible(true);
        });
    }

    private static boolean useConsole(String[] args) {
        boolean askedForCli = args != null
                && Arrays.asList(args).contains("--cli");
        return askedForCli || GraphicsEnvironment.isHeadless();
    }
}
