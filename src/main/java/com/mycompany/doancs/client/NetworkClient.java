package com.mycompany.doancs.client;

import com.mycompany.doancs.common.CommandType;
import com.mycompany.doancs.common.FileMetadata;
import com.mycompany.doancs.common.HashUtils;
import com.mycompany.doancs.common.Packet;
import com.mycompany.doancs.model.*;
import com.mycompany.doancs.util.FileUtils;
import com.mycompany.doancs.util.SyncIgnoreFilter;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

/**
 * Network Client (Phía Máy Trạm):
 * Đảm nhận việc kết nối, gửi nhận gói tin Packet (Serializable) và truyền/nhận
 * tệp tin với Server qua TCP/IP Socket (Streaming & Chunking 64KB).
 */
public class NetworkClient {
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String host;
    private int port;
    private volatile boolean connected = false;
    private volatile boolean cancelRequested = false;
    private final List<ClientEventListener> listeners = new CopyOnWriteArrayList<>();

    // Xác thực tài khoản & Kênh thông báo đẩy
    private String authToken;
    private String currentUser;
    private Socket notifySocket;
    private ObjectOutputStream notifyOut;
    private ObjectInputStream notifyIn;
    private Thread notifyThread;
    private List<Map<String, String>> lastRecycleItems = new ArrayList<>();

    public synchronized void connect(String host, int port) throws IOException {
        if (connected) {
            disconnect();
        }
        this.host = host;
        this.port = port;

        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000); // 5s timeout
        socket.setSoTimeout(30000);

