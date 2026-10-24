package com.mycompany.doancs.model;

import java.text.SimpleDateFormat;
import java.util.Date;

public class ClientSessionInfo {
    private final String clientId;
    private final String ipAddress;
    private final int port;
    private String username = "Chưa xác thực";
    private final long connectedTime;
    private long lastActiveTime;
    private int filesTransferred;
    private long bytesTransferred;
    private String currentStatus;

    public ClientSessionInfo(String clientId, String ipAddress, int port) {
        this.clientId = clientId;
        this.ipAddress = ipAddress;
        this.port = port;
        this.connectedTime = System.currentTimeMillis();
        this.lastActiveTime = this.connectedTime;
        this.filesTransferred = 0;
        this.bytesTransferred = 0;
        this.currentStatus = "Đã kết nối";
    }

    public synchronized void recordTransfer(long bytes) {
        filesTransferred++;
        bytesTransferred += bytes;
        lastActiveTime = System.currentTimeMillis();
    }

    public String getClientId() { return clientId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getIpAddress() { return ipAddress; }
    public int getPort() { return port; }
    public long getConnectedTime() { return connectedTime; }
    public long getLastActiveTime() { return lastActiveTime; }
    public void setLastActiveTime(long lastActiveTime) { this.lastActiveTime = lastActiveTime; }
    public int getFilesTransferred() { return filesTransferred; }
    public long getBytesTransferred() { return bytesTransferred; }
    public String getCurrentStatus() { return currentStatus; }
    public void setCurrentStatus(String currentStatus) { this.currentStatus = currentStatus; }

    public String getFormattedConnectedTime() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date(connectedTime));
    }

    public String getFormattedTransferredSize() {
        if (bytesTransferred < 1024) return bytesTransferred + " B";
        int exp = (int) (Math.log(bytesTransferred) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %sB", bytesTransferred / Math.pow(1024, exp), pre);
    }
}
