package com.mycompany.doancs.server;

import com.mycompany.doancs.common.Packet;
import com.mycompany.doancs.model.ClientSessionInfo;
import com.mycompany.doancs.server.db.DatabaseManager;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/**
 * Server Core (Phía Máy Chủ):
 * Mở cổng mạng TCP (Port) lắng nghe liên tục các kết nối từ client,
 * điều phối các luồng ClientHandler qua Thread Pool và quản lý StorageManager.
 */
public class ServerCore {
    private ServerSocket serverSocket;
    private StorageManager storageManager;
    private DatabaseManager databaseManager;
    private int port;
    private volatile boolean running = false;
    private final List<ServerEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, ClientSessionInfo> activeClients = new ConcurrentHashMap<>();
    private final List<ClientHandler> activeHandlers = new CopyOnWriteArrayList<>();
    private final Map<String, List<ClientHandler>> userNotificationChannels = new ConcurrentHashMap<>();
    private final List<ClientHandler> allNotificationChannels = new CopyOnWriteArrayList<>();
    private ExecutorService threadPool;
    private Thread acceptThread;

    public synchronized void start(int port, File sharedDirectory) throws IOException {
        if (running) {
            throw new IllegalStateException("Máy chủ đang chạy!");
        }

        if (sharedDirectory == null) {
            throw new IllegalArgumentException("Thư mục chia sẻ không hợp lệ!");
        }

        this.port = port;
        this.storageManager = new StorageManager(sharedDirectory);

        // Khởi tạo Database SQLite tại SyncData/server_database.db
        File parentDir = sharedDirectory.getParentFile();
        File dbDir = (parentDir != null && parentDir.exists()) ? parentDir : new File("SyncData");
        File dbFile = new File(dbDir, "server_database.db");
        this.databaseManager = new DatabaseManager(dbFile);

        this.serverSocket = new ServerSocket(port);
        this.running = true;
        // Tận dụng tính năng Java 21/22 Virtual Threads (Project Loom) cho Server Core
        this.threadPool = Executors.newVirtualThreadPerTaskExecutor();

        acceptThread = Thread.ofVirtual().name("ServerCore-AcceptThread").unstarted(this::listenForClients);
        acceptThread.start();

        for (ServerEventListener l : listeners) {
            l.onServerStarted(port, sharedDirectory.getAbsolutePath());
        }
        log(ServerEventListener.LogLevel.INFO, "Máy chủ đã mở cổng TCP " + port + " và sẵn sàng lắng nghe.");
        log(ServerEventListener.LogLevel.INFO, "Đã kích hoạt Java 22 Virtual Threads (Project Loom) cho Server Core.");
        log(ServerEventListener.LogLevel.INFO, "Thư mục lưu trữ: " + sharedDirectory.getAbsolutePath());
        log(ServerEventListener.LogLevel.INFO, "Cơ sở dữ liệu SQLite: " + dbFile.getAbsolutePath());
    }

