package com.mycompany.doancs.server;

import com.mycompany.doancs.common.CommandType;
import com.mycompany.doancs.common.FileMetadata;
import com.mycompany.doancs.common.Packet;
import com.mycompany.doancs.model.ClientSessionInfo;
import com.mycompany.doancs.server.db.DatabaseManager;
import com.mycompany.doancs.util.FileUtils;

import java.io.*;
import java.net.Socket;
import java.util.*;

/**
 * Client Handler (Phía Máy Chủ):
 * Cơ chế đa luồng xử lý riêng biệt cho từng client kết nối vào để server
 * có thể phục vụ nhiều máy trạm cùng lúc mà không bị nghẽn.
 * Hỗ trợ xác thực tài khoản, đồng bộ tệp và kênh thông báo đẩy thời gian thực.
 */
public class ClientHandler implements Runnable {
    private final Socket socket;
    private final ServerCore server;
    private final StorageManager storageManager;
    private final ClientSessionInfo sessionInfo;

    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile boolean running = true;
    private String authenticatedUser = null;
    private String sessionToken = null;
    private boolean isNotificationChannel = false;

    public ClientHandler(Socket socket, ServerCore server, StorageManager storageManager) {
        this.socket = socket;
        this.server = server;
        this.storageManager = storageManager;

        String clientIp = socket.getInetAddress().getHostAddress();
        int clientPort = socket.getPort();
        String clientId = clientIp + ":" + clientPort;
        this.sessionInfo = new ClientSessionInfo(clientId, clientIp, clientPort);
    }

    public ClientSessionInfo getSessionInfo() {
        return sessionInfo;
    }

