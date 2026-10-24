package com.mycompany.doancs.model;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

/**
 * Đại diện cho thông tin siêu dữ liệu (metadata) của một tệp tin.
 */
public class FileInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String relativePath;  // Đường dẫn tương đối chuẩn hóa (dùng '/')
    private final long size;            // Kích thước (bytes)
    private final long lastModified;    // Thời gian sửa đổi cuối (epoch millis)
    private final String checksum;      // Mã kiểm tra (CRC32 hoặc SHA-256)
    private final boolean isDirectory;  // Có phải thư mục không

    public FileInfo(String relativePath, long size, long lastModified, String checksum, boolean isDirectory) {
        this.relativePath = relativePath.replace('\\', '/');
        this.size = size;
        this.lastModified = lastModified;
        this.checksum = checksum != null ? checksum : "";
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

    public String getChecksum() {
        return checksum;
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
        FileInfo fileInfo = (FileInfo) o;
        return size == fileInfo.size &&
                Math.abs(lastModified - fileInfo.lastModified) <= 1000 &&
                Objects.equals(relativePath, fileInfo.relativePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relativePath, size);
    }

    @Override
    public String toString() {
        return "FileInfo{" +
                "path='" + relativePath + '\'' +
                ", size=" + size +
                ", lastModified=" + getFormattedDate() +
                ", checksum='" + checksum + '\'' +
                '}';
    }
}
