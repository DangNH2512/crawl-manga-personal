package org.example.fetch_manga.model;

/**
 * Kết quả của một công việc chạy nền: thành công hay thất bại, thông điệp cho
 * người dùng và thời gian chạy. View chỉ cần đọc đối tượng này để cập nhật thanh
 * trạng thái.
 *
 * <p>Dùng chung cho cả việc tải chương lẫn việc tạo file .psd, nên chỗ này giữ
 * một cái tên công việc thay vì buộc vào một nguồn truyện cụ thể.</p>
 */
public final class FetchOutcome {

    private final String jobName;
    private final boolean success;
    private final String message;
    private final long durationMillis;
    private final Throwable error;

    private FetchOutcome(String jobName, boolean success, String message,
                         long durationMillis, Throwable error) {
        this.jobName = jobName == null ? "" : jobName;
        this.success = success;
        this.message = message;
        this.durationMillis = durationMillis;
        this.error = error;
    }

    public static FetchOutcome success(String jobName, long durationMillis) {
        return new FetchOutcome(jobName, true, "Xong", durationMillis, null);
    }

    public static FetchOutcome failure(String jobName, long durationMillis, Throwable error) {
        String msg = error.getMessage();
        if (msg == null || msg.isBlank()) msg = error.getClass().getSimpleName();
        return new FetchOutcome(jobName, false, msg, durationMillis, error);
    }

    public static FetchOutcome cancelled(String jobName, long durationMillis) {
        return new FetchOutcome(jobName, false, "Đã huỷ", durationMillis, null);
    }

    public String getJobName()          { return jobName; }
    public boolean isSuccess()          { return success; }
    public String getMessage()          { return message; }
    public long getDurationMillis()     { return durationMillis; }
    public Throwable getError()         { return error; }

    public double getDurationSeconds()  { return durationMillis / 1000.0; }

    @Override
    public String toString() {
        return (success ? "OK" : "LỖI") + " — " + message
                + String.format(" (%.1fs)", getDurationSeconds());
    }
}
