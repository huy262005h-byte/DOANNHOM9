package com.mycompany.doancs.common;

import com.mycompany.doancs.model.FileVersionInfo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cấu trúc gói tin (Packet) giao tiếp giữa Client và Server.
 * Hỗ trợ chuyển đổi tuần tự (Serializable) để truyền qua luồng đối tượng Socket.
 * Đã nâng cấp hỗ trợ: Nén động GZIP, Truyền tiếp ngắt quãng (Offset), và Lịch sử phiên bản (Versioning).
 */
public class Packet implements Serializable {
    private static final long serialVersionUID = 3L;

    private CommandType command;          // Lệnh thực hiện
    private String username;              // Tên đăng nhập người dùng
    private String password;              // Mật khẩu (đã băm hoặc truyền xác thực)
    private String authToken;             // Token phiên làm việc bảo mật
    private String fileName;              // Tên tệp hoặc đường dẫn tương đối
    private long fileSize;                // Kích thước tệp (bytes)
    private long lastModified;            // Thời gian sửa đổi (epoch millis)
    private String checksum;              // Mã băm SHA-256
    private List<FileMetadata> fileList;  // Danh sách tệp gửi kèm
    private String message;               // Thông điệp phản hồi hoặc lỗi
    private boolean success;              // Trạng thái thành công hay thất bại

    // Nâng cấp: Nén động GZIP & Truyền tiếp ngắt quãng
    private boolean compressed = false;   // Cờ báo dòng dữ liệu có được nén GZIP
    private long originalSize = 0;        // Kích thước gốc trước khi nén
    private long offset = 0;              // Vị trí byte bắt đầu truyền (Resumable)

    // Nâng cấp: Phiên bản tệp & Thùng rác
    private int versionId;                // ID phiên bản cần khôi phục
    private List<FileVersionInfo> fileVersions; // Danh sách phiên bản
    private List<Map<String, String>> recycleItems; // Danh sách tệp trong thùng rác

    public Packet() {
        this.fileList = new ArrayList<>();
        this.fileVersions = new ArrayList<>();
        this.success = true;
    }

    public Packet(CommandType command) {
        this();
        this.command = command;
    }

    // Factory methods
    public static Packet createPing() {
        return new Packet(CommandType.PING);
    }

    public static Packet createPong() {
        return new Packet(CommandType.PONG);
    }

    public static Packet createLoginRequest(String username, String password) {
        Packet p = new Packet(CommandType.AUTH_LOGIN);
        p.setUsername(username);
        p.setPassword(password);
        return p;
    }

    public static Packet createRegisterRequest(String username, String password) {
        Packet p = new Packet(CommandType.AUTH_REGISTER);
        p.setUsername(username);
        p.setPassword(password);
        return p;
    }

    public static Packet createGetFileListRequest() {
        return createGetFileListRequest(null);
    }

    public static Packet createGetFileListRequest(String authToken) {
        Packet p = new Packet(CommandType.GET_FILE_LIST);
        p.setAuthToken(authToken);
        return p;
    }

    public static Packet createFileListResponse(List<FileMetadata> list) {
        Packet p = new Packet(CommandType.FILE_LIST_RESPONSE);
        p.setFileList(list);
        return p;
    }

    public static Packet createUploadRequest(String fileName, long fileSize, long lastModified, String sha256) {
        return createUploadRequest(fileName, fileSize, lastModified, sha256, null);
    }

    public static Packet createUploadRequest(String fileName, long fileSize, long lastModified, String sha256, String authToken) {
        Packet p = new Packet(CommandType.UPLOAD);
        p.setFileName(fileName);
        p.setFileSize(fileSize);
        p.setLastModified(lastModified);
        p.setChecksum(sha256);
        p.setAuthToken(authToken);
        return p;
    }

    public static Packet createDownloadRequest(String fileName) {
        return createDownloadRequest(fileName, null);
    }

