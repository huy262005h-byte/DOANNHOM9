package com.mycompany.doancs.model;

import java.io.Serializable;

/**
 * Thông tin chi tiết về một phiên bản lịch sử của tệp tin (File Versioning).
 */
public class FileVersionInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private int id;
    private int version;
    private String username;
    private String relativePath;
    private long fileSize;
    private String sha256;
    private String backupPath;
    private String createdAt;

    public FileVersionInfo() {}

    public FileVersionInfo(int id, int version, String username, String relativePath, long fileSize, String sha256, String backupPath, String createdAt) {
        this.id = id;
        this.version = version;
        this.username = username;
        this.relativePath = relativePath;
        this.fileSize = fileSize;
        this.sha256 = sha256;
        this.backupPath = backupPath;
        this.createdAt = createdAt;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getBackupPath() { return backupPath; }
    public void setBackupPath(String backupPath) { this.backupPath = backupPath; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "v" + version + " (" + createdAt + ", " + fileSize + " B)";
    }
}
