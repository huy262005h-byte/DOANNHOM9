package com.mycompany.doancs.util;

import com.mycompany.doancs.model.FileInfo;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.CRC32;

public class FileUtils {

    /**
     * Quét toàn bộ thư mục và tạo danh sách FileInfo của tất cả các tệp con.
     */
    public static List<FileInfo> scanDirectory(File rootDir) {
        List<FileInfo> list = new ArrayList<>();
        if (rootDir == null || !rootDir.exists() || !rootDir.isDirectory()) {
            return list;
        }

        Path rootPath = rootDir.toPath().toAbsolutePath().normalize();

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Bỏ qua thư mục ẩn như .git, .idea, etc.
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (name.startsWith(".") && !dir.equals(rootPath)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        String name = file.getFileName().toString();
                        if (!name.startsWith(".")) {
                            Path relative = rootPath.relativize(file);
                            String normalizedRelPath = normalizePath(relative.toString());
                            long size = attrs.size();
                            long lastModified = attrs.lastModifiedTime().toMillis();
                            String checksum = calculateCRC32(file.toFile());

                            list.add(new FileInfo(normalizedRelPath, size, lastModified, checksum, false));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    System.err.println("Không thể đọc tệp: " + file + " (" + exc.getMessage() + ")");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Sắp xếp theo thứ tự đường dẫn A-Z
        list.sort(Comparator.comparing(FileInfo::getRelativePath));
        return list;
    }

    /**
     * Tính mã CRC32 nhanh cho tệp (vừa nhẹ vừa phát hiện sai khác dữ liệu chính xác).
     */
    public static String calculateCRC32(File file) {
        if (!file.exists() || file.isDirectory()) {
            return "";
        }
        CRC32 crc = new CRC32();
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
            return String.format("%08X", crc.getValue());
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Chuẩn hóa đường dẫn tương đối (chuyển dấu '\' thành '/', bỏ dấu '/' ở đầu).
     */
    public static String normalizePath(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    /**
     * Kiểm tra bảo mật chống tấn công Path Traversal ("../").
     */
    public static boolean isPathSafe(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return false;
        }
        String p = normalizePath(relativePath);
        if (p.contains("..") || p.startsWith("/") || p.contains(":") || p.contains("\0")) {
            return false;
        }
        return true;
    }

    /**
     * Đặt lại chính xác thời gian sửa đổi cuối cùng cho tệp (cho phép so sánh timestamp chính xác).
     */
    public static boolean setLastModified(File file, long timestamp) {
        if (file == null || !file.exists()) return false;
        try {
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(timestamp));
            return true;
        } catch (Exception e) {
            return file.setLastModified(timestamp);
        }
    }

    /**
     * Định dạng kích thước tệp thân thiện với người dùng (Bytes, KB, MB, GB).
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }

    /**
     * Định dạng thời gian theo chuẩn yyyy-MM-dd HH:mm:ss.
     */
    public static String formatDateTime(long millis) {
        if (millis <= 0) return "-";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(millis));
    }

    /**
     * Tạo đường dẫn tệp xung đột dạng filename_conflict_[timestamp].ext để lưu vết và tránh mất dữ liệu.
     */
    public static String generateConflictPath(String originalPath) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String normalized = normalizePath(originalPath);
        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex > normalized.lastIndexOf('/')) {
            String nameWithoutExt = normalized.substring(0, dotIndex);
            String ext = normalized.substring(dotIndex);
            return nameWithoutExt + "_conflict_" + timestamp + ext;
        } else {
            return normalized + "_conflict_" + timestamp;
        }
    }

    /**
     * Mở thư mục bằng trình quản lý tệp hệ điều hành (Explorer trên Windows).
     */
    public static void openFolderInExplorer(File folder) {
        if (folder == null) return;
        try {
            if (!folder.exists()) {
                folder.mkdirs();
            }
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(folder);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