    public static Packet createDownloadRequest(String fileName, String authToken) {
        Packet p = new Packet(CommandType.DOWNLOAD);
        p.setFileName(fileName);
        p.setAuthToken(authToken);
        return p;
    }

    public static Packet createDeleteRequest(String fileName) {
        return createDeleteRequest(fileName, null);
    }

    public static Packet createDeleteRequest(String fileName, String authToken) {
        Packet p = new Packet(CommandType.DELETE);
        p.setFileName(fileName);
        p.setAuthToken(authToken);
        return p;
    }

    public static Packet createServerNotifyChange(String fileName, String action) {
        Packet p = new Packet(CommandType.SERVER_NOTIFY_CHANGE);
        p.setFileName(fileName);
        p.setMessage(action);
        return p;
    }

    public static Packet createSubscribeNotifications(String authToken) {
        Packet p = new Packet(CommandType.SUBSCRIBE_NOTIFICATIONS);
        p.setAuthToken(authToken);
        return p;
    }

    public static Packet createGetFileVersionsRequest(String fileName, String authToken) {
        Packet p = new Packet(CommandType.GET_FILE_VERSIONS);
        p.setFileName(fileName);
        p.setAuthToken(authToken);
        return p;
    }

    public static Packet createFileVersionsResponse(List<FileVersionInfo> versions) {
        Packet p = new Packet(CommandType.FILE_VERSIONS_RESPONSE);
        p.setFileVersions(versions);
        return p;
    }

    public static Packet createRestoreFileVersionRequest(int versionId, String fileName, String authToken) {
        Packet p = new Packet(CommandType.RESTORE_FILE_VERSION);
        p.setVersionId(versionId);
        p.setFileName(fileName);
        p.setAuthToken(authToken);
        return p;
    }

    public static Packet createCheckOffsetRequest(String fileName, String authToken) {
        Packet p = new Packet(CommandType.CHECK_OFFSET);
        p.setFileName(fileName);
        p.setAuthToken(authToken);
        return p;
    }

    public static Packet createOffsetResponse(long offset) {
        Packet p = new Packet(CommandType.OFFSET_RESPONSE);
        p.setOffset(offset);
        return p;
    }

    public static Packet createOkResponse(String message) {
        Packet p = new Packet(CommandType.RESPONSE_OK);
        p.setMessage(message);
        p.setSuccess(true);
        return p;
    }

    public static Packet createErrorResponse(String errorMessage) {
        Packet p = new Packet(CommandType.RESPONSE_ERROR);
        p.setMessage(errorMessage);
        p.setSuccess(false);
        return p;
    }

    public static Packet createDisconnect() {
        return new Packet(CommandType.DISCONNECT);
    }

    // Getters and Setters
    public CommandType getCommand() { return command; }
    public void setCommand(CommandType command) { this.command = command; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public List<FileMetadata> getFileList() { return fileList; }
    public void setFileList(List<FileMetadata> fileList) { this.fileList = fileList; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public boolean isCompressed() { return compressed; }
    public void setCompressed(boolean compressed) { this.compressed = compressed; }

    public long getOriginalSize() { return originalSize; }
    public void setOriginalSize(long originalSize) { this.originalSize = originalSize; }

    public long getOffset() { return offset; }
    public void setOffset(long offset) { this.offset = offset; }

    public int getVersionId() { return versionId; }
    public void setVersionId(int versionId) { this.versionId = versionId; }

    public List<FileVersionInfo> getFileVersions() { return fileVersions; }
    public void setFileVersions(List<FileVersionInfo> fileVersions) { this.fileVersions = fileVersions; }

    public List<Map<String, String>> getRecycleItems() { return recycleItems; }
    public void setRecycleItems(List<Map<String, String>> recycleItems) { this.recycleItems = recycleItems; }

    @Override
    public String toString() {
        return "Packet{" +
                "command=" + command +
                ", user='" + username + '\'' +
                ", file='" + fileName + '\'' +
                ", size=" + fileSize +
                ", compressed=" + compressed +
                ", offset=" + offset +
                ", success=" + success +
                '}';
    }
}
