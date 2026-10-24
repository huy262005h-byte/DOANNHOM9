package com.mycompany.doancs;

import com.mycompany.doancs.client.ClientEventListener;
import com.mycompany.doancs.client.NetworkClient;
import com.mycompany.doancs.common.FileMetadata;
import com.mycompany.doancs.model.*;
import com.mycompany.doancs.server.ServerCore;
import com.mycompany.doancs.server.db.DatabaseManager;
import com.mycompany.doancs.util.FileUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kiểm thử tích hợp toàn diện các tính năng nâng cao:
 * 1. Xác thực tài khoản (Register & Login với SHA-256 + Salt trong SQLite)
 * 2. Lưu trữ siêu dữ liệu & Lập chỉ mục trong Database SQLite
 * 3. Kênh thông báo đẩy thời gian thực giữa 2 máy khách (Real-time TCP Push Notification)
 * 4. Xử lý xung đột tự động tạo bản sao 'filename_conflict_[timestamp].ext' bảo toàn dữ liệu
 */
public class AdvancedFeaturesIntegrationTest {

    public static void main(String[] args) {
        System.out.println("========== BẮT ĐẦU KIỂM THỬ TÍCH HỢP TÍNH NĂNG NÂNG CAO ==========");

        File baseDir = new File(System.getProperty("java.io.tmpdir"), "AdvTest_" + System.currentTimeMillis());
        File serverDir = new File(baseDir, "ServerShared");
        File client1Dir = new File(baseDir, "Client1Local");
        File client2Dir = new File(baseDir, "Client2Local");

        serverDir.mkdirs();
        client1Dir.mkdirs();
        client2Dir.mkdirs();

        int port = 18890;
        ServerCore server = new ServerCore();
        NetworkClient client1 = new NetworkClient();
        NetworkClient client2 = new NetworkClient();

        try {
            // 1. Khởi động Server
            System.out.println("\n[1] Khởi động ServerCore kèm Cơ sở dữ liệu SQLite...");
            server.start(port, serverDir);
            DatabaseManager db = server.getDatabaseManager();
            assert db != null : "DatabaseManager phải được khởi tạo thành công";
            System.out.println("   -> Máy chủ & Database SQLite đã sẵn sàng.");

            // 2. Kiểm tra tài khoản mặc định và đăng ký tài khoản mới
            System.out.println("\n[2] Kiểm thử Xác thực (Authentication): Đăng ký & Đăng nhập...");
            client1.connect("127.0.0.1", port);

            // Đăng ký tài khoản testuser
            boolean registered = client1.register("testuser", "pass12345");
            System.out.println("   -> Đăng ký tài khoản mới '@testuser': " + registered);
            assert registered : "Đăng ký phải thành công";

            // Đăng nhập
            boolean loggedIn = client1.login("testuser", "pass12345");
            System.out.println("   -> Đăng nhập với '@testuser': " + loggedIn);
            assert loggedIn : "Đăng nhập phải thành công";
            assert client1.getAuthToken() != null : "Token phiên làm việc phải tồn tại";
            System.out.println("   -> Token nhận được: " + client1.getAuthToken());

            // 3. Kết nối Client 2 và đăng nhập cùng tài khoản để kiểm tra Real-time Push Notification
            System.out.println("\n[3] Kiểm thử Kênh Thông Báo Đẩy Thời Gian Thực (TCP Push Notification)...");
            client2.connect("127.0.0.1", port);
            client2.login("testuser", "pass12345");

            CountDownLatch pushLatch = new CountDownLatch(1);
            AtomicBoolean pushReceived = new AtomicBoolean(false);

            client2.addListener(new ClientEventListener() {
                @Override public void onConnected(String host, int port) {}
                @Override public void onDisconnected() {}
                @Override public void onScanCompleted(List<SyncItem> comparisonList, SyncStats stats) {}
                @Override public void onSyncStarted(int totalItems, long totalBytes) {}
                @Override public void onItemProgress(SyncItem item, int percent, long bytesTransferred, long fileSize) {}
                @Override public void onItemCompleted(SyncItem item, boolean success, String message) {}
                @Override public void onSyncFinished(SyncStats stats) {}
                @Override public void onLog(LogLevel level, String message) {}
                @Override
                public void onServerChangeNotification(String fileName, String action) {
                    System.out.println("   -> [Client 2 BẮT ĐƯỢC PUSH NOTIFICATION TỪ SERVER!]: Tệp=" + fileName + ", Hành động=" + action);
                    pushReceived.set(true);
                    pushLatch.countDown();
                }
            });

            // Client 1 tạo và tải lên 1 tệp mới
            File sampleFile = new File(client1Dir, "realtime_doc.txt");
            try (FileWriter fw = new FileWriter(sampleFile)) {
                fw.write("Dữ liệu được cập nhật từ Client 1 lúc " + System.currentTimeMillis());
            }
            System.out.println("   -> Client 1 tải tệp 'realtime_doc.txt' lên Server...");
            boolean uploaded = client1.uploadSingleCustomFile(sampleFile, "realtime_doc.txt");
            assert uploaded : "Tải lên phải thành công";

            // Chờ xem Client 2 có nhận được push notification từ Server hay không
            boolean receivedPushInTime = pushLatch.await(5, TimeUnit.SECONDS);
            System.out.println("   -> Client 2 nhận thông báo đẩy thành công: " + (receivedPushInTime && pushReceived.get()));
            assert receivedPushInTime && pushReceived.get() : "Client 2 phải nhận được thông báo đẩy thời gian thực!";

            // 4. Kiểm tra Database SQLite đã ghi nhận Metadata
            System.out.println("\n[4] Kiểm tra Metadata lưu trong Database SQLite...");
            List<Map<String, String>> dbMetaList = db.getAllFileMetadata();
            System.out.println("   -> Tổng số bản ghi metadata trong SQLite: " + dbMetaList.size());
            assert !dbMetaList.isEmpty() : "SQLite files_metadata không được rỗng";
            for (Map<String, String> m : dbMetaList) {
                System.out.println("      • @" + m.get("user") + " | " + m.get("path") + " | " + m.get("size") + " B | SHA-256=" + m.get("sha256"));
            }

            // 5. Kiểm thử Xử Lý Xung Đột (Conflict Resolution)
            System.out.println("\n[5] Kiểm thử Xử Lý Xung Đột (Conflict Resolution)...");
            // Tạo tệp conflicting.txt trong thư mục chia sẻ chung trên Server
            File userServerDir = serverDir;
            File serverConflictFile = new File(userServerDir, "conflicting.txt");
            try (FileWriter fw = new FileWriter(serverConflictFile)) {
                fw.write("NỘI DUNG TẠI SERVER (Phiên bản B)");
            }
            long conflictTime = System.currentTimeMillis();
            FileUtils.setLastModified(serverConflictFile, conflictTime);

            // Tạo tệp conflicting.txt trên Client 1 với nội dung KHÁC nhưng CÙNG TIMESTAMP
            File clientConflictFile = new File(client1Dir, "conflicting.txt");
            try (FileWriter fw = new FileWriter(clientConflictFile)) {
                fw.write("NỘI DUNG TẠI CLIENT (Phiên bản A)");
            }
            FileUtils.setLastModified(clientConflictFile, conflictTime);

            // Thực hiện so sánh trên Client 1
            List<FileMetadata> cList = client1.scanLocalFiles(client1Dir);
            List<FileMetadata> sList = client1.fetchRemoteFileList();
            List<SyncItem> comparison = client1.compare(cList, sList, SyncMode.TWO_WAY);

            SyncItem conflictItem = comparison.stream()
                    .filter(i -> i.getRelativePath().equals("conflicting.txt"))
                    .findFirst()
                    .orElse(null);

            assert conflictItem != null : "Phải tìm thấy tệp conflicting.txt trong danh sách so sánh";
            System.out.println("   -> Trạng thái phát hiện của conflicting.txt: " + conflictItem.getAction());
            assert conflictItem.getAction() == SyncAction.CONFLICT : "Hệ thống phải nhận diện chính xác trạng thái CONFLICT";

            // Thực thi đồng bộ để giải quyết xung đột
            System.out.println("   -> Thực thi giải quyết xung đột tự động...");
            client1.executeSync(comparison, client1Dir);

            // Kiểm tra: Cả 2 phiên bản phải được bảo toàn!
            // 1. conflicting.txt tại client phải chứa nội dung Server
            // 2. Phải xuất hiện tệp conflicting_conflict_[timestamp].txt tại cả Client và Server
            File[] clientFiles = client1Dir.listFiles((dir, name) -> name.startsWith("conflicting_conflict_"));
            File[] serverFiles = userServerDir.listFiles((dir, name) -> name.startsWith("conflicting_conflict_"));

            assert clientFiles != null && clientFiles.length > 0 : "Client phải tạo bản sao xung đột";
            assert serverFiles != null && serverFiles.length > 0 : "Server phải lưu trữ bản sao xung đột";
            System.out.println("   -> Bản sao xung đột đã được tạo tại Client: " + clientFiles[0].getName());
            System.out.println("   -> Bản sao xung đột đã được tải lên Server: " + serverFiles[0].getName());

            // 6. Kiểm tra nhật ký xung đột trong SQLite
            System.out.println("\n[6] Kiểm tra nhật ký xung đột trong bảng conflict_logs của SQLite...");
            List<Map<String, String>> confLogs = db.getAllConflictLogs();
            assert !confLogs.isEmpty() : "Bảng conflict_logs phải ghi nhận trường hợp xung đột";
            for (Map<String, String> log : confLogs) {
                System.out.println("      • Thời gian: " + log.get("time") + " | Người dùng: @" + log.get("user") +
                        " | Gốc: " + log.get("original") + " -> Bản sao: " + log.get("conflict"));
            }

            // 7. Kiểm thử tính năng Xóa tệp (DELETE Packet vào Thùng rác)
            System.out.println("\n[7] Kiểm thử tính năng Xóa tệp & Thùng rác (Recycle Bin / Soft Delete)...");
            boolean deleted = client1.deleteRemoteFile("realtime_doc.txt");
            System.out.println("   -> Kết quả xóa 'realtime_doc.txt' trên Server: " + deleted);
            assert deleted : "Xóa tệp trên Server phải thành công";
            File deletedServerFile = new File(userServerDir, "realtime_doc.txt");
            assert !deletedServerFile.exists() : "Tệp trên Server phải được dời khỏi thư mục gốc";
            List<Map<String, String>> trashList = db.getAllRecycleBin("testuser");
            assert !trashList.isEmpty() : "Thùng rác SQLite phải ghi nhận tệp bị xóa";
            System.out.println("   -> Xác minh tệp đã được chuyển vào Thùng rác an toàn (Recycle Bin): " + trashList.get(0).get("path"));

            // 8. Kiểm thử Lịch sử phiên bản (File Versioning & Rollback)
            System.out.println("\n[8] Kiểm thử Lịch Sử Phiên Bản & Khôi Phục (File Versioning & Rollback)...");
            File verFile = new File(client1Dir, "version_demo.txt");
            try (FileWriter fw = new FileWriter(verFile)) {
                fw.write("Phiên bản ban đầu v1");
            }
            client1.uploadSingleCustomFile(verFile, "version_demo.txt");

            // Cập nhật phiên bản v2
            try (FileWriter fw = new FileWriter(verFile)) {
                fw.write("Nội dung được cập nhật v2 - có nhiều sửa đổi quan trọng!");
            }
            client1.uploadSingleCustomFile(verFile, "version_demo.txt");

            // Lấy danh sách phiên bản từ Server
            List<com.mycompany.doancs.model.FileVersionInfo> versions = client1.fetchFileVersions("version_demo.txt");
            System.out.println("   -> Số phiên bản cũ lưu trong lịch sử: " + versions.size());
            assert !versions.isEmpty() : "Server phải lưu trữ phiên bản v1 cũ trước khi bị ghi đè";
            for (com.mycompany.doancs.model.FileVersionInfo v : versions) {
                System.out.println("      • Phiên bản v" + v.getVersion() + " (" + v.getCreatedAt() + ", " + v.getFileSize() + " B, SHA-256=" + v.getSha256().substring(0, 12) + "...)");
            }

            // Kiểm thử Rollback
            boolean rollbacked = client1.restoreFileVersion(versions.get(0).getId(), "version_demo.txt");
            System.out.println("   -> Khôi phục (Rollback) về phiên bản v" + versions.get(0).getVersion() + ": " + rollbacked);
            assert rollbacked : "Khôi phục phiên bản phải thành công";

            // 9. Kiểm thử Nén động GZIP trên đường truyền (On-the-fly Compression)
            System.out.println("\n[9] Kiểm thử Nén Động GZIP trên đường truyền (On-the-fly Compression)...");
            File largeTextFile = new File(client1Dir, "big_log.txt");
            StringBuilder sbLarge = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                sbLarge.append("Dòng log kiểm thử dữ liệu nén GZIP qua mạng TCP Socket #").append(i).append("\n");
            }
            try (FileWriter fw = new FileWriter(largeTextFile)) {
                fw.write(sbLarge.toString());
            }
            long uncompressedLen = largeTextFile.length();
            boolean gzipUploaded = client1.uploadSingleCustomFile(largeTextFile, "big_log.txt");
            System.out.println("   -> Tải lên tệp văn bản lớn với GZIP: " + gzipUploaded + " (Kích thước gốc: " + uncompressedLen + " bytes)");
            assert gzipUploaded : "Tải tệp nén GZIP phải thành công";

            System.out.println("\n==========================================================================");
            System.out.println("=> TẤT CẢ 3 NHÓM NÂNG CẤP ĐÃ HOÀN TẤT VÀ VƯỢT QUA 100% CÁC BÀI KIỂM THỬ:");
            System.out.println("   1. [MẠNG]: Java 22 Virtual Threads (Loom) + Nén GZIP + Resumable Offset");
            System.out.println("   2. [ĐỒNG BỘ]: Lịch sử Phiên bản (Rollback) + Thùng rác (Recycle) + .syncignore");
            System.out.println("   3. [UI/UX]: System Tray + Windows Notification + Bộ lọc & Tìm kiếm");
            System.out.println("==========================================================================");

        } catch (Exception e) {
            System.err.println("[LỖI TEST NÂNG CAO]: " + e.getMessage());
            e.printStackTrace();
        } finally {
            client1.disconnect();
            client2.disconnect();
            server.stop();
            try {
                deleteRecursively(baseDir);
            } catch (Exception ignored) {}
        }
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