    public String getAuthenticatedUser() {
        return authenticatedUser;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public boolean isRunning() {
        return running && socket != null && !socket.isClosed();
    }

    /**
     * Không gian lưu trữ chung trên Máy Chủ (Shared Repository).
     * Toàn bộ các Client kết nối đều đồng bộ và chia sẻ dữ liệu vào thư mục chung của Server.
     */
    private StorageManager getEffectiveStorage() {
        return storageManager;
    }

    public synchronized boolean sendPacket(Packet packet) {
        try {
            if (out != null && socket != null && !socket.isClosed()) {
                out.writeObject(packet);
                out.flush();
                return true;
            }
        } catch (IOException e) {
            // Lỗi khi gửi gói tin qua kênh đẩy
        }
        return false;
    }

    @Override
    public void run() {
        try {
            // Khởi tạo luồng Object tuần tự hóa
            out = new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            out.flush();
            in = new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));

            server.notifyClientConnected(sessionInfo);
            server.log(ServerEventListener.LogLevel.INFO, "Client đã kết nối: " + sessionInfo.getClientId());

            while (running && !socket.isClosed()) {
                Packet requestPacket;
                try {
                    requestPacket = (Packet) in.readObject();
                } catch (EOFException e) {
                    break; // Client đóng kết nối bình thường
                }

                if (requestPacket == null) continue;

                sessionInfo.setLastActiveTime(System.currentTimeMillis());

                // Xác thực token nếu có đính kèm
                if (authenticatedUser == null && requestPacket.getAuthToken() != null && server.getDatabaseManager() != null) {
                    String user = server.getDatabaseManager().validateToken(requestPacket.getAuthToken());
                    if (user != null) {
                        this.authenticatedUser = user;
                        this.sessionInfo.setUsername(user);
                    }
                }

                switch (requestPacket.getCommand()) {
                    case PING:
                        handlePing();
                        break;
                    case AUTH_LOGIN:
                        handleLogin(requestPacket);
                        break;
                    case AUTH_REGISTER:
                        handleRegister(requestPacket);
                        break;
                    case SUBSCRIBE_NOTIFICATIONS:
                        handleSubscribeNotifications(requestPacket);
                        break;
                    case GET_FILE_LIST:
                        handleGetFileList();
                        break;
                    case UPLOAD:
                        handleUpload(requestPacket);
                        break;
                    case DOWNLOAD:
                        handleDownload(requestPacket);
                        break;
                    case DELETE:
                        handleDelete(requestPacket);
                        break;
                    case CONFLICT_RESOLVE:
                        handleConflictResolve(requestPacket);
                        break;
                    case GET_FILE_VERSIONS:
                        handleGetFileVersions(requestPacket);
                        break;
                    case RESTORE_FILE_VERSION:
                        handleRestoreFileVersion(requestPacket);
                        break;
                    case CHECK_OFFSET:
                        handleCheckOffset(requestPacket);
                        break;
                    case GET_RECYCLE_BIN:
                        handleGetRecycleBin(requestPacket);
                        break;
                    case RESTORE_RECYCLE_FILE:
                        handleRestoreRecycleFile(requestPacket);
                        break;
                    case DISCONNECT:
                        running = false;
                        break;
                    default:
                        server.log(ServerEventListener.LogLevel.WARN, "Lệnh không xác định từ " + sessionInfo.getClientId() + ": " + requestPacket.getCommand());
                        out.writeObject(Packet.createErrorResponse("Lệnh không được hỗ trợ"));
                        out.flush();
                        break;
                }
            }
        } catch (Exception e) {
            if (running) {
                server.log(ServerEventListener.LogLevel.WARN, "Ngắt kết nối với " + sessionInfo.getClientId() + ": " + e.getMessage());
            }
        } finally {
            close();
        }
    }

    private void handlePing() throws IOException {
        out.writeObject(Packet.createPong());
        out.flush();
    }

    private void handleLogin(Packet req) throws IOException {
        String username = req.getUsername();
        String password = req.getPassword();
        String clientIp = socket.getInetAddress().getHostAddress();

        DatabaseManager db = server.getDatabaseManager();
        if (db == null) {
            out.writeObject(Packet.createErrorResponse("Cơ sở dữ liệu máy chủ chưa sẵn sàng!"));
            out.flush();
            return;
        }

        String token = db.authenticateUser(username, password, clientIp);
        if (token != null) {
            this.authenticatedUser = username.trim().toLowerCase();
            this.sessionToken = token;
            this.sessionInfo.setUsername(this.authenticatedUser);
            this.sessionInfo.setCurrentStatus("Đã đăng nhập: @" + this.authenticatedUser);

            Packet ok = Packet.createOkResponse("Đăng nhập thành công!");
            ok.setAuthToken(token);
            ok.setUsername(this.authenticatedUser);
            out.writeObject(ok);
            out.flush();

            server.log(ServerEventListener.LogLevel.SUCCESS, "[" + sessionInfo.getClientId() + "] Đăng nhập thành công: @" + this.authenticatedUser);
            server.notifyStatsUpdated();
        } else {
            out.writeObject(Packet.createErrorResponse("Tên đăng nhập hoặc mật khẩu không chính xác!"));
            out.flush();
            server.log(ServerEventListener.LogLevel.WARN, "[" + sessionInfo.getClientId() + "] Đăng nhập thất bại cho tên: " + username);
        }
    }

    private void handleRegister(Packet req) throws IOException {
        String username = req.getUsername();
        String password = req.getPassword();

        DatabaseManager db = server.getDatabaseManager();
        if (db == null) {
            out.writeObject(Packet.createErrorResponse("Cơ sở dữ liệu máy chủ chưa sẵn sàng!"));
            out.flush();
            return;
        }

        if (username == null || username.trim().length() < 3 || password == null || password.trim().length() < 4) {
            out.writeObject(Packet.createErrorResponse("Tên đăng nhập tối thiểu 3 ký tự, mật khẩu tối thiểu 4 ký tự!"));
            out.flush();
            return;
        }

        boolean ok = db.registerUser(username, password);
        if (ok) {
            out.writeObject(Packet.createOkResponse("Đăng ký tài khoản @" + username + " thành công! Vui lòng đăng nhập."));
            out.flush();
            server.log(ServerEventListener.LogLevel.SUCCESS, "[" + sessionInfo.getClientId() + "] Đăng ký tài khoản mới: @" + username);
            server.notifyStatsUpdated();
        } else {
            out.writeObject(Packet.createErrorResponse("Tên tài khoản @" + username + " đã tồn tại trong hệ thống!"));
            out.flush();
        }
    }

    private void handleSubscribeNotifications(Packet req) throws IOException {
        String token = req.getAuthToken();
        DatabaseManager db = server.getDatabaseManager();
        String user = (db != null && token != null) ? db.validateToken(token) : null;
        if (user == null && req.getUsername() != null) user = req.getUsername();
        if (user == null) user = (this.authenticatedUser != null) ? this.authenticatedUser : "general";

        this.authenticatedUser = user;
        this.sessionToken = token;
        this.isNotificationChannel = true;
        this.sessionInfo.setUsername(user);
        this.sessionInfo.setCurrentStatus("Kênh Push Notification (@" + user + ")");

        server.registerNotificationChannel(user, this);
        out.writeObject(Packet.createOkResponse("Đã đăng ký kênh nhận thông báo đẩy thời gian thực"));
        out.flush();
    }

    private void handleGetFileList() throws IOException {
        sessionInfo.setCurrentStatus("Đang quét danh sách tệp");
        server.log(ServerEventListener.LogLevel.INFO, "[" + sessionInfo.getClientId() + "] Yêu cầu danh sách siêu dữ liệu tệp (GET_FILE_LIST)...");

        StorageManager sm = getEffectiveStorage();
        List<FileMetadata> fileList = sm.scanFiles();

        Packet response = Packet.createFileListResponse(fileList);
        if (server.getDatabaseManager() != null) {
            response.setRecycleItems(server.getDatabaseManager().getAllRecycleBin("admin"));
        }
        out.writeObject(response);
        out.flush();

        sessionInfo.setCurrentStatus("Đã gửi danh sách (" + fileList.size() + " tệp)");
        server.log(ServerEventListener.LogLevel.SUCCESS, "[" + sessionInfo.getClientId() + "] Đã gửi danh mục " + fileList.size() + " tệp.");
        server.notifyStatsUpdated();
    }

    private void handleUpload(Packet uploadRequest) throws IOException {
        String relativePath = uploadRequest.getFileName();
        long fileSize = uploadRequest.getFileSize();
        long lastModified = uploadRequest.getLastModified();
        String sha256 = uploadRequest.getChecksum();
        boolean isCompressed = uploadRequest.isCompressed();
        long originalSize = uploadRequest.getOriginalSize();
        long offset = uploadRequest.getOffset();

        sessionInfo.setCurrentStatus("Đang nhận tệp: " + relativePath);

        if (!FileUtils.isPathSafe(relativePath)) {
            out.writeObject(Packet.createErrorResponse("Đường dẫn tệp không an toàn!"));
            out.flush();
            server.log(ServerEventListener.LogLevel.ERROR, "[" + sessionInfo.getClientId() + "] Từ chối upload đường dẫn không hợp lệ: " + relativePath);
            return;
        }

        // Báo cho client sẵn sàng nhận dòng byte
        Packet readyPacket = new Packet(CommandType.READY_TO_RECEIVE);
        readyPacket.setMessage("Sẵn sàng nhận dữ liệu tệp");
        out.writeObject(readyPacket);
        out.flush();

        // Nhận dữ liệu tệp theo từng khối byte (Chunking 64KB) vào thư mục riêng của Client
        StorageManager sm = getEffectiveStorage();
        DatabaseManager db = server.getDatabaseManager();
        String user = (authenticatedUser != null) ? authenticatedUser : "general";

        // Tự động tạo bản sao lưu phiên bản cũ nếu tệp đã tồn tại (File Versioning)
        if (db != null) {
            int currentVer = db.getCurrentVersion(user, relativePath);
            if (currentVer > 0) {
                sm.backupFileVersion(relativePath, user, currentVer, db);
                server.log(ServerEventListener.LogLevel.INFO, "[Versioning] @" + user + ": Đã lưu bản sao phiên bản v" + currentVer + " của '" + relativePath + "'");
            }
        }

        try {
            boolean success = sm.saveFileChunked(relativePath, in, fileSize, lastModified, sha256, isCompressed, originalSize, offset);
            if (success) {
                out.writeObject(Packet.createOkResponse("Tải lên tệp thành công"));
                out.flush();

                long actualBytesTransferred = fileSize;
                sessionInfo.recordTransfer(actualBytesTransferred);
                sessionInfo.setCurrentStatus("Đã nhận " + relativePath);
                server.notifyFileReceived(sessionInfo.getClientId(), relativePath, actualBytesTransferred);

                if (isCompressed && originalSize > 0) {
                    int savingPct = (int) ((originalSize - fileSize) * 100 / originalSize);
                    server.log(ServerEventListener.LogLevel.SUCCESS, "[" + sessionInfo.getClientId() + "] [Nén GZIP] Đã nhận: " + relativePath +
                            " (" + FileUtils.formatBytes(originalSize) + " -> " + FileUtils.formatBytes(fileSize) + ", Tiết kiệm " + savingPct + "% băng thông) [SHA-256 OK]");
                } else {
                    server.log(ServerEventListener.LogLevel.SUCCESS, "[" + sessionInfo.getClientId() + "] Đã nhận tệp: " + relativePath + " (" + FileUtils.formatBytes(fileSize) + ") [SHA-256 OK]");
                }

                // Cập nhật Metadata vào Database SQLite
                if (db != null) {
                    db.upsertFileMetadata(user, new FileMetadata(relativePath, (originalSize > 0 ? originalSize : fileSize), lastModified, sha256, false));
                }

                // Phát sóng thông báo đẩy cho các Client khác của người dùng/nhóm
                server.broadcastFileChange(user, relativePath, "UPLOAD", this);
                server.notifyStatsUpdated();
            }
        } catch (Exception ex) {
            out.writeObject(Packet.createErrorResponse("Lỗi lưu tệp: " + ex.getMessage()));
            out.flush();
            server.log(ServerEventListener.LogLevel.ERROR, "[" + sessionInfo.getClientId() + "] Lỗi upload " + relativePath + ": " + ex.getMessage());
        }
    }

    private void handleDownload(Packet downloadRequest) throws IOException {
        String relativePath = downloadRequest.getFileName();
        sessionInfo.setCurrentStatus("Đang gửi tệp: " + relativePath);

        StorageManager sm = getEffectiveStorage();
        File targetFile = sm.getFile(relativePath);
        if (targetFile == null || !targetFile.exists() || targetFile.isDirectory()) {
            targetFile = storageManager.getFile(relativePath); // Fallback sang shared root nếu có
            sm = storageManager;
        }

        if (targetFile == null || !targetFile.exists() || targetFile.isDirectory()) {
            out.writeObject(Packet.createErrorResponse("Tệp không tồn tại trên Server: " + relativePath));
            out.flush();
            server.log(ServerEventListener.LogLevel.WARN, "[" + sessionInfo.getClientId() + "] Yêu cầu tải tệp không tồn tại: " + relativePath);
            return;
        }

        long fileSize = targetFile.length();
        long lastModified = targetFile.lastModified();
        String sha256 = com.mycompany.doancs.common.HashUtils.calculateSHA256(targetFile);

        // Gửi Packet phản hồi chứa thông tin tệp
        Packet readyPacket = new Packet(CommandType.READY_TO_SEND);
        readyPacket.setFileName(relativePath);
        readyPacket.setFileSize(fileSize);
        readyPacket.setLastModified(lastModified);
        readyPacket.setChecksum(sha256);
        out.writeObject(readyPacket);
        out.flush();

        // Truyền luồng byte theo khối 64KB
        sm.writeFileChunked(relativePath, out);

        // Đợi gói phản hồi xác nhận từ Client
        try {
            Packet clientResp = (Packet) in.readObject();
            if (clientResp != null && clientResp.isSuccess()) {
                sessionInfo.recordTransfer(fileSize);
                sessionInfo.setCurrentStatus("Đã gửi " + relativePath);
                server.notifyFileSent(sessionInfo.getClientId(), relativePath, fileSize);
                server.log(ServerEventListener.LogLevel.SUCCESS, "[" + sessionInfo.getClientId() + "] Đã gửi tệp: " + relativePath + " (" + FileUtils.formatBytes(fileSize) + ")");
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void handleDelete(Packet deleteRequest) throws IOException {
        String relativePath = deleteRequest.getFileName();
        StorageManager sm = getEffectiveStorage();
        DatabaseManager db = server.getDatabaseManager();
        String user = (authenticatedUser != null) ? authenticatedUser : "general";

        // Sử dụng Soft-Delete (Thùng rác an toàn) trước khi xóa cứng
        boolean deleted = false;
        if (db != null) {
            deleted = sm.moveToRecycleBin(relativePath, user, db);
        }
        if (!deleted) {
            deleted = sm.deleteFile(relativePath);
        }
        if (!deleted && !sm.getRootDirectory().equals(server.getSharedDirectory())) {
            deleted = storageManager.deleteFile(relativePath);
        }

        if (deleted) {
            out.writeObject(Packet.createOkResponse("Đã chuyển tệp vào thùng rác an toàn: " + relativePath));
            server.log(ServerEventListener.LogLevel.INFO, "[" + sessionInfo.getClientId() + "] [Thùng rác] Đã chuyển tệp vào thùng rác an toàn: " + relativePath);

            // Xóa Metadata khỏi Database SQLite
            if (db != null) {
                db.deleteFileMetadata(user, relativePath);
            }

            // Phát sóng thông báo đẩy cho các Client khác
            server.broadcastFileChange(user, relativePath, "DELETE", this);
            server.notifyStatsUpdated();
        } else {
            out.writeObject(Packet.createErrorResponse("Không thể xóa tệp trên máy chủ"));
        }
        out.flush();
    }

    private void handleConflictResolve(Packet req) throws IOException {
        String originalPath = req.getFileName();
        String conflictPath = req.getMessage();

        DatabaseManager db = server.getDatabaseManager();
        String user = (authenticatedUser != null) ? authenticatedUser : "general";
        if (db != null && originalPath != null && conflictPath != null) {
            db.logConflict(user, originalPath, conflictPath);
            server.log(ServerEventListener.LogLevel.WARN, "[Xung đột] @" + user + ": " + originalPath + " -> Đã lưu bản sao: " + conflictPath);
            server.notifyStatsUpdated();
        }

        out.writeObject(Packet.createOkResponse("Đã ghi nhận xung đột"));
        out.flush();
    }

    private void handleGetFileVersions(Packet req) throws IOException {
        String relativePath = req.getFileName();
        DatabaseManager db = server.getDatabaseManager();
        String user = (authenticatedUser != null) ? authenticatedUser : "general";
        List<com.mycompany.doancs.model.FileVersionInfo> versions = (db != null) ? db.getFileVersions(user, relativePath) : new ArrayList<>();
        Packet resp = Packet.createFileVersionsResponse(versions);
        out.writeObject(resp);
        out.flush();
        server.log(ServerEventListener.LogLevel.INFO, "[" + sessionInfo.getClientId() + "] Đã gửi " + versions.size() + " phiên bản cũ của '" + relativePath + "'");
    }

    private void handleRestoreFileVersion(Packet req) throws IOException {
        int versionId = req.getVersionId();
        String relativePath = req.getFileName();
        DatabaseManager db = server.getDatabaseManager();
        String user = (authenticatedUser != null) ? authenticatedUser : "general";

        if (db == null) {
            out.writeObject(Packet.createErrorResponse("Database chưa sẵn sàng!"));
            out.flush();
            return;
        }

        com.mycompany.doancs.model.FileVersionInfo ver = db.getVersionById(versionId);
        if (ver == null) {
            out.writeObject(Packet.createErrorResponse("Không tìm thấy phiên bản ID: " + versionId));
            out.flush();
            return;
        }

        StorageManager sm = getEffectiveStorage();
        boolean restored = sm.restoreFileVersion(relativePath, ver.getBackupPath());
        if (restored) {
            File restoredFile = sm.getFile(relativePath);
            long size = restoredFile != null ? restoredFile.length() : ver.getFileSize();
            long lastMod = restoredFile != null ? restoredFile.lastModified() : System.currentTimeMillis();
            db.upsertFileMetadata(user, new FileMetadata(relativePath, size, lastMod, ver.getSha256(), false));

            out.writeObject(Packet.createOkResponse("Đã khôi phục tệp về phiên bản v" + ver.getVersion()));
            out.flush();

            server.log(ServerEventListener.LogLevel.SUCCESS, "[" + sessionInfo.getClientId() + "] [Rollback] Đã khôi phục '" + relativePath + "' về phiên bản v" + ver.getVersion());
            server.broadcastFileChange(user, relativePath, "RESTORE", this);
            server.notifyStatsUpdated();
        } else {
            out.writeObject(Packet.createErrorResponse("Không thể khôi phục tệp từ sao lưu!"));
            out.flush();
        }
    }

    private void handleCheckOffset(Packet req) throws IOException {
        String relativePath = req.getFileName();
        StorageManager sm = getEffectiveStorage();
        File targetFile = sm.getFile(relativePath);
        File partFile = new File(targetFile.getParentFile(), targetFile.getName() + ".part");
        long currentOffset = 0;
        if (partFile.exists()) {
            currentOffset = partFile.length();
        }
        out.writeObject(Packet.createOffsetResponse(currentOffset));
        out.flush();
    }

    private void handleGetRecycleBin(Packet req) throws IOException {
        DatabaseManager db = server.getDatabaseManager();
        String user = (authenticatedUser != null) ? authenticatedUser : "general";
        List<Map<String, String>> items = (db != null) ? db.getAllRecycleBin(user) : new ArrayList<>();
        Packet resp = new Packet(CommandType.RECYCLE_BIN_RESPONSE);
        resp.setRecycleItems(items);
        out.writeObject(resp);
        out.flush();
    }

    private void handleRestoreRecycleFile(Packet req) throws IOException {
        int id = req.getVersionId();
        DatabaseManager db = server.getDatabaseManager();
        String user = (authenticatedUser != null) ? authenticatedUser : "general";

        if (db == null) {
            out.writeObject(Packet.createErrorResponse("Database chưa sẵn sàng!"));
            out.flush();
            return;
        }

        Map<String, String> item = db.getRecycleBinItem(id);
        if (item == null) {
            out.writeObject(Packet.createErrorResponse("Không tìm thấy tệp trong thùng rác!"));
            out.flush();
            return;
        }

        String path = item.get("path");
        String trashPath = item.get("trash_path");
        StorageManager sm = getEffectiveStorage();

        boolean restored = sm.restoreRecycledFile(path, trashPath);
        if (restored) {
            db.deleteRecycleBinItem(id);
            File f = sm.getFile(path);
            if (f != null && f.exists()) {
                String sha256 = com.mycompany.doancs.common.HashUtils.calculateSHA256(f);
                db.upsertFileMetadata(user, new FileMetadata(path, f.length(), f.lastModified(), sha256, false));
            }
            out.writeObject(Packet.createOkResponse("Đã khôi phục tệp từ thùng rác"));
            server.broadcastFileChange(user, path, "RESTORE", this);
            server.notifyStatsUpdated();
        } else {
            out.writeObject(Packet.createErrorResponse("Không thể khôi phục tệp từ thùng rác!"));
        }
        out.flush();
    }

    public void close() {
        running = false;
        if (isNotificationChannel && authenticatedUser != null) {
            server.unregisterNotificationChannel(authenticatedUser, this);
        }
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
        server.notifyClientDisconnected(sessionInfo);
        server.log(ServerEventListener.LogLevel.INFO, "Client đã ngắt kết nối: " + sessionInfo.getClientId());
    }
}
