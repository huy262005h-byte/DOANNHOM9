package com.mycompany.doancs.model;

public class SyncStats {
    private int totalFiles;
    private int upToDateCount;
    private int uploadCount;
    private int downloadCount;
    private int conflictCount;
    private long totalBytesToTransfer;
    private long bytesTransferredSoFar;
    private long startTime;
    private long endTime;
    private boolean isRunning;

    public void reset() {
        totalFiles = 0;
        upToDateCount = 0;
        uploadCount = 0;
        downloadCount = 0;
        conflictCount = 0;
        totalBytesToTransfer = 0;
        bytesTransferredSoFar = 0;
        startTime = System.currentTimeMillis();
        endTime = 0;
        isRunning = true;
    }

    public void finish() {
        endTime = System.currentTimeMillis();
        isRunning = false;
    }

    public synchronized void addBytesTransferred(long bytes) {
        bytesTransferredSoFar += bytes;
    }

    public double getSpeedKBps() {
        long elapsed = (isRunning ? System.currentTimeMillis() : endTime) - startTime;
        if (elapsed <= 0) return 0.0;
        return (bytesTransferredSoFar / 1024.0) / (elapsed / 1000.0);
    }

    public String getFormattedSpeed() {
        double speed = getSpeedKBps();
        if (speed < 1024) {
            return String.format("%.1f KB/s", speed);
        } else {
            return String.format("%.2f MB/s", speed / 1024.0);
        }
    }

    public int getOverallProgressPercent() {
        if (totalBytesToTransfer <= 0) return 100;
        int pct = (int) ((bytesTransferredSoFar * 100) / totalBytesToTransfer);
        return Math.min(100, Math.max(0, pct));
    }

    // Getters and setters
    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }
    public int getUpToDateCount() { return upToDateCount; }
    public void setUpToDateCount(int upToDateCount) { this.upToDateCount = upToDateCount; }
    public int getUploadCount() { return uploadCount; }
    public void setUploadCount(int uploadCount) { this.uploadCount = uploadCount; }
    public int getDownloadCount() { return downloadCount; }
    public void setDownloadCount(int downloadCount) { this.downloadCount = downloadCount; }
    public int getConflictCount() { return conflictCount; }
    public void setConflictCount(int conflictCount) { this.conflictCount = conflictCount; }
    public long getTotalBytesToTransfer() { return totalBytesToTransfer; }
    public void setTotalBytesToTransfer(long totalBytesToTransfer) { this.totalBytesToTransfer = totalBytesToTransfer; }
    public long getBytesTransferredSoFar() { return bytesTransferredSoFar; }
    public void setBytesTransferredSoFar(long bytesTransferredSoFar) { this.bytesTransferredSoFar = bytesTransferredSoFar; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public boolean isRunning() { return isRunning; }
}
