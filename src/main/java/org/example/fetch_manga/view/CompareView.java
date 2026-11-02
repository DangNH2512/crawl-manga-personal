package org.example.fetch_manga.view;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;

/**
 * Khung so sánh trước/sau kiểu các trang upscale: ảnh gốc bên trái, ảnh upscale bên
 * phải, kéo vạch giữa để dời ranh giới.
 *
 * <p>Ảnh gốc được kéo giãn đúng bằng kích thước ảnh upscale nên hai nửa khớp từng nét.
 * Lăn chuột để phóng to quanh con trỏ, kéo để di chuyển, nhấp đúp để vừa khung —
 * ảnh cả trang thu nhỏ vừa khung thì khó thấy khác biệt, phải phóng to mới rõ.</p>
 */
final class CompareView extends JComponent {

    private static final Color BACKGROUND = new Color(0x2B2B2B);
    private static final double MAX_ZOOM = 8.0;
    private static final int GRAB_DISTANCE = 8;

    private BufferedImage before;
    private BufferedImage after;
    private String message = "Chọn một ảnh rồi bấm Xem thử";

    /** Số điểm ảnh màn hình ứng với một điểm ảnh của ảnh upscale. */
    private double zoom = 1;
    private double originX;
    private double originY;
    /** Vị trí vạch chia, tính theo tỉ lệ chiều ngang khung. */
    private double split = 0.5;
    /** Còn đang ở chế độ vừa khung thì đổi cỡ cửa sổ sẽ tự căn lại. */
    private boolean autoFit = true;

    private boolean draggingSplit;
    private int lastX;
    private int lastY;

    CompareView() {
        setPreferredSize(new Dimension(640, 420));
        setFocusable(true);

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                draggingSplit = nearSplit(e.getX());
                lastX = e.getX();
                lastY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (after == null) return;
                if (draggingSplit) {
                    split = Math.max(0, Math.min(1, e.getX() / (double) Math.max(1, getWidth())));
                } else {
                    originX += e.getX() - lastX;
                    originY += e.getY() - lastY;
                    autoFit = false;
                }
                lastX = e.getX();
                lastY = e.getY();
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                if (after == null) {
                    setCursor(Cursor.getDefaultCursor());
                } else {
                    setCursor(Cursor.getPredefinedCursor(nearSplit(e.getX())
                            ? Cursor.E_RESIZE_CURSOR : Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && after != null) {
                    autoFit = true;
                    fit();
                    repaint();
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (after == null) return;
                double factor = Math.pow(1.2, -e.getPreciseWheelRotation());
                double newZoom = Math.max(fitZoom() / 2, Math.min(MAX_ZOOM, zoom * factor));
                // Giữ nguyên điểm ảnh nằm dưới con trỏ khi phóng to/thu nhỏ.
                originX = e.getX() - (e.getX() - originX) * newZoom / zoom;
                originY = e.getY() - (e.getY() - originY) * newZoom / zoom;
                zoom = newZoom;
                autoFit = false;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (autoFit && after != null) {
                    fit();
                    repaint();
                }
            }
        });
    }

    void setImages(BufferedImage before, BufferedImage after) {
        this.before = before;
        this.after = after;
        this.split = 0.5;
        this.autoFit = true;
        fit();
        repaint();
    }

    /** Xoá ảnh và hiện một dòng chữ ở giữa khung, vd khi đang chạy hoặc bị lỗi. */
    void showMessage(String text) {
        this.before = null;
        this.after = null;
        this.message = text;
        repaint();
    }

    private boolean nearSplit(int x) {
        return after != null && Math.abs(x - splitX()) <= GRAB_DISTANCE;
    }

    private int splitX() {
        return (int) Math.round(split * getWidth());
    }

    private double fitZoom() {
        if (after == null || getWidth() <= 0 || getHeight() <= 0) return 1;
        return Math.min(getWidth() / (double) after.getWidth(), getHeight() / (double) after.getHeight());
    }