    private void listenForClients() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket, this, storageManager);
                activeHandlers.add(handler);
                threadPool.execute(handler); // Chạy luồng riêng biệt cho từng client
            } catch (IOException e) {
                if (running) {
                    log(ServerEventListener.LogLevel.ERROR, "Lỗi khi chấp nhận kết nối: " + e.getMessage());
                }
            }
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;

        log(ServerEventListener.LogLevel.WARN, "Đang dừng máy chủ...");

        for (ClientHandler handler : activeHandlers) {
            handler.close();
        }
        activeHandlers.clear();
        activeClients.clear();
        allNotificationChannels.clear();
        userNotificationChannels.clear();

        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        for (ServerEventListener l : listeners) {
            l.onServerStopped();
        }
        log(ServerEventListener.LogLevel.INFO, "Máy chủ đã dừng hoàn toàn.");
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public StorageManager getStorageManager() {
        return storageManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public File getSharedDirectory() {
        return storageManager != null ? storageManager.getRootDirectory() : null;
    }

    public void registerNotificationChannel(String username, ClientHandler handler) {
        if (handler == null) return;
        if (!allNotificationChannels.contains(handler)) {
            allNotificationChannels.add(handler);
        }
        if (username != null) {
            userNotificationChannels.computeIfAbsent(username.toLowerCase(), k -> new CopyOnWriteArrayList<>()).add(handler);
            log(ServerEventListener.LogLevel.INFO, "[Push Event] Đã đăng ký kênh đẩy thông báo cho tài khoản: @" + username);
        }
    }

    public void unregisterNotificationChannel(String username, ClientHandler handler) {
        if (handler == null) return;
        allNotificationChannels.remove(handler);
        if (username != null) {
            List<ClientHandler> list = userNotificationChannels.get(username.toLowerCase());
            if (list != null) {
                list.remove(handler);
            }
        }
    }

    public void broadcastFileChange(String username, String fileName, String action, ClientHandler originHandler) {
        Packet pushPacket = Packet.createServerNotifyChange(fileName, action);
        int count = 0;
        for (ClientHandler ch : allNotificationChannels) {
            if (originHandler != null) {
                // 1. Không gửi lại cho chính handler gốc
                if (ch == originHandler) continue;

                // 2. Không gửi cho kênh push thuộc cùng phiên làm việc (cùng sessionToken)
                if (originHandler.getSessionToken() != null && ch.getSessionToken() != null
                        && originHandler.getSessionToken().equals(ch.getSessionToken())) {
                    continue;
                }

                // 3. Không gửi cho kênh push có cùng địa chỉ IP và cùng tài khoản người dùng
                if (originHandler.getSessionInfo() != null && ch.getSessionInfo() != null
                        && originHandler.getSessionInfo().getIpAddress().equals(ch.getSessionInfo().getIpAddress())
                        && Objects.equals(originHandler.getAuthenticatedUser(), ch.getAuthenticatedUser())) {
                    continue;
                }
            }

            if (ch.isRunning()) {
                ch.sendPacket(pushPacket);
                count++;
            }
        }
        if (count > 0) {
            String userTag = (username != null && !username.isEmpty()) ? "@" + username : "Máy chủ";
            log(ServerEventListener.LogLevel.SUCCESS, "[Push Event] " + userTag + " thay đổi tệp '" + fileName + "' (" + action + ") -> Đã phát sóng tới " + count + " client khác.");
        }
    }

    public void addListener(ServerEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ServerEventListener listener) {
        listeners.remove(listener);
    }

    public Collection<ClientSessionInfo> getActiveClients() {
        return activeClients.values();
    }

    void notifyClientConnected(ClientSessionInfo client) {
        activeClients.put(client.getClientId(), client);
        for (ServerEventListener l : listeners) {
            l.onClientConnected(client);
        }
    }

    void notifyClientDisconnected(ClientSessionInfo client) {
        activeClients.remove(client.getClientId());
        for (ServerEventListener l : listeners) {
            l.onClientDisconnected(client);
        }
    }

    void notifyFileReceived(String clientAddress, String relativePath, long size) {
        for (ServerEventListener l : listeners) {
            l.onFileReceived(clientAddress, relativePath, size);
        }
    }

    void notifyFileSent(String clientAddress, String relativePath, long size) {
        for (ServerEventListener l : listeners) {
            l.onFileSent(clientAddress, relativePath, size);
        }
    }

    void notifyStatsUpdated() {
        for (ServerEventListener l : listeners) {
            l.onStatsUpdated();
        }
    }

    public void log(ServerEventListener.LogLevel level, String message) {
        for (ServerEventListener l : listeners) {
            l.onLog(level, message);
        }
    }

    /**
     * Lấy danh sách IP khả dụng của máy chủ để hiển thị trên giao diện.
     */
    public static List<String> getAvailableIPAddresses() {
        List<String> ips = new ArrayList<>();
        ips.add("127.0.0.1 (Localhost)");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) { // IPv4
                        ips.add(addr.getHostAddress() + " (" + iface.getDisplayName() + ")");
                    }
                }
            }
        } catch (Exception ignored) {}
        return ips;
    }
}
