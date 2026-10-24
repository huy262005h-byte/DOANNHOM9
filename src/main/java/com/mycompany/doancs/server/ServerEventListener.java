package com.mycompany.doancs.server;

import com.mycompany.doancs.model.ClientSessionInfo;

public interface ServerEventListener {
    enum LogLevel {
        INFO, SUCCESS, WARN, ERROR
    }

    void onServerStarted(int port, String sharedPath);
    void onServerStopped();
    void onClientConnected(ClientSessionInfo client);
    void onClientDisconnected(ClientSessionInfo client);
    void onFileReceived(String clientAddress, String relativePath, long size);
    void onFileSent(String clientAddress, String relativePath, long size);
    void onLog(LogLevel level, String message);
    void onStatsUpdated();
}
