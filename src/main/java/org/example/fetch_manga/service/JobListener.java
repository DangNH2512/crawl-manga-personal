package org.example.fetch_manga.service;

import org.example.fetch_manga.model.FetchOutcome;

/**
 * Kênh báo tiến độ từ tầng Service ngược lên tầng trên.
 *
 * <p>Nhờ interface này, Service không hề biết nó đang chạy dưới cửa sổ Swing hay
 * dưới console — đúng chiều phụ thuộc của mô hình MVC.</p>
 */
public interface JobListener {

    /** @param jobName tên công việc đang chạy, vd tên nguồn truyện hoặc "Tạo file .psd" */
    void onStarted(String jobName);

    void onLog(String line);

    void onFinished(FetchOutcome outcome);

    /** Listener rỗng, dùng khi không ai quan tâm tiến độ. */
    JobListener NOOP = new JobListener() {
        @Override public void onStarted(String jobName) { }
        @Override public void onLog(String line) { }
        @Override public void onFinished(FetchOutcome outcome) { }
    };
}
