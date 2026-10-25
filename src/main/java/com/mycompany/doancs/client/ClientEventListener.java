package com.mycompany.doancs.client;

import com.mycompany.doancs.model.SyncItem;
import com.mycompany.doancs.model.SyncStats;

import java.util.List;

public interface ClientEventListener {
    enum LogLevel {
        INFO, SUCCESS, WARN, ERROR
    }

    void onConnected(String host, int port);
    void onDisconnected();
    void onScanCompleted(List<SyncItem> comparisonList, SyncStats stats);
    void onSyncStarted(int totalItems, long totalBytes);
    void onItemProgress(SyncItem item, int percent, long bytesTransferred, long fileSize);
    void onItemCompleted(SyncItem item, boolean success, String message);
    void onSyncFinished(SyncStats stats);
    void onLog(LogLevel level, String message);
    default void onServerChangeNotification(String fileName, String action) {}
}
