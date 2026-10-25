package com.mycompany.doancs.model;

/**
 * Mục so sánh tệp giữa Client và Server.
 */
public class SyncItem {
    private final String relativePath;
    private final FileInfo localInfo;
    private final FileInfo remoteInfo;
    private SyncAction action;
    private String reason;
    private String transferStatus; // "Chờ xử lý", "Đang truyền...", "Thành công", "Lỗi", "Bỏ qua"
    private int progressPercent;
    private String errorMessage;

    public SyncItem(String relativePath, FileInfo localInfo, FileInfo remoteInfo, SyncAction action, String reason) {
        this.relativePath = relativePath;
        this.localInfo = localInfo;
        this.remoteInfo = remoteInfo;
        this.action = action;
        this.reason = reason;
        this.transferStatus = action == SyncAction.UP_TO_DATE ? "Đã đồng bộ" : "Chờ xử lý";
        this.progressPercent = action == SyncAction.UP_TO_DATE ? 100 : 0;
        this.errorMessage = "";
    }

    public String getRelativePath() {
        return relativePath;
    }

    public FileInfo getLocalInfo() {
        return localInfo;
    }

    public FileInfo getRemoteInfo() {
        return remoteInfo;
    }

    public SyncAction getAction() {
        return action;
    }

    public void setAction(SyncAction action) {
        this.action = action;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getTransferStatus() {
        return transferStatus;
    }

    public void setTransferStatus(String transferStatus) {
        this.transferStatus = transferStatus;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getFileSizeToTransfer() {
        if (action == SyncAction.UPLOAD && localInfo != null) {
            return localInfo.getSize();
        } else if (action == SyncAction.DOWNLOAD && remoteInfo != null) {
            return remoteInfo.getSize();
        }
        return 0;
    }
}
