package com.mycompany.doancs.server;

import com.mycompany.doancs.common.FileMetadata;
import com.mycompany.doancs.common.HashUtils;
import com.mycompany.doancs.server.db.DatabaseManager;
import com.mycompany.doancs.util.FileUtils;
import com.mycompany.doancs.util.SyncIgnoreFilter;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Storage Manager (Phía Máy Chủ):
 * Quản lý không gian lưu trữ file trên ổ đĩa của server và lập chỉ mục metadata.
 * Đã nâng cấp hỗ trợ:
 * - Bộ lọc loại trừ .syncignore
 * - Giải nén GZIP tự động trên luồng
 * - Lịch sử phiên bản (Versioning) & Khôi phục (Rollback)
 * - Thùng rác an toàn (Recycle Bin / Soft Delete)
 */
public class StorageManager {
    private final File rootDirectory;
    private final SyncIgnoreFilter ignoreFilter;

    public StorageManager(File rootDirectory) {
        if (!rootDirectory.exists()) {
            rootDirectory.mkdirs();
        }
        this.rootDirectory = rootDirectory;
        this.ignoreFilter = new SyncIgnoreFilter(rootDirectory);
    }

    public File getRootDirectory() {
        return rootDirectory;
    }

    /**
     * Quét toàn bộ thư mục lưu trữ và lập chỉ mục danh sách siêu dữ liệu FileMetadata.
     * Áp dụng bộ lọc loại trừ .syncignore.
     */
    public List<FileMetadata> scanFiles() {
        List<FileMetadata> list = new ArrayList<>();
        if (!rootDirectory.exists() || !rootDirectory.isDirectory()) {
            return list;
        }

        Path rootPath = rootDirectory.toPath().toAbsolutePath().normalize();

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
     * Sao lưu phiên bản hiện tại của tệp trước khi bị ghi đè (File Versioning).
     */
    public void backupFileVersion(String relativePath, String username, int currentVersion, DatabaseManager db) {
        File targetFile = getFile(relativePath);
        if (targetFile == null || !targetFile.exists() || !targetFile.isFile() || currentVersion <= 0) {
            return;
        }

        try {
            String safeUser = (username != null && !username.isEmpty()) ? username : "general";
            File versionsBaseDir = new File("SyncData/versions", safeUser);
            File fileVerDir = new File(versionsBaseDir, relativePath);
            if (!fileVerDir.exists()) {
                fileVerDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String backupFileName = "v" + currentVersion + "_" + timestamp + "_" + targetFile.getName();
            File backupFile = new File(fileVerDir, backupFileName);

            Files.copy(targetFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            if (db != null) {
                String sha256 = HashUtils.calculateSHA256(targetFile);
                db.addFileVersion(safeUser, relativePath, currentVersion, targetFile.length(), sha256, backupFile.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("Lỗi khi tạo sao lưu phiên bản tệp: " + e.getMessage());
        }
    }

    /**
     * Khôi phục tệp về một phiên bản lịch sử cũ (Rollback).
     */
    public boolean restoreFileVersion(String relativePath, String backupPath) {
        if (!FileUtils.isPathSafe(relativePath)) return false;
        File targetFile = new File(rootDirectory, relativePath);
        File backupFile = new File(backupPath);

        if (!backupFile.exists() || !backupFile.isFile()) return false;

        try {
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            Files.copy(backupFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            FileUtils.setLastModified(targetFile, System.currentTimeMillis());
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Chuyển tệp vào thùng rác an toàn thay vì xóa vĩnh viễn (Soft Delete / Recycle Bin).
     */
    public boolean moveToRecycleBin(String relativePath, String username, DatabaseManager db) {
        File targetFile = getFile(relativePath);
        if (targetFile == null || !targetFile.exists() || !targetFile.isFile()) {
            return false;
        }

        try {
            String safeUser = (username != null && !username.isEmpty()) ? username : "general";
            File trashBaseDir = new File("SyncData/.trash", safeUser);
            if (!trashBaseDir.exists()) {
                trashBaseDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File trashFile = new File(trashBaseDir, timestamp + "_" + targetFile.getName());

            long fileSize = targetFile.length();
            Files.move(targetFile.toPath(), trashFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            if (db != null) {
                db.addRecycleBin(safeUser, relativePath, trashFile.getAbsolutePath(), fileSize);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Khôi phục tệp từ thùng rác về vị trí ban đầu.
     */
    public boolean restoreRecycledFile(String relativePath, String trashPath) {
        if (!FileUtils.isPathSafe(relativePath)) return false;
        File targetFile = new File(rootDirectory, relativePath);
        File trashFile = new File(trashPath);

        if (!trashFile.exists() || !trashFile.isFile()) return false;

        try {
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            Files.move(trashFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            FileUtils.setLastModified(targetFile, System.currentTimeMillis());
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Nhận và lưu tệp từ luồng InputStream theo từng khối byte (Chunk 64KB).
     * Hỗ trợ giải nén tự động GZIP nếu bên gửi nén dữ liệu.
     */
    public boolean saveFileChunked(String relativePath, InputStream in, long fileSize, long lastModified, String expectedSHA256) throws IOException {
        return saveFileChunked(relativePath, in, fileSize, lastModified, expectedSHA256, false, 0, 0);
    }

    public boolean saveFileChunked(String relativePath, InputStream in, long streamSize, long lastModified,
                                   String expectedSHA256, boolean isCompressed, long originalSize, long offset) throws IOException {
        if (!FileUtils.isPathSafe(relativePath)) {
            throw new SecurityException("Đường dẫn tệp không an toàn hoặc chứa ký tự bị cấm");
        }

        File targetFile = new File(rootDirectory, relativePath);
        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // Trường hợp 1: Dữ liệu được nén GZIP trên đường truyền
        if (isCompressed) {
            File tempGzFile = new File(parentDir, targetFile.getName() + ".download.gz");
            try {
                // Nhận toàn bộ khối nén vào file tạm
                try (FileOutputStream fos = new FileOutputStream(tempGzFile);
                     BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                    byte[] buffer = new byte[64 * 1024];
                    long bytesRemaining = streamSize;
                    while (bytesRemaining > 0) {
                        int toRead = (int) Math.min(buffer.length, bytesRemaining);
                        int bytesRead = in.read(buffer, 0, toRead);
                        if (bytesRead == -1) {
                            throw new EOFException("Luồng dữ liệu kết thúc sớm trước khi nhận đủ tệp nén");
                        }
                        bos.write(buffer, 0, bytesRead);
                        bytesRemaining -= bytesRead;
                    }
                    bos.flush();
                }

                // Giải nén file tạm ra file đích và tính SHA-256 nội dung giải nén
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (GZIPInputStream gis = new GZIPInputStream(new BufferedInputStream(new FileInputStream(tempGzFile)));
                     FileOutputStream fos = new FileOutputStream(targetFile);
                     BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                    byte[] buffer = new byte[64 * 1024];
                    int r;
                    while ((r = gis.read(buffer)) != -1) {
                        bos.write(buffer, 0, r);
                        digest.update(buffer, 0, r);
                    }
                    bos.flush();
                }

                FileUtils.setLastModified(targetFile, lastModified);

                // Kiểm tra SHA-256
                String actualSHA256 = HashUtils.calculateSHA256(targetFile);
                if (expectedSHA256 != null && !expectedSHA256.isEmpty() && !actualSHA256.equalsIgnoreCase(expectedSHA256)) {
                    targetFile.delete();
                    throw new IOException("Mã băm SHA-256 không khớp sau khi giải nén GZIP!");
                }

                return true;
            } catch (Exception e) {
                if (targetFile.exists()) targetFile.delete();
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException(e.getMessage());
            } finally {
                if (tempGzFile.exists()) tempGzFile.delete();
            }
        }

        // Trường hợp 2: Dữ liệu bình thường (hỗ trợ resumable transfer với offset)
        File partFile = (offset > 0) ? new File(parentDir, targetFile.getName() + ".part") : targetFile;
        boolean append = (offset > 0 && partFile.exists());

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024]; // Khối 64KB
            long bytesRemaining = streamSize;

            try (FileOutputStream fos = new FileOutputStream(partFile, append);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                while (bytesRemaining > 0) {
                    int toRead = (int) Math.min(buffer.length, bytesRemaining);
                    int bytesRead = in.read(buffer, 0, toRead);
                    if (bytesRead == -1) {
                        throw new EOFException("Luồng dữ liệu kết thúc sớm trước khi nhận đủ tệp");
                    }
                    bos.write(buffer, 0, bytesRead);
                    bytesRemaining -= bytesRead;
                }
                bos.flush();
            }

            if (offset > 0 && partFile.exists()) {
                Files.move(partFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            FileUtils.setLastModified(targetFile, lastModified);

            // Kiểm tra mã băm SHA-256
            String actualSHA256 = HashUtils.calculateSHA256(targetFile);
            if (expectedSHA256 != null && !expectedSHA256.isEmpty() && !actualSHA256.equalsIgnoreCase(expectedSHA256)) {
                targetFile.delete(); // Xóa tệp hỏng
                throw new IOException("Mã băm SHA-256 không khớp! Nhận: " + actualSHA256 + " - Kỳ vọng: " + expectedSHA256);
            }

            return true;
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getMessage());
        }
    }

    /**
     * Truyền nội dung tệp ra luồng OutputStream theo từng khối byte (Chunk 64KB).
     */
    public void writeFileChunked(String relativePath, OutputStream out) throws IOException {
        if (!FileUtils.isPathSafe(relativePath)) {
            throw new SecurityException("Đường dẫn tệp không an toàn");
        }

        File sourceFile = new File(rootDirectory, relativePath);
        if (!sourceFile.exists() || sourceFile.isDirectory()) {
            throw new FileNotFoundException("Tệp không tồn tại trên Server: " + relativePath);
        }

        byte[] buffer = new byte[64 * 1024];
        long bytesRemaining = sourceFile.length();

        try (FileInputStream fis = new FileInputStream(sourceFile);
             BufferedInputStream bis = new BufferedInputStream(fis)) {

            while (bytesRemaining > 0) {
                int toRead = (int) Math.min(buffer.length, bytesRemaining);
                int bytesRead = bis.read(buffer, 0, toRead);
                if (bytesRead == -1) break;
                out.write(buffer, 0, bytesRead);
                bytesRemaining -= bytesRead;
            }
            out.flush();
        }
    }

    public File getFile(String relativePath) {
        if (!FileUtils.isPathSafe(relativePath)) return null;
        return new File(rootDirectory, relativePath);
    }

    public boolean deleteFile(String relativePath) {
        File file = getFile(relativePath);
        if (file != null && file.exists() && file.isFile()) {
            return file.delete();
        }
        return false;
    }
}
