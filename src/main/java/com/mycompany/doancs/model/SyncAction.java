package com.mycompany.doancs.model;

/**
 * Hành động cần thực hiện đối với một tệp tin khi đồng bộ.
 */
public enum SyncAction {
    UP_TO_DATE("Đã đồng bộ", "Tệp hoàn toàn giống nhau giữa Client và Server (bỏ qua)", "#2e7d32"),
    UPLOAD("Tải lên Server", "Tệp mới hoặc phiên bản Client mới hơn -> Đẩy lên Server", "#1976d2"),
    DOWNLOAD("Tải về Client", "Tệp mới trên Server hoặc phiên bản Server mới hơn -> Tải về Client", "#f57c00"),
    CONFLICT("Xung đột", "Cả 2 bên đều có chỉnh sửa khác nhau", "#d32f2f"),
    SKIP("Bỏ qua", "Không cần xử lý theo chế độ đồng bộ đã chọn", "#757575");

    private final String title;
    private final String description;
    private final String colorHex;

    SyncAction(String title, String description, String colorHex) {
        this.title = title;
        this.description = description;
        this.colorHex = colorHex;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getColorHex() {
        return colorHex;
    }
}
