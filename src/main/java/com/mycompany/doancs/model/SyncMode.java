package com.mycompany.doancs.model;

/**
 * Các chế độ đồng bộ hỗ trợ.
 */
public enum SyncMode {
    TWO_WAY("Đồng bộ 2 chiều thông minh (Bidirectional)", 
            "Cập nhật tệp mới nhất cho cả Client và Server; tệp mới bên nào sẽ thêm vào bên kia."),
    UPLOAD_ONLY("Chỉ tải lên: Client -> Server (Upload Only)", 
            "Chỉ đẩy các tệp mới và tệp sửa đổi từ Client lên Server."),
    DOWNLOAD_ONLY("Chỉ tải về: Server -> Client (Download Only)", 
            "Chỉ kéo các tệp mới và tệp sửa đổi từ Server về Client.");

    private final String displayName;
    private final String description;

    SyncMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