        out = new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        out.flush();
        in = new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));

        // Kiểm tra kết nối bằng gói PING
        out.writeObject(Packet.createPing());
        out.flush();

        try {
            Packet resp = (Packet) in.readObject();
            if (resp == null || resp.getCommand() != CommandType.PONG) {
                throw new IOException("Máy chủ phản hồi gói tin không đúng (PONG expected)");
            }
        } catch (ClassNotFoundException e) {
            throw new IOException("Không thể phân tích gói tin từ máy chủ: " + e.getMessage());
        }

        connected = true;
        for (ClientEventListener l : listeners) {
            l.onConnected(host, port);
        }
        log(ClientEventListener.LogLevel.SUCCESS, "Kết nối thành công đến Máy chủ: " + host + ":" + port);
    }

    public synchronized void disconnect() {
        if (!connected) return;
        connected = false;
        stopNotificationChannel();
        authToken = null;
        currentUser = null;

        try {
            if (out != null) {
                out.writeObject(Packet.createDisconnect());
                out.flush();
            }
        } catch (Exception ignored) {}

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {}

        for (ClientEventListener l : listeners) {
            l.onDisconnected();
        }
        log(ClientEventListener.LogLevel.INFO, "Đã ngắt kết nối khỏi Máy chủ.");
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    public String getAuthToken() {
        return authToken;
    }

    public String getCurrentUser() {
        return currentUser;
    }

    /**
     * Xác thực đăng nhập tài khoản với Máy Chủ.
     */
    public synchronized boolean login(String username, String password) throws IOException {
        if (!isConnected()) {
            throw new IOException("Chưa kết nối đến máy chủ");
        }
        log(ClientEventListener.LogLevel.INFO, "Đang gửi thông tin xác thực tài khoản @" + username + "...");
        Packet loginReq = Packet.createLoginRequest(username, password);
        out.writeObject(loginReq);
        out.flush();

        try {
            Packet resp = (Packet) in.readObject();
            if (resp != null && resp.isSuccess()) {
                this.authToken = resp.getAuthToken();
                this.currentUser = resp.getUsername() != null ? resp.getUsername() : username;
                log(ClientEventListener.LogLevel.SUCCESS, "Đăng nhập thành công với tài khoản: @" + this.currentUser);
                startNotificationChannel();
                return true;
            } else {
                String msg = resp != null ? resp.getMessage() : "Đăng nhập thất bại";
                log(ClientEventListener.LogLevel.ERROR, "Lỗi đăng nhập: " + msg);
                throw new IOException(msg);
            }
        } catch (ClassNotFoundException e) {
            throw new IOException("Lỗi đọc phản hồi xác thực: " + e.getMessage());
        }
    }

    /**
     * Đăng ký tài khoản người dùng mới trên Máy Chủ.
     */
    public synchronized boolean register(String username, String password) throws IOException {
        if (!isConnected()) {
            throw new IOException("Chưa kết nối đến máy chủ");
        }
        log(ClientEventListener.LogLevel.INFO, "Đang gửi yêu cầu đăng ký tài khoản mới @" + username + "...");
        Packet regReq = Packet.createRegisterRequest(username, password);
        out.writeObject(regReq);
        out.flush();

        try {
            Packet resp = (Packet) in.readObject();
            if (resp != null && resp.isSuccess()) {
                log(ClientEventListener.LogLevel.SUCCESS, "Đăng ký thành công tài khoản @" + username);
                return true;
            } else {
                String msg = resp != null ? resp.getMessage() : "Đăng ký thất bại";
                log(ClientEventListener.LogLevel.ERROR, "Lỗi đăng ký: " + msg);
                throw new IOException(msg);
            }
        } catch (ClassNotFoundException e) {
            throw new IOException("Lỗi đọc phản hồi đăng ký: " + e.getMessage());
        }
    }

    /**
     * Mở kênh Socket phụ để nhận thông báo đẩy thời gian thực từ Máy Chủ (Real-time Push).
     */
    public synchronized void startNotificationChannel() {
        stopNotificationChannel();
        if (!isConnected()) return;

        Thread t = new Thread(() -> {
            try {
                notifySocket = new Socket();
                notifySocket.connect(new InetSocketAddress(host, port), 5000);
                notifyOut = new ObjectOutputStream(new BufferedOutputStream(notifySocket.getOutputStream()));
                notifyOut.flush();
                notifyIn = new ObjectInputStream(new BufferedInputStream(notifySocket.getInputStream()));

                Packet sub = Packet.createSubscribeNotifications(authToken);
                sub.setUsername(currentUser);
                notifyOut.writeObject(sub);
                notifyOut.flush();

                Packet resp = (Packet) notifyIn.readObject();
                if (resp != null && resp.isSuccess()) {
                    log(ClientEventListener.LogLevel.INFO, "Kênh nhận thông báo thời gian thực từ Máy chủ đã kết nối.");
                }

                while (connected && notifySocket != null && !notifySocket.isClosed()) {
                    Packet push = (Packet) notifyIn.readObject();
                    if (push == null) continue;
                    if (push.getCommand() == CommandType.SERVER_NOTIFY_CHANGE) {
                        String file = push.getFileName();
                        String action = push.getMessage();
                        log(ClientEventListener.LogLevel.INFO, "[Push Event] Máy chủ thông báo thay đổi: " + file + " (" + action + ")");
                        for (ClientEventListener l : listeners) {
                            l.onServerChangeNotification(file, action);
                        }
                    }
                }
            } catch (Exception ignored) {
                // Kênh đóng khi ngắt kết nối
            }
        }, "NetworkClient-NotificationChannel");
        t.setDaemon(true);
        this.notifyThread = t;
        t.start();
    }

    public synchronized void stopNotificationChannel() {
        try {
            if (notifySocket != null && !notifySocket.isClosed()) {
                notifySocket.close();
            }
        } catch (Exception ignored) {}
        notifySocket = null;
        notifyOut = null;
        notifyIn = null;
        if (notifyThread != null) {
            notifyThread.interrupt();
            notifyThread = null;
        }
    }

    public void addListener(ClientEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ClientEventListener listener) {
        listeners.remove(listener);
    }

    public void log(ClientEventListener.LogLevel level, String message) {
        for (ClientEventListener l : listeners) {
            l.onLog(level, message);
        }
    }

    public void requestCancel() {
        this.cancelRequested = true;
    }

    /**
     * Gửi yêu cầu lấy danh mục siêu dữ liệu tệp từ Máy Chủ (gói tin GET_FILE_LIST).
     */
    public synchronized List<FileMetadata> fetchRemoteFileList() throws IOException {
        if (!isConnected()) {
            throw new IOException("Chưa kết nối đến máy chủ");
        }

        log(ClientEventListener.LogLevel.INFO, "Gửi Packet GET_FILE_LIST yêu cầu danh mục tệp từ Máy chủ...");
        out.writeObject(Packet.createGetFileListRequest(authToken));
        out.flush();

        try {
            Packet resp = (Packet) in.readObject();
            if (resp == null || resp.getCommand() != CommandType.FILE_LIST_RESPONSE) {
                throw new IOException("Phản hồi không hợp lệ từ máy chủ");
            }
            List<FileMetadata> list = resp.getFileList() != null ? resp.getFileList() : new ArrayList<>();
            this.lastRecycleItems = resp.getRecycleItems() != null ? resp.getRecycleItems() : new ArrayList<>();
            log(ClientEventListener.LogLevel.SUCCESS, "Đã nhận danh mục " + list.size() + " tệp từ Máy chủ.");
            return list;
        } catch (ClassNotFoundException e) {
            throw new IOException("Không thể đọc danh mục tệp: " + e.getMessage());
        }
    }

    /**
     * Quét toàn bộ thư mục cục bộ của Client để lập danh sách FileMetadata và tính SHA-256.
     */
    public List<FileMetadata> scanLocalFiles(File rootDir) {
        List<FileMetadata> list = new ArrayList<>();
        if (rootDir == null || !rootDir.exists() || !rootDir.isDirectory()) {
            return list;
        }

        SyncIgnoreFilter ignoreFilter = new SyncIgnoreFilter(rootDir);

        Path rootPath = rootDir.toPath().toAbsolutePath().normalize();

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(rootPath)) return FileVisitResult.CONTINUE;
                    Path relative = rootPath.relativize(dir);
                    String relStr = FileUtils.normalizePath(relative.toString());
                    if (ignoreFilter.shouldIgnore(relStr)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        Path relative = rootPath.relativize(file);
                        String relPathStr = FileUtils.normalizePath(relative.toString());

                        if (!ignoreFilter.shouldIgnore(relPathStr)) {
                            long size = attrs.size();
                            long lastModified = attrs.lastModifiedTime().toMillis();
                            String sha256 = HashUtils.calculateSHA256(file.toFile());

                            list.add(new FileMetadata(relPathStr, size, lastModified, sha256, false));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }

        list.sort(Comparator.comparing(FileMetadata::getRelativePath));
        return list;
    }

    /**
     * So sánh danh sách siêu dữ liệu giữa Local và Remote dựa trên Tên, Kích thước,
     * Timestamp và Mã băm SHA-256.
     */
    public List<SyncItem> compare(List<FileMetadata> localFiles, List<FileMetadata> remoteFiles, SyncMode mode) {
        Map<String, FileMetadata> localMap = new HashMap<>();
        for (FileMetadata f : localFiles) {
            localMap.put(f.getRelativePath(), f);
        }

        Map<String, FileMetadata> remoteMap = new HashMap<>();
        for (FileMetadata f : remoteFiles) {
            remoteMap.put(f.getRelativePath(), f);
        }

        Set<String> deletedOnServerPaths = new HashSet<>();
        if (lastRecycleItems != null) {
            for (Map<String, String> item : lastRecycleItems) {
                String p = item.get("path");
                if (p != null) {
                    deletedOnServerPaths.add(FileUtils.normalizePath(p));
                }
            }
        }

        Set<String> allPaths = new TreeSet<>(localMap.keySet());
        allPaths.addAll(remoteMap.keySet());

        List<SyncItem> result = new ArrayList<>();

        for (String path : allPaths) {
            FileMetadata localMeta = localMap.get(path);
            FileMetadata remoteMeta = remoteMap.get(path);

            FileInfo localInfo = localMeta != null ?
                    new FileInfo(localMeta.getRelativePath(), localMeta.getSize(), localMeta.getLastModified(), localMeta.getSha256(), false) : null;
            FileInfo remoteInfo = remoteMeta != null ?
                    new FileInfo(remoteMeta.getRelativePath(), remoteMeta.getSize(), remoteMeta.getLastModified(), remoteMeta.getSha256(), false) : null;

            if (localMeta != null && remoteMeta == null) {
                // Tệp chỉ có ở Local
                if (deletedOnServerPaths.contains(path)) {
                    // Tệp này trước đó đã bị xóa trên Server (trong thùng rác) -> Không tự ý tải lên lại
                    result.add(new SyncItem(path, localInfo, null, SyncAction.SKIP, "Đã bị xóa trên Server (Trong thùng rác) - Bỏ qua"));
                } else if (mode == SyncMode.TWO_WAY || mode == SyncMode.UPLOAD_ONLY) {
                    result.add(new SyncItem(path, localInfo, null, SyncAction.UPLOAD, "Tệp mới ở Client -> Cần tải lên"));
                } else {
                    result.add(new SyncItem(path, localInfo, null, SyncAction.SKIP, "Chỉ tải về: Bỏ qua tệp mới Client"));
                }
            } else if (localMeta == null && remoteMeta != null) {
                // Tệp chỉ có ở Remote
                if (mode == SyncMode.TWO_WAY || mode == SyncMode.DOWNLOAD_ONLY) {
                    result.add(new SyncItem(path, null, remoteInfo, SyncAction.DOWNLOAD, "Tệp mới ở Server -> Cần tải về"));
                } else {
                    result.add(new SyncItem(path, null, remoteInfo, SyncAction.SKIP, "Chỉ tải lên: Bỏ qua tệp mới Server"));
                }
            } else if (localMeta != null && remoteMeta != null) {
                // Tồn tại ở cả 2 bên -> So sánh mã băm SHA-256 và kích thước
                boolean sameSize = localMeta.getSize() == remoteMeta.getSize();
                boolean sameHash = !localMeta.getSha256().isEmpty() && localMeta.getSha256().equalsIgnoreCase(remoteMeta.getSha256());

                long timeDiff = localMeta.getLastModified() - remoteMeta.getLastModified();

                if (sameSize && sameHash) {
                    result.add(new SyncItem(path, localInfo, remoteInfo, SyncAction.UP_TO_DATE, "Đã khớp hoàn toàn (Mã băm SHA-256 trùng khớp)"));
                } else if (timeDiff > 1000) {
                    // Local mới hơn Remote
                    if (mode == SyncMode.TWO_WAY || mode == SyncMode.UPLOAD_ONLY) {
                        result.add(new SyncItem(path, localInfo, remoteInfo, SyncAction.UPLOAD,
                                "Client mới hơn Server (" + (timeDiff / 1000) + "s) [Khác SHA-256] -> Cần tải lên"));
                    } else {
                        result.add(new SyncItem(path, localInfo, remoteInfo, SyncAction.SKIP, "Chỉ tải về: Bỏ qua bản sửa ở Client"));
                    }
                } else if (timeDiff < -1000) {
                    // Remote mới hơn Local
                    if (mode == SyncMode.TWO_WAY || mode == SyncMode.DOWNLOAD_ONLY) {
                        result.add(new SyncItem(path, localInfo, remoteInfo, SyncAction.DOWNLOAD,
                                "Server mới hơn Client (" + (-timeDiff / 1000) + "s) [Khác SHA-256] -> Cần tải về"));
                    } else {
                        result.add(new SyncItem(path, localInfo, remoteInfo, SyncAction.SKIP, "Chỉ tải lên: Bỏ qua bản sửa ở Server"));
                    }
                } else {
                    // Khác nội dung nhưng cùng timestamp
                    if (mode == SyncMode.TWO_WAY) {
                        result.add(new SyncItem(path, localInfo, remoteInfo, SyncAction.CONFLICT, "Xung đột: Khác SHA-256 nhưng cùng thời gian"));
                    } else if (mode == SyncMode.UPLOAD_ONLY) {
                        result.add(new SyncItem(path, localInfo, remoteInfo, SyncAction.UPLOAD, "Ghi đè nội dung lên Server"));
                    } else {
                        result.add(new SyncItem(path, localInfo, remoteInfo, SyncAction.DOWNLOAD, "Ghi đè nội dung từ Server về Client"));
                    }
                }
            }
        }

        return result;
    }

    /**
     * Thực thi đồng bộ danh sách SyncItem.
     */
    public synchronized SyncStats executeSync(List<SyncItem> items, File localDir) throws IOException {
        cancelRequested = false;
        SyncStats stats = new SyncStats();
        stats.reset();
        stats.setTotalFiles(items.size());

        long totalBytesToTransfer = 0;
        int toUpload = 0;
        int toDownload = 0;
        int upToDate = 0;
        int conflicts = 0;

        for (SyncItem item : items) {
            if (item.getAction() == SyncAction.UPLOAD) {
                toUpload++;
                totalBytesToTransfer += item.getFileSizeToTransfer();
            } else if (item.getAction() == SyncAction.DOWNLOAD) {
                toDownload++;
                totalBytesToTransfer += item.getFileSizeToTransfer();
            } else if (item.getAction() == SyncAction.UP_TO_DATE) {
                upToDate++;
            } else if (item.getAction() == SyncAction.CONFLICT) {
                conflicts++;
            }
        }

        stats.setTotalBytesToTransfer(totalBytesToTransfer);
        stats.setUploadCount(toUpload);
        stats.setDownloadCount(toDownload);
        stats.setUpToDateCount(upToDate);
        stats.setConflictCount(conflicts);

        for (ClientEventListener l : listeners) {
            l.onSyncStarted(items.size(), totalBytesToTransfer);
        }
        log(ClientEventListener.LogLevel.INFO, String.format("Bắt đầu đồng bộ: Cần tải lên %d tệp, tải về %d tệp, bỏ qua %d tệp đã khớp (Tổng: %s)",
                toUpload, toDownload, upToDate, FileUtils.formatBytes(totalBytesToTransfer)));

        for (SyncItem item : items) {
            if (cancelRequested) {
                log(ClientEventListener.LogLevel.WARN, "Quá trình đồng bộ đã bị người dùng hủy!");
                break;
            }

            if (item.getAction() == SyncAction.UPLOAD) {
                processUpload(item, localDir, stats);
            } else if (item.getAction() == SyncAction.DOWNLOAD) {
                processDownload(item, localDir, stats);
            } else if (item.getAction() == SyncAction.CONFLICT) {
                processConflict(item, localDir, stats);
            } else if (item.getAction() == SyncAction.UP_TO_DATE) {
                item.setTransferStatus("Đã đồng bộ (Bỏ qua)");
                item.setProgressPercent(100);
                for (ClientEventListener l : listeners) {
                    l.onItemCompleted(item, true, "Đã khớp SHA-256");
                }
            } else {
                item.setTransferStatus("Bỏ qua");
                item.setProgressPercent(100);
            }
        }

        stats.finish();
        for (ClientEventListener l : listeners) {
            l.onSyncFinished(stats);
        }
        log(ClientEventListener.LogLevel.SUCCESS, String.format("Đồng bộ hoàn tất! Tốc độ: %s. Đã truyền: %s",
                stats.getFormattedSpeed(), FileUtils.formatBytes(stats.getBytesTransferredSoFar())));

        return stats;
    }

    private boolean isCompressible(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".java") || lower.endsWith(".py") ||
                lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".html") ||
                lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".csv") ||
                lower.endsWith(".md") || lower.endsWith(".log") || lower.endsWith(".sql") ||
                lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".xls") ||
                lower.endsWith(".xlsx") || lower.endsWith(".c") || lower.endsWith(".cpp") ||
                lower.endsWith(".h") || lower.endsWith(".sh") || lower.endsWith(".bat");
    }

    private void processUpload(SyncItem item, File localDir, SyncStats stats) {
        File file = new File(localDir, item.getRelativePath());
        if (!file.exists()) {
            item.setTransferStatus("Lỗi");
            item.setErrorMessage("Tệp cục bộ không tồn tại");
            for (ClientEventListener l : listeners) {
                l.onItemCompleted(item, false, "Tệp không tồn tại");
            }
            return;
        }

        item.setTransferStatus("Đang tải lên...");
        item.setProgressPercent(0);

        File fileToSend = file;
        File tempGzFile = null;
        boolean useGzip = false;
        long originalSize = file.length();
        long transferSize = originalSize;

        // Tự động kiểm tra và nén động GZIP để tiết kiệm băng thông mạng (On-the-fly Compression)
        if (isCompressible(file.getName()) && originalSize > 256) {
            try {
                tempGzFile = File.createTempFile("client_gz_", ".tmp");
                try (FileInputStream fis = new FileInputStream(file);
                     BufferedInputStream bis = new BufferedInputStream(fis);
                     FileOutputStream fos = new FileOutputStream(tempGzFile);
                     GZIPOutputStream gos = new GZIPOutputStream(new BufferedOutputStream(fos))) {
                    byte[] b = new byte[64 * 1024];
                    int r;
                    while ((r = bis.read(b)) != -1) {
                        gos.write(b, 0, r);
                    }
                    gos.finish();
                    gos.flush();
                }
                if (tempGzFile.length() < originalSize) {
                    fileToSend = tempGzFile;
                    transferSize = tempGzFile.length();
                    useGzip = true;
                } else {
                    tempGzFile.delete();
                    tempGzFile = null;
                }
            } catch (Exception ignored) {
                if (tempGzFile != null && tempGzFile.exists()) tempGzFile.delete();
                tempGzFile = null;
            }
        }

        try {
            long lastModified = file.lastModified();
            String sha256 = HashUtils.calculateSHA256(file);

            // 1. Gửi Packet yêu cầu Upload
            Packet request = Packet.createUploadRequest(item.getRelativePath(), transferSize, lastModified, sha256, authToken);
            request.setCompressed(useGzip);
            request.setOriginalSize(originalSize);
            out.writeObject(request);
            out.flush();

            // 2. Chờ phản hồi READY từ Server
            Packet ready = (Packet) in.readObject();
            if (ready == null || ready.getCommand() != CommandType.READY_TO_RECEIVE) {
                String errMsg = ready != null ? ready.getMessage() : "Máy chủ không sẵn sàng";
                throw new IOException(errMsg);
            }

            // 3. Truyền dữ liệu tệp theo từng khối byte 64KB (Chunking)
            byte[] buffer = new byte[64 * 1024];
            long bytesRemaining = transferSize;
            long bytesTransferred = 0;

            try (FileInputStream fis = new FileInputStream(fileToSend);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {

                while (bytesRemaining > 0 && !cancelRequested) {
                    int toRead = (int) Math.min(buffer.length, bytesRemaining);
                    int read = bis.read(buffer, 0, toRead);
                    if (read == -1) break;

                    out.write(buffer, 0, read);
                    bytesRemaining -= read;
                    bytesTransferred += read;
                    stats.addBytesTransferred(read);

                    int pct = transferSize > 0 ? (int) ((bytesTransferred * 100) / transferSize) : 100;
                    item.setProgressPercent(pct);

                    for (ClientEventListener l : listeners) {
                        l.onItemProgress(item, pct, bytesTransferred, transferSize);
                    }
                }
                out.flush();
            }

            // 4. Nhận gói xác nhận từ Server
            Packet resp = (Packet) in.readObject();
            if (resp != null && resp.isSuccess()) {
                item.setTransferStatus("Thành công");
                item.setProgressPercent(100);
                for (ClientEventListener l : listeners) {
                    l.onItemCompleted(item, true, "Tải lên thành công");
                }
                if (useGzip) {
                    int savingPct = (int) ((originalSize - transferSize) * 100 / originalSize);
                    log(ClientEventListener.LogLevel.SUCCESS, "[Nén GZIP] Tải lên thành công: " + item.getRelativePath() +
                            " (" + FileUtils.formatBytes(originalSize) + " -> " + FileUtils.formatBytes(transferSize) +
                            ", Tiết kiệm " + savingPct + "% băng thông) [SHA-256 OK]");
                } else {
                    log(ClientEventListener.LogLevel.SUCCESS, "Tải lên thành công: " + item.getRelativePath() + " (" + FileUtils.formatBytes(originalSize) + ") [SHA-256 OK]");
                }
            } else {
                String msg = resp != null ? resp.getMessage() : "Lỗi không xác định";
                item.setTransferStatus("Lỗi");
                item.setErrorMessage(msg);
                for (ClientEventListener l : listeners) {
                    l.onItemCompleted(item, false, msg);
                }
                log(ClientEventListener.LogLevel.ERROR, "Lỗi tải lên " + item.getRelativePath() + ": " + msg);
            }
        } catch (Exception e) {
            item.setTransferStatus("Lỗi");
            item.setErrorMessage(e.getMessage());
            for (ClientEventListener l : listeners) {
                l.onItemCompleted(item, false, e.getMessage());
            }
            log(ClientEventListener.LogLevel.ERROR, "Lỗi truyền tệp " + item.getRelativePath() + ": " + e.getMessage());
        } finally {
            if (tempGzFile != null && tempGzFile.exists()) {
                tempGzFile.delete();
            }
        }
    }

    private void processDownload(SyncItem item, File localDir, SyncStats stats) {
        File file = new File(localDir, item.getRelativePath());
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        item.setTransferStatus("Đang tải về...");
        item.setProgressPercent(0);

        try {
            // 1. Gửi Packet yêu cầu Download
            Packet request = Packet.createDownloadRequest(item.getRelativePath(), authToken);
            out.writeObject(request);
            out.flush();

            // 2. Nhận Packet phản hồi chứa thông tin tệp
            Packet resp = (Packet) in.readObject();
            if (resp == null || resp.getCommand() != CommandType.READY_TO_SEND) {
                String errMsg = resp != null ? resp.getMessage() : "Không thể tải tệp";
                throw new IOException(errMsg);
            }

            long fileSize = resp.getFileSize();
            long remoteLastModified = resp.getLastModified();
            String expectedSHA256 = resp.getChecksum();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long bytesRemaining = fileSize;
            long bytesTransferred = 0;

            try (FileOutputStream fos = new FileOutputStream(file);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                while (bytesRemaining > 0 && !cancelRequested) {
                    int toRead = (int) Math.min(buffer.length, bytesRemaining);
                    int read = in.read(buffer, 0, toRead);
                    if (read == -1) {
                        throw new EOFException("Luồng dữ liệu bị gián đoạn");
                    }

                    bos.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    bytesRemaining -= read;
                    bytesTransferred += read;
                    stats.addBytesTransferred(read);

                    int pct = fileSize > 0 ? (int) ((bytesTransferred * 100) / fileSize) : 100;
                    item.setProgressPercent(pct);

                    for (ClientEventListener l : listeners) {
                        l.onItemProgress(item, pct, bytesTransferred, fileSize);
                    }
                }
                bos.flush();
            }

            FileUtils.setLastModified(file, remoteLastModified);

            // Kiểm tra mã băm SHA-256
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            String actualSHA256 = sb.toString().toUpperCase();

            if (expectedSHA256 != null && !expectedSHA256.isEmpty() && !actualSHA256.equalsIgnoreCase(expectedSHA256)) {
                file.delete();
                out.writeObject(Packet.createErrorResponse("Mã băm SHA-256 không khớp!"));
                out.flush();
                item.setTransferStatus("Lỗi SHA-256");
                for (ClientEventListener l : listeners) {
                    l.onItemCompleted(item, false, "Sai mã băm SHA-256");
                }
            } else {
                out.writeObject(Packet.createOkResponse("Nhận tệp thành công"));
                out.flush();

                item.setTransferStatus("Thành công");
                item.setProgressPercent(100);
                for (ClientEventListener l : listeners) {
                    l.onItemCompleted(item, true, "Tải về thành công");
                }
                log(ClientEventListener.LogLevel.SUCCESS, "Tải về thành công: " + item.getRelativePath() + " (" + FileUtils.formatBytes(fileSize) + ") [SHA-256 OK]");
            }

        } catch (Exception e) {
            item.setTransferStatus("Lỗi");
            item.setErrorMessage(e.getMessage());
            for (ClientEventListener l : listeners) {
                l.onItemCompleted(item, false, e.getMessage());
            }
            log(ClientEventListener.LogLevel.ERROR, "Lỗi tải về " + item.getRelativePath() + ": " + e.getMessage());
        }
    }

    /**
     * Xử lý trường hợp xung đột tệp (Conflict Resolution):
     * Tự động tạo bản sao lưu với tên định dạng 'filename_conflict_[timestamp].ext',
     * đẩy bản sao lưu lên Server để bảo toàn cả 2 phiên bản và kéo bản Server về máy.
     */
    private void processConflict(SyncItem item, File localDir, SyncStats stats) {
        String relativePath = item.getRelativePath();
        String conflictPath = FileUtils.generateConflictPath(relativePath);
        item.setTransferStatus("Đang giải quyết xung đột...");

        try {
            File originalLocal = new File(localDir, relativePath);
            File conflictLocal = new File(localDir, conflictPath);

            // 1. Tạo bản sao lưu xung đột tại máy khách
            if (originalLocal.exists()) {
                File conflictParent = conflictLocal.getParentFile();
                if (conflictParent != null && !conflictParent.exists()) {
                    conflictParent.mkdirs();
                }
                Files.copy(originalLocal.toPath(), conflictLocal.toPath(), StandardCopyOption.REPLACE_EXISTING);
                FileUtils.setLastModified(conflictLocal, originalLocal.lastModified());
            }

            // 2. Tải bản sao xung đột lên Server để máy chủ lưu trữ bản sao này
            if (conflictLocal.exists()) {
                uploadSingleCustomFile(conflictLocal, conflictPath);
            }

            // 3. Tải phiên bản mới nhất từ Server về tệp gốc tại máy khách
            downloadSingleCustomFile(relativePath, originalLocal);

            // 4. Báo cho Server ghi nhận xung đột vào SQLite conflict_logs
            Packet confPacket = new Packet(CommandType.CONFLICT_RESOLVE);
            confPacket.setFileName(relativePath);
            confPacket.setMessage(conflictPath);
            confPacket.setAuthToken(authToken);
            out.writeObject(confPacket);
            out.flush();
            try {
                in.readObject();
            } catch (Exception ignored) {}

            item.setTransferStatus("Đã bảo lưu: " + new File(conflictPath).getName());
            item.setProgressPercent(100);
            for (ClientEventListener l : listeners) {
                l.onItemCompleted(item, true, "Đã tạo bản sao xung đột: " + conflictPath);
            }
            log(ClientEventListener.LogLevel.WARN, "Xung đột: " + relativePath + " -> Đã lưu bản sao: " + conflictPath + " và cập nhật từ Server.");

        } catch (Exception e) {
            item.setTransferStatus("Lỗi giải quyết xung đột");
            item.setErrorMessage(e.getMessage());
            for (ClientEventListener l : listeners) {
                l.onItemCompleted(item, false, e.getMessage());
            }
            log(ClientEventListener.LogLevel.ERROR, "Lỗi xử lý xung đột " + relativePath + ": " + e.getMessage());
        }
    }

    /**
     * Tải lên một tệp riêng lẻ do người dùng lựa chọn lên Server.
     */
    public synchronized boolean uploadSingleCustomFile(File file, String remoteRelativePath) throws IOException {
        if (!isConnected()) {
            throw new IOException("Chưa kết nối đến máy chủ");
        }
        if (!file.exists() || file.isDirectory()) {
            throw new IOException("Tệp không tồn tại hoặc là thư mục");
        }

        cancelRequested = false;
        long fileSize = file.length();
        long lastModified = file.lastModified();
        String sha256 = HashUtils.calculateSHA256(file);

        for (ClientEventListener l : listeners) {
            l.onSyncStarted(1, fileSize);
        }

        SyncItem dummyItem = new SyncItem(remoteRelativePath,
                new FileInfo(remoteRelativePath, fileSize, lastModified, sha256, false),
                null, SyncAction.UPLOAD, "Tải lên thủ công");

        Packet req = Packet.createUploadRequest(remoteRelativePath, fileSize, lastModified, sha256, authToken);
        out.writeObject(req);
        out.flush();

        try {
            Packet ready = (Packet) in.readObject();
            if (ready == null || ready.getCommand() != CommandType.READY_TO_RECEIVE) {
                String errMsg = ready != null ? ready.getMessage() : "Máy chủ không sẵn sàng";
                throw new IOException(errMsg);
            }

            byte[] buffer = new byte[64 * 1024];
            long bytesRemaining = fileSize;
            long bytesTransferred = 0;

            try (FileInputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {

                while (bytesRemaining > 0 && !cancelRequested) {
                    int toRead = (int) Math.min(buffer.length, bytesRemaining);
                    int read = bis.read(buffer, 0, toRead);
                    if (read == -1) break;

                    out.write(buffer, 0, read);
                    bytesRemaining -= read;
                    bytesTransferred += read;

                    int pct = fileSize > 0 ? (int) ((bytesTransferred * 100) / fileSize) : 100;
                    for (ClientEventListener l : listeners) {
                        l.onItemProgress(dummyItem, pct, bytesTransferred, fileSize);
                    }
                }
                out.flush();
            }

            Packet resp = (Packet) in.readObject();
            if (resp != null && resp.isSuccess()) {
                log(ClientEventListener.LogLevel.SUCCESS, "Tải lên thành công: " + remoteRelativePath + " (" + FileUtils.formatBytes(fileSize) + ") [SHA-256 OK]");
                for (ClientEventListener l : listeners) {
                    l.onItemCompleted(dummyItem, true, "Tải lên thành công");
                }
                return true;
            } else {
                String errMsg = resp != null ? resp.getMessage() : "Lỗi tải lên";
                log(ClientEventListener.LogLevel.ERROR, "Lỗi tải lên: " + errMsg);
                for (ClientEventListener l : listeners) {
                    l.onItemCompleted(dummyItem, false, errMsg);
                }
                return false;
            }
        } catch (ClassNotFoundException e) {
            throw new IOException(e.getMessage());
        }
    }

    /**
     * Tải về một tệp riêng lẻ từ Server về máy cục bộ.
     */
    public synchronized boolean downloadSingleCustomFile(String remoteRelativePath, File destinationLocalFile) throws IOException {
        if (!isConnected()) {
            throw new IOException("Chưa kết nối đến máy chủ");
        }

        cancelRequested = false;
        File parentDir = destinationLocalFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        Packet req = Packet.createDownloadRequest(remoteRelativePath, authToken);
        out.writeObject(req);
        out.flush();

        try {
            Packet resp = (Packet) in.readObject();
            if (resp == null || resp.getCommand() != CommandType.READY_TO_SEND) {
                String errMsg = resp != null ? resp.getMessage() : "Không thể tải tệp";
                throw new IOException(errMsg);
            }

            long fileSize = resp.getFileSize();
            long remoteLastModified = resp.getLastModified();
            String expectedSHA256 = resp.getChecksum();

            for (ClientEventListener l : listeners) {
                l.onSyncStarted(1, fileSize);
            }

            SyncItem dummyItem = new SyncItem(remoteRelativePath, null,
                    new FileInfo(remoteRelativePath, fileSize, remoteLastModified, expectedSHA256, false),
                    SyncAction.DOWNLOAD, "Tải về thủ công");

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long bytesRemaining = fileSize;
            long bytesTransferred = 0;

            try (FileOutputStream fos = new FileOutputStream(destinationLocalFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                while (bytesRemaining > 0 && !cancelRequested) {
                    int toRead = (int) Math.min(buffer.length, bytesRemaining);
                    int read = in.read(buffer, 0, toRead);
                    if (read == -1) {
                        throw new EOFException("Luồng dữ liệu bị gián đoạn");
                    }

                    bos.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    bytesRemaining -= read;
                    bytesTransferred += read;

                    int pct = fileSize > 0 ? (int) ((bytesTransferred * 100) / fileSize) : 100;
                    for (ClientEventListener l : listeners) {
                        l.onItemProgress(dummyItem, pct, bytesTransferred, fileSize);
                    }
                }
                bos.flush();
            }

            FileUtils.setLastModified(destinationLocalFile, remoteLastModified);

            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            String actualSHA256 = sb.toString().toUpperCase();

            if (expectedSHA256 != null && !expectedSHA256.isEmpty() && !actualSHA256.equalsIgnoreCase(expectedSHA256)) {
                destinationLocalFile.delete();
                out.writeObject(Packet.createErrorResponse("Mã băm SHA-256 không khớp!"));
                out.flush();
                log(ClientEventListener.LogLevel.ERROR, "Lỗi SHA-256 tệp: " + remoteRelativePath);
                return false;
            } else {
                out.writeObject(Packet.createOkResponse("Nhận tệp thành công"));
                out.flush();
                log(ClientEventListener.LogLevel.SUCCESS, "Tải về thành công: " + remoteRelativePath + " (" + FileUtils.formatBytes(fileSize) + ") [SHA-256 OK]");
                for (ClientEventListener l : listeners) {
                    l.onItemCompleted(dummyItem, true, "Tải về thành công");
                }
                return true;
            }
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    /**
     * Gửi yêu cầu xóa tệp trên Server (Lệnh DELETE).
     */
    public synchronized boolean deleteRemoteFile(String remoteRelativePath) throws IOException {
        if (!isConnected()) {
            throw new IOException("Chưa kết nối đến máy chủ");
        }
        log(ClientEventListener.LogLevel.INFO, "Gửi yêu cầu xóa tệp trên Server: " + remoteRelativePath);
        Packet req = Packet.createDeleteRequest(remoteRelativePath, authToken);
        out.writeObject(req);
        out.flush();

        try {
            Packet resp = (Packet) in.readObject();
            if (resp != null && resp.isSuccess()) {
                log(ClientEventListener.LogLevel.SUCCESS, "Đã chuyển tệp vào thùng rác trên Server: " + remoteRelativePath);
                return true;
            } else {
                String errMsg = resp != null ? resp.getMessage() : "Lỗi không xác định khi xóa tệp trên máy chủ";
                log(ClientEventListener.LogLevel.ERROR, "Lỗi xóa tệp trên Server: " + errMsg);
                return false;
            }
        } catch (ClassNotFoundException e) {
            throw new IOException("Lỗi đọc phản hồi từ Server: " + e.getMessage());
        }
    }

    /**
     * Lấy danh sách các phiên bản cũ của tệp từ Server (File Versioning).
     */
    public synchronized List<FileVersionInfo> fetchFileVersions(String relativePath) throws IOException {
        if (!isConnected()) throw new IOException("Chưa kết nối đến máy chủ");
        log(ClientEventListener.LogLevel.INFO, "Yêu cầu lịch sử phiên bản tệp: " + relativePath);
        Packet req = Packet.createGetFileVersionsRequest(relativePath, authToken);
        out.writeObject(req);
        out.flush();
        try {
            Packet resp = (Packet) in.readObject();
            if (resp != null && resp.getFileVersions() != null) {
                return resp.getFileVersions();
            }
        } catch (ClassNotFoundException e) {
            throw new IOException(e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * Khôi phục tệp về phiên bản cũ (Rollback Version).
     */
    public synchronized boolean restoreFileVersion(int versionId, String relativePath) throws IOException {
        if (!isConnected()) throw new IOException("Chưa kết nối đến máy chủ");
        log(ClientEventListener.LogLevel.INFO, "Yêu cầu khôi phục phiên bản ID " + versionId + " cho tệp: " + relativePath);
        Packet req = Packet.createRestoreFileVersionRequest(versionId, relativePath, authToken);
        out.writeObject(req);
        out.flush();
        try {
            Packet resp = (Packet) in.readObject();
            if (resp != null && resp.isSuccess()) {
                log(ClientEventListener.LogLevel.SUCCESS, "Khôi phục phiên bản thành công: " + relativePath);
                return true;
            } else {
                String msg = resp != null ? resp.getMessage() : "Lỗi khôi phục";
                log(ClientEventListener.LogLevel.ERROR, "Lỗi khôi phục phiên bản: " + msg);
                return false;
            }
        } catch (ClassNotFoundException e) {
            throw new IOException(e.getMessage());
        }
    }

    /**
     * Lấy danh sách tệp trong thùng rác của tài khoản (Recycle Bin).
     */
    public synchronized List<Map<String, String>> fetchRecycleBin() throws IOException {
        if (!isConnected()) throw new IOException("Chưa kết nối đến máy chủ");
        Packet req = new Packet(CommandType.GET_RECYCLE_BIN);
        req.setAuthToken(authToken);
        out.writeObject(req);
        out.flush();
        try {
            Packet resp = (Packet) in.readObject();
            if (resp != null && resp.getRecycleItems() != null) {
                return resp.getRecycleItems();
            }
        } catch (ClassNotFoundException e) {
            throw new IOException(e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * Khôi phục tệp từ thùng rác trên Server.
     */
    public synchronized boolean restoreRecycledFile(int id) throws IOException {
        if (!isConnected()) throw new IOException("Chưa kết nối đến máy chủ");
        Packet req = new Packet(CommandType.RESTORE_RECYCLE_FILE);
        req.setVersionId(id);
        req.setAuthToken(authToken);
        out.writeObject(req);
        out.flush();
        try {
            Packet resp = (Packet) in.readObject();
            return resp != null && resp.isSuccess();
        } catch (ClassNotFoundException e) {
            throw new IOException(e.getMessage());
        }
    }
}
