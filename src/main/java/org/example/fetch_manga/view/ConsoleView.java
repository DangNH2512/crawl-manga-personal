package org.example.fetch_manga.view;

import org.example.fetch_manga.controller.FetchController;
import org.example.fetch_manga.model.FetchOutcome;
import org.example.fetch_manga.model.FieldSpec;
import org.example.fetch_manga.model.FieldType;
import org.example.fetch_manga.model.PinnedFolder;
import org.example.fetch_manga.model.SourceDescriptor;
import org.example.fetch_manga.service.JobListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

/**
 * Bản giao diện dòng lệnh, giữ lại cho ai quen chạy trong terminal hoặc khi máy
 * không có màn hình đồ hoạ.
 *
 * <p>Lớp này dùng chung {@link FetchController} và cùng danh sách nguồn với cửa
 * sổ Swing — minh hoạ rõ lợi ích của MVC: đổi View không phải sửa Model/Service.</p>
 */
public class ConsoleView implements JobListener {

    private final FetchController controller;
    private final Scanner scanner = new Scanner(System.in);

    private CountDownLatch done;

    public ConsoleView(FetchController controller) {
        this.controller = controller;
    }

    public void run() {
        List<SourceDescriptor> sources = controller.getSources();

        while (true) {
            printMenu(sources);
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("q") || input.equals("0")) {
                controller.shutdown();
                System.out.println("Thoát chương trình.");
                return;
            }

            int choice;
            try {
                choice = Integer.parseInt(input);
            } catch (NumberFormatException e) {
                System.out.println("Vui lòng nhập số hợp lệ.");
                continue;
            }
            if (choice < 1 || choice > sources.size()) {
                System.out.println("Lựa chọn không hợp lệ.");
                continue;
            }

            runSource(sources.get(choice - 1));

            System.out.println("\nNhấn Enter để quay lại menu...");
            scanner.nextLine();
        }
    }

    private void printMenu(List<SourceDescriptor> sources) {
        System.out.println("\n===== FETCH MANGA =====");
        System.out.println("Thư mục gốc: " + controller.getSettings().getBasePath());
        for (int i = 0; i < sources.size(); i++) {
            System.out.printf("%2d. %s%n", i + 1, sources.get(i).getDisplayName());
        }
        System.out.println(" 0. Thoát");
        System.out.print("Chọn nguồn: ");
    }

    private void runSource(SourceDescriptor source) {
        System.out.println("\n=== " + source.getDisplayName() + " ===");
        if (!source.getNote().isEmpty()) System.out.println(source.getNote());
        if (!source.getExample().isEmpty()) System.out.println("Ví dụ: " + source.getExample());

        String url = "";
        if (source.needsUrl()) {
            System.out.print("Link chương: ");
            url = scanner.nextLine().trim();
        }

        String folder = askFolder();
        Map<String, String> extras = askExtras(source);

        done = new CountDownLatch(1);
        try {
            controller.start(source, url, folder, extras, this);
            done.await();
        } catch (IllegalArgumentException e) {
            System.out.println("!! " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("!! Bị ngắt giữa chừng.");
        }
    }

    /** Cho chọn nhanh trong danh sách thư mục đã ghim, hoặc gõ đường dẫn mới. */
    private String askFolder() {
        List<PinnedFolder> pinned = controller.getSettings().getPinnedFolders();
        System.out.println("Thư mục đã ghim:");
        System.out.println("  0. (không chọn - dùng quy ước mặc định của nguồn)");
        for (int i = 0; i < pinned.size(); i++) {
            System.out.printf("  %d. %s%n", i + 1, pinned.get(i).getPath());
        }
        System.out.print("Chọn số, hoặc gõ thẳng một đường dẫn để ghim mới: ");
        String answer = scanner.nextLine().trim();

        String root = "";
        if (answer.isEmpty() || answer.equals("0")) {
            root = "";
        } else {
            try {
                int index = Integer.parseInt(answer);
                if (index >= 1 && index <= pinned.size()) root = pinned.get(index - 1).getPath();
            } catch (NumberFormatException e) {
                root = answer;
                controller.pinFolder(root);
                System.out.println("Đã ghim: " + root);
            }
        }

        if (root.isEmpty()) return "";

        System.out.print("Thư mục con (vd: Chap 12, Enter để bỏ qua): ");
        return controller.resolveFolder(root, scanner.nextLine().trim());
    }

    private Map<String, String> askExtras(SourceDescriptor source) {
        Map<String, String> extras = new LinkedHashMap<>();
        for (FieldSpec field : source.getFields()) {
            String saved = controller.getSettings()
                    .remembered(source.getId(), field.getKey(), field.getDefaultValue());

            StringBuilder prompt = new StringBuilder(field.getLabel());
            if (field.getType() == FieldType.CHOICE) {
                prompt.append(' ').append(field.getOptions());
            }
            if (!saved.isEmpty()) {
                prompt.append(" [Enter = giữ giá trị đã lưu]");
            } else if (!field.isRequired()) {
                prompt.append(" (Enter để bỏ qua)");
            }
            prompt.append(": ");

            System.out.print(prompt);
            String value = scanner.nextLine().trim();
            extras.put(field.getKey(), value.isEmpty() ? saved : value);
        }
        return extras;
    }

    // -- JobListener ----------------------------------------------------------

    @Override
    public void onStarted(String jobName) {
        System.out.println("--- Bắt đầu: " + jobName + " ---");
    }

    @Override
    public void onLog(String line) {
        // Scraper đã in thẳng ra console rồi, không cần in lại.
    }

    @Override
    public void onFinished(FetchOutcome outcome) {
        System.out.println(outcome.isSuccess()
                ? String.format("Hoàn tất sau %.1fs.", outcome.getDurationSeconds())
                : "Lỗi: " + outcome.getMessage());
        if (done != null) done.countDown();
    }
}
