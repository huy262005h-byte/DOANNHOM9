package com.mycompany.doancs.common;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

/**
 * Thông tin siêu dữ liệu (Metadata) của tệp tin.
 * Được truyền trong Packet giữa Client và Server để so sánh tệp.
 */
public class FileMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String relativePath; // Đường dẫn tương đối chuẩn hóa (dùng '/')
    private final long size;           // Kích thước (bytes)
    private final long lastModified;   // Thời gian sửa đổi cuối cùng (epoch millis)
    private final String sha256;       // Mã băm SHA-256
    private final boolean isDirectory; // Có phải thư mục không

    public FileMetadata(String relativePath, long size, long lastModified, String sha256, boolean isDirectory) {
        this.relativePath = relativePath != null ? relativePath.replace('\\', '/') : "";
        this.size = size;
        this.lastModified = lastModified;
        this.sha256 = sha256 != null ? sha256 : "";
        this.isDirectory = isDirectory;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public long getSize() {
        return size;
    }

    public long getLastModified() {
        return lastModified;
    }

    public String getSha256() {
        return sha256;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public String getFileName() {
        int idx = relativePath.lastIndexOf('/');
        return idx >= 0 ? relativePath.substring(idx + 1) : relativePath;
    }

    public String getFormattedSize() {
        if (isDirectory) return "<DIR>";
        if (size < 1024) return size + " B";
        int exp = (int) (Math.log(size) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %sB", size / Math.pow(1024, exp), pre);
    }

    public String getFormattedDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new Date(lastModified));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileMetadata that = (FileMetadata) o;
        return size == that.size &&
                Math.abs(lastModified - that.lastModified) <= 1000 &&
                Objects.equals(relativePath, that.relativePath) &&
                Objects.equals(sha256, that.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relativePath, size, sha256);
    }

    @Override
    public String toString() {
        return "FileMetadata{" +
                "path='" + relativePath + '\'' +
                ", size=" + size +
                ", sha256='" + sha256 + '\'' +
                '}';
    }
}
