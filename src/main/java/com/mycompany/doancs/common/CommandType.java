package com.mycompany.doancs.common;

/**
 * Các loại lệnh (Command) định nghĩa trong gói tin Packet
 * truyền giữa Client và Server.
 */
public enum CommandType {
    PING,                    // Kiểm tra kết nối
    PONG,                    // Phản hồi kết nối
    AUTH_LOGIN,              // Đăng nhập tài khoản
    AUTH_REGISTER,           // Đăng ký tài khoản
    GET_FILE_LIST,           // Yêu cầu danh sách siêu dữ liệu tệp
    FILE_LIST_RESPONSE,      // Trả về danh sách tệp trên Server
    UPLOAD,                  // Yêu cầu tải tệp lên
    READY_TO_RECEIVE,        // Máy chủ sẵn sàng nhận dòng byte
    DOWNLOAD,                // Yêu cầu tải tệp về
    READY_TO_SEND,           // Máy chủ sẵn sàng gửi dòng byte
    DELETE,                  // Yêu cầu xóa tệp
    CONFLICT_RESOLVE,        // Giải quyết xung đột tệp
    SERVER_NOTIFY_CHANGE,    // Máy chủ thông báo thời gian thực có tệp thay đổi cho các Client
    SUBSCRIBE_NOTIFICATIONS, // Client đăng ký kênh nhận thông báo đẩy thời gian thực từ Máy chủ

    // Nâng cấp: Lịch sử phiên bản (Versioning) & Khôi phục (Rollback)
    GET_FILE_VERSIONS,       // Lấy danh sách các phiên bản cũ của tệp
    FILE_VERSIONS_RESPONSE,  // Trả về danh sách phiên bản tệp
    RESTORE_FILE_VERSION,    // Khôi phục tệp về phiên bản cũ

    // Nâng cấp: Thùng rác (Recycle Bin / Soft Delete)
    GET_RECYCLE_BIN,         // Lấy danh sách tệp trong thùng rác
    RECYCLE_BIN_RESPONSE,    // Trả về danh sách tệp thùng rác
    RESTORE_RECYCLE_FILE,    // Khôi phục tệp từ thùng rác

    // Nâng cấp: Truyền tiếp tệp bị ngắt quãng (Resumable Transfer)
    CHECK_OFFSET,            // Kiểm tra dung lượng offset tệp đã nhận dở
    OFFSET_RESPONSE,         // Trả về offset hiện có trên bên nhận

    RESPONSE_OK,             // Phản hồi thành công
    RESPONSE_ERROR,          // Phản hồi lỗi
    DISCONNECT               // Ngắt kết nối
}
