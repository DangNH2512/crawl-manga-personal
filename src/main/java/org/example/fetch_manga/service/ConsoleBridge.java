package org.example.fetch_manga.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Chuyển hướng {@code System.out} / {@code System.err} sang một nơi nhận log do
 * người gọi chỉ định, đồng thời vẫn ghi ra console gốc.
 *
 * <p>Các lớp scraper trong {@code service.site} in tiến độ bằng
 * {@code System.out.println}. Thay vì sửa gần 4000 dòng mã đã chạy ổn định, lớp
 * này bắc cầu để cửa sổ Swing hiển thị đúng những dòng đó trong ô Nhật ký.</p>
 */
public final class ConsoleBridge {

    private static final AtomicReference<Consumer<String>> SINK = new AtomicReference<>();

    private static boolean installed;

    private ConsoleBridge() { }

    /** Cài đặt cầu nối. Gọi nhiều lần cũng chỉ có tác dụng một lần. */
    public static synchronized void install() {
        if (installed) return;
        System.setOut(wrap(System.out));
        System.setErr(wrap(System.err));
        installed = true;
    }

    /** Đặt nơi nhận log; truyền {@code null} để tạm ngừng chuyển tiếp. */
    public static void setSink(Consumer<String> sink) {
        SINK.set(sink);
    }

    private static PrintStream wrap(PrintStream original) {
        return new PrintStream(new TeeStream(original), true, StandardCharsets.UTF_8);
    }

    private static void emit(String line) {
        Consumer<String> sink = SINK.get();
        if (sink != null) {
            try {
                sink.accept(line);
            } catch (RuntimeException ignored) {
                // Không để lỗi hiển thị làm hỏng luồng tải.
            }
        }
    }

    /**
     * Ghi song song ra console gốc và gom byte thành từng dòng hoàn chỉnh trước
     * khi giải mã UTF-8 — nhờ vậy ký tự tiếng Việt hay emoji bị cắt giữa hai lần
     * ghi vẫn hiển thị đúng.
     */
    private static final class TeeStream extends OutputStream {

        private final PrintStream original;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);

        private TeeStream(PrintStream original) {
            this.original = original;
        }

        @Override
        public synchronized void write(int b) {
            original.write(b);
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                buffer.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            original.flush();
        }

        private void flushLine() {
            String line = buffer.toString(StandardCharsets.UTF_8);
            buffer.reset();
            emit(line);
        }
    }
}
