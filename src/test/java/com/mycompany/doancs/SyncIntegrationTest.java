package com.mycompany.doancs;

import com.mycompany.doancs.client.NetworkClient;
import com.mycompany.doancs.common.FileMetadata;
import com.mycompany.doancs.model.*;
import com.mycompany.doancs.server.ServerCore;
import com.mycompany.doancs.util.FileUtils;
import com.mycompany.doancs.util.SampleDataGenerator;

import java.io.File;
import java.util.List;

public class SyncIntegrationTest {

    public static void main(String[] args) {
        System.out.println("========== BẮT ĐẦU KIỂM THỬ TÍCH HỢP ĐỒNG BỘ DỮ LIỆU THẬT ==========");

        File baseDir = new File(System.getProperty("java.io.tmpdir"), "FileSyncTest_" + System.currentTimeMillis());
        File clientDir = new File(baseDir, "ClientDir");
        File serverDir = new File(baseDir, "ServerDir");

        int testPort = 18889;
        ServerCore server = new ServerCore();
        NetworkClient client = new NetworkClient();

        try {
            // 1. Tạo môi trường dữ liệu mẫu
            System.out.println("\n[1] Tạo dữ liệu mẫu đa dạng...");
            SampleDataGenerator.generateSampleTestEnvironment(clientDir, serverDir);

            // Kiểm tra số lượng tệp ban đầu
            List<FileMetadata> initialClientFiles = client.scanLocalFiles(clientDir);
            List<FileMetadata> initialServerFiles = new com.mycompany.doancs.server.StorageManager(serverDir).scanFiles();
            System.out.println("   -> Số tệp ban đầu phía Client: " + initialClientFiles.size());
            System.out.println("   -> Số tệp ban đầu phía Server: " + initialServerFiles.size());

            // 2. Khởi động ServerCore
            System.out.println("\n[2] Khởi động ServerCore trên cổng " + testPort + "...");
            server.start(testPort, serverDir);
            System.out.println("   -> ServerCore đã khởi động thành công.");

            // 3. NetworkClient kết nối đến Server qua TCP Socket
            System.out.println("\n[3] NetworkClient thiết lập kết nối TCP Socket (Packet Ping/Pong)...");
            client.connect("127.0.0.1", testPort);
            System.out.println("   -> Client đã kết nối: " + client.isConnected());

            // 4. Lấy danh sách tệp từ xa
            System.out.println("\n[4] Client gửi Packet GET_FILE_LIST lấy danh mục siêu dữ liệu từ Server...");
            List<FileMetadata> remoteList = client.fetchRemoteFileList();
            System.out.println("   -> Server trả về " + remoteList.size() + " tệp.");

            // 5. So sánh danh mục tệp (Pre-flight Compare dựa trên SHA-256)
            System.out.println("\n[5] Tiến hành so sánh tệp (Thuật toán so sánh siêu dữ liệu & SHA-256)...");
            List<SyncItem> syncItems = client.compare(initialClientFiles, remoteList, SyncMode.TWO_WAY);
            System.out.println("   -> Tổng số mục trong ma trận so sánh: " + syncItems.size());
            for (SyncItem item : syncItems) {
                System.out.printf("      [%-14s] %-35s (Lý do: %s)\n",
                        item.getAction().name(), item.getRelativePath(), item.getReason());
            }

            // 6. Thực thi đồng bộ Hai Chiều (Execute Two-Way Sync theo Chunking 64KB)
            System.out.println("\n[6] Thực thi truyền tệp (Upload / Download Chunk Stream 64KB)...");
            SyncStats stats = client.executeSync(syncItems, clientDir);
            System.out.println("   -> Đồng bộ hoàn tất trong " + (stats.getEndTime() - stats.getStartTime()) + " ms");
            System.out.println("   -> Tốc độ trung bình: " + stats.getFormattedSpeed());
            System.out.println("   -> Tổng dung lượng truyền: " + FileUtils.formatBytes(stats.getBytesTransferredSoFar()));

            // 7. Xác minh sau khi đồng bộ
            System.out.println("\n[7] Xác minh tính toàn vẹn và kết quả...");
            List<FileMetadata> postClientFiles = client.scanLocalFiles(clientDir);
            List<FileMetadata> postServerFiles = server.getStorageManager().scanFiles();
            System.out.println("   -> Số tệp Client sau đồng bộ: " + postClientFiles.size());
            System.out.println("   -> Số tệp Server sau đồng bộ: " + postServerFiles.size());

            if (postClientFiles.size() != postServerFiles.size()) {
                throw new AssertionError("Lỗi: Số lượng tệp 2 bên không bằng nhau!");
            }

            // 8. Chạy quét lại lần 2 để kiểm tra tính năng "Bỏ qua tệp không thay đổi" (Incremental Sync)
            System.out.println("\n[8] Kiểm tra cơ chế Không truyền lại tệp không thay đổi (Incremental Sync với SHA-256)...");
            List<FileMetadata> remoteList2 = client.fetchRemoteFileList();
            List<SyncItem> syncItems2 = client.compare(postClientFiles, remoteList2, SyncMode.TWO_WAY);

            long upToDateCount = syncItems2.stream().filter(i -> i.getAction() == SyncAction.UP_TO_DATE).count();
            System.out.println("   -> Số tệp đã khớp (UP_TO_DATE): " + upToDateCount + " / " + syncItems2.size());

            if (upToDateCount != syncItems2.size()) {
                throw new AssertionError("Lỗi: Vẫn còn tệp chưa khớp sau khi đã đồng bộ!");
            }

            System.out.println("\n=> TẤT CẢ CÁC BƯỚC KIỂM THỬ THEO KIẾN TRÚC MỚI ĐỀU ĐẠT CHUẨN 100% THÀNH CÔNG!");

        } catch (Exception e) {
            System.err.println("\n[LỖI TEST]: " + e.getMessage());
            e.printStackTrace();
        } finally {
            client.disconnect();
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