    private void fit() {
        zoom = fitZoom();
        originX = (getWidth() - after.getWidth() * zoom) / 2;
        originY = (getHeight() - after.getHeight() * zoom) / 2;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(BACKGROUND);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (after == null) {
                paintCentered(g2, message);
                return;
            }

            int x = (int) Math.round(originX);
            int y = (int) Math.round(originY);
            int w = (int) Math.round(after.getWidth() * zoom);
            int h = (int) Math.round(after.getHeight() * zoom);
            int sx = splitX();

            // Hai nửa cùng nội suy song tuyến như trình duyệt, để so sánh công bằng: ảnh gốc
            // không bị vẽ vỡ ô vuông cho có vẻ tệ hơn thực tế.
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            Graphics2D right = (Graphics2D) g2.create();
            right.clipRect(sx, 0, getWidth() - sx, getHeight());
            right.drawImage(after, x, y, w, h, null);
            right.dispose();

            if (before != null) {
                Graphics2D left = (Graphics2D) g2.create();
                left.clipRect(0, 0, sx, getHeight());
                left.drawImage(before, x, y, w, h, null);
                left.dispose();
            }

            paintDivider(g2, sx);
            paintBadge(g2, "Trước", 10, false);
            paintBadge(g2, "Sau (AI)", getWidth() - 10, true);
            paintZoom(g2);
        } finally {
            g2.dispose();
        }
    }

    private void paintDivider(Graphics2D g2, int sx) {
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2f));
        g2.drawLine(sx, 0, sx, getHeight());

        int r = 14;
        int cy = getHeight() / 2;
        g2.setColor(UiTheme.ACCENT);
        g2.fillOval(sx - r, cy - r, r * 2, r * 2);
        g2.setColor(Color.WHITE);
        g2.drawOval(sx - r, cy - r, r * 2, r * 2);
        // Hai mũi tên nhỏ gợi ý kéo sang trái/phải.
        int[] lx = {sx - 3, sx - 9, sx - 3};
        int[] rx = {sx + 3, sx + 9, sx + 3};
        int[] ys = {cy - 5, cy, cy + 5};
        g2.fillPolygon(lx, ys, 3);
        g2.fillPolygon(rx, ys, 3);
    }

    private void paintBadge(Graphics2D g2, String text, int anchorX, boolean alignRight) {
        g2.setFont(UiTheme.uiFont(Font.BOLD, 12));
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(text) + 16;
        int h = fm.getHeight() + 6;
        int x = alignRight ? anchorX - w : anchorX;
        g2.setColor(new Color(0, 0, 0, 150));
        g2.fillRoundRect(x, 10, w, h, h, h);
        g2.setColor(Color.WHITE);
        g2.drawString(text, x + 8, 10 + 3 + fm.getAscent());
    }

    private void paintZoom(Graphics2D g2) {
        String text = Math.round(zoom * 100) + "%  ·  lăn chuột để phóng to, nhấp đúp để vừa khung";
        g2.setFont(UiTheme.uiFont(Font.PLAIN, 11));
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(text) + 14;
        int h = fm.getHeight() + 4;
        int y = getHeight() - h - 8;
        g2.setColor(new Color(0, 0, 0, 150));
        g2.fillRoundRect(10, y, w, h, h, h);
        g2.setColor(new Color(0xE6E6E6));
        g2.drawString(text, 17, y + 2 + fm.getAscent());
    }

    private void paintCentered(Graphics2D g2, String text) {
        g2.setFont(UiTheme.uiFont(Font.PLAIN, 14));
        g2.setColor(new Color(0xBBBBBB));
        FontMetrics fm = g2.getFontMetrics();
        String[] lines = text.split("\n");
        int total = lines.length * fm.getHeight();
        int y = (getHeight() - total) / 2 + fm.getAscent();
        for (String line : lines) {
            g2.drawString(line, (getWidth() - fm.stringWidth(line)) / 2, y);
            y += fm.getHeight();
        }
    }
}
