package com.mycompany.doancs.server.db;

import com.mycompany.doancs.common.FileMetadata;
import com.mycompany.doancs.model.FileVersionInfo;

import java.io.File;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;

/**
 * Quản trị Cơ sở dữ liệu SQLite phía Server (Database Manager).
 * Đảm nhiệm:
 * 1. Xác thực tài khoản người dùng (Authentication: Register, Login, Session Token).
 * 2. Lưu trữ và đồng bộ siêu dữ liệu tệp (Metadata Repository: Size, Timestamp, SHA-256, Version).
 * 3. Nhật ký ghi nhận các trường hợp xung đột dữ liệu (Conflict Logs).
 */
public class DatabaseManager {
    private final String dbUrl;
    private final SecureRandom random = new SecureRandom();

    public DatabaseManager(File dbFile) {
        if (dbFile.getParentFile() != null && !dbFile.getParentFile().exists()) {
            dbFile.getParentFile().mkdirs();
        }
        this.dbUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        initDatabase();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    private void initDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA busy_timeout = 5000;");

            // 1. Bảng tài khoản người dùng
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "username TEXT UNIQUE NOT NULL, " +
                    "password_hash TEXT NOT NULL, " +
                    "salt TEXT NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            // 2. Bảng phiên đăng nhập (Session Tokens)
            stmt.execute("CREATE TABLE IF NOT EXISTS sessions (" +
                    "token TEXT PRIMARY KEY, " +
                    "username TEXT NOT NULL, " +
                    "client_ip TEXT, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            // 3. Bảng siêu dữ liệu tệp tin (File Metadata Index)
            stmt.execute("CREATE TABLE IF NOT EXISTS files_metadata (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "username TEXT NOT NULL, " +
                    "relative_path TEXT NOT NULL, " +
                    "file_size INTEGER NOT NULL, " +
                    "sha256 TEXT NOT NULL, " +
                    "last_modified INTEGER NOT NULL, " +
                    "version INTEGER DEFAULT 1, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "UNIQUE(username, relative_path))");

            // 4. Bảng ghi nhận các xung đột tệp (Conflict Resolution Logs)
            stmt.execute("CREATE TABLE IF NOT EXISTS conflict_logs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "username TEXT NOT NULL, " +
                    "original_path TEXT NOT NULL, " +
                    "conflict_path TEXT NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            // 5. Bảng lịch sử các phiên bản tệp (File Versioning & History)
            stmt.execute("CREATE TABLE IF NOT EXISTS file_versions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "username TEXT NOT NULL, " +
                    "relative_path TEXT NOT NULL, " +
                    "version INTEGER NOT NULL, " +
                    "file_size INTEGER NOT NULL, " +
                    "sha256 TEXT NOT NULL, " +
                    "backup_path TEXT NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            // 6. Bảng thùng rác an toàn (Recycle Bin / Soft Delete)
            stmt.execute("CREATE TABLE IF NOT EXISTS recycle_bin (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "username TEXT NOT NULL, " +
                    "relative_path TEXT NOT NULL, " +
                    "trash_path TEXT NOT NULL, " +
                    "file_size INTEGER NOT NULL, " +
                    "deleted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            // Tạo sẵn các tài khoản mặc định nếu cơ sở dữ liệu mới khởi tạo
            seedDefaultUsers();

        } catch (SQLException e) {
            System.err.println("Lỗi khởi tạo cơ sở dữ liệu SQLite: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void seedDefaultUsers() {
        if (!userExists("admin")) {
            registerUser("admin", "admin123");
        }
        if (!userExists("user1")) {
            registerUser("user1", "123456");
        }
        if (!userExists("user2")) {
            registerUser("user2", "123456");
        }
    }

    // --- Authentication (Xác thực tài khoản) ---

    public boolean userExists(String username) {
        String sql = "SELECT id FROM users WHERE username = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized boolean registerUser(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            return false;
        }
        String salt = generateSalt();
        String hash = hashPassword(password, salt);

        String sql = "INSERT INTO users (username, password_hash, salt) VALUES (?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.trim().toLowerCase());
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false; // Trùng username hoặc lỗi
        }
    }

    public synchronized String authenticateUser(String username, String password, String clientIp) {
        if (username == null || password == null) return null;
        String cleanUser = username.trim().toLowerCase();

        String storedHash = null;
        String salt = null;

        String sql = "SELECT password_hash, salt FROM users WHERE username = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cleanUser);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    storedHash = rs.getString("password_hash");
                    salt = rs.getString("salt");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (storedHash != null && salt != null) {
            String computedHash = hashPassword(password, salt);
            if (storedHash.equalsIgnoreCase(computedHash)) {
                // Mật khẩu đúng -> Tạo Session Token (UUID)
                String token = UUID.randomUUID().toString().replace("-", "");
                createSession(token, cleanUser, clientIp);
                return token;
            }
        }
        return null;
    }

    private void createSession(String token, String username, String clientIp) {
        String sql = "INSERT INTO sessions (token, username, client_ip) VALUES (?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, username);
            ps.setString(3, clientIp);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String validateToken(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        String sql = "SELECT username FROM sessions WHERE token = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("username");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void revokeSession(String token) {
        String sql = "DELETE FROM sessions WHERE token = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    // --- Metadata Management (Lập chỉ mục siêu dữ liệu tệp) ---

    public synchronized void upsertFileMetadata(String username, FileMetadata meta) {
        String sql = "INSERT INTO files_metadata (username, relative_path, file_size, sha256, last_modified, version, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP) " +
                "ON CONFLICT(username, relative_path) DO UPDATE SET " +
                "file_size = excluded.file_size, " +
                "sha256 = excluded.sha256, " +
                "last_modified = excluded.last_modified, " +
                "version = version + 1, " +
                "updated_at = CURRENT_TIMESTAMP";

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, meta.getRelativePath());
            ps.setLong(3, meta.getSize());
            ps.setString(4, meta.getSha256());
            ps.setLong(5, meta.getLastModified());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public synchronized void deleteFileMetadata(String relativePath) {
        String sql = "DELETE FROM files_metadata WHERE relative_path = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, relativePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public synchronized void deleteFileMetadata(String username, String relativePath) {
        if (username == null || username.isEmpty() || username.equalsIgnoreCase("admin") || username.equalsIgnoreCase("general")) {
            deleteFileMetadata(relativePath);
            return;
        }
        String sql = "DELETE FROM files_metadata WHERE relative_path = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, relativePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<FileMetadata> getFileMetadataList(String username) {
        List<FileMetadata> list = new ArrayList<>();
        String sql = "SELECT relative_path, file_size, sha256, last_modified FROM files_metadata WHERE username = ? ORDER BY relative_path ASC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new FileMetadata(
                            rs.getString("relative_path"),
                            rs.getLong("file_size"),
                            rs.getLong("last_modified"),
                            rs.getString("sha256"),
                            false
                    ));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public List<Map<String, String>> getAllFileMetadata() {
        List<Map<String, String>> list = new ArrayList<>();
        String sql = "SELECT username, relative_path, file_size, sha256, version, updated_at FROM files_metadata ORDER BY id DESC";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("user", rs.getString("username"));
                row.put("path", rs.getString("relative_path"));
                row.put("size", String.valueOf(rs.getLong("file_size")));
                row.put("sha256", rs.getString("sha256"));
                row.put("version", String.valueOf(rs.getInt("version")));
                row.put("updated_at", rs.getString("updated_at"));
                list.add(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // --- Conflict Logging (Nhật ký xử lý xung đột) ---

    public synchronized void logConflict(String username, String originalPath, String conflictPath) {
        String sql = "INSERT INTO conflict_logs (username, original_path, conflict_path) VALUES (?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, originalPath);
            ps.setString(3, conflictPath);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<Map<String, String>> getAllConflictLogs() {
        List<Map<String, String>> list = new ArrayList<>();
        String sql = "SELECT username, original_path, conflict_path, created_at FROM conflict_logs ORDER BY id DESC LIMIT 50";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("user", rs.getString("username"));
                row.put("original", rs.getString("original_path"));
                row.put("conflict", rs.getString("conflict_path"));
                row.put("time", rs.getString("created_at"));
                list.add(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public List<Map<String, String>> getAllRegisteredUsers() {
        List<Map<String, String>> list = new ArrayList<>();
        String sql = "SELECT id, username, created_at FROM users ORDER BY id ASC";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(rs.getInt("id")));
                row.put("username", rs.getString("username"));
                row.put("created_at", rs.getString("created_at"));
                list.add(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // --- File Versioning Management (Quản lý phiên bản tệp) ---

    public int getCurrentVersion(String username, String relativePath) {
        String sql = "SELECT version FROM files_metadata WHERE username = ? AND relative_path = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, relativePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("version");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public synchronized void addFileVersion(String username, String relativePath, int version, long size, String sha256, String backupPath) {
        String sql = "INSERT INTO file_versions (username, relative_path, version, file_size, sha256, backup_path) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, relativePath);
            ps.setInt(3, version);
            ps.setLong(4, size);
            ps.setString(5, sha256);
            ps.setString(6, backupPath);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<FileVersionInfo> getFileVersions(String username, String relativePath) {
        List<FileVersionInfo> list = new ArrayList<>();
        String sql = "SELECT id, version, username, relative_path, file_size, sha256, backup_path, created_at FROM file_versions " +
                "WHERE username = ? AND relative_path = ? ORDER BY version DESC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, relativePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new FileVersionInfo(
                            rs.getInt("id"),
                            rs.getInt("version"),
                            rs.getString("username"),
                            rs.getString("relative_path"),
                            rs.getLong("file_size"),
                            rs.getString("sha256"),
                            rs.getString("backup_path"),
                            rs.getString("created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public FileVersionInfo getVersionById(int versionId) {
        String sql = "SELECT id, version, username, relative_path, file_size, sha256, backup_path, created_at FROM file_versions WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new FileVersionInfo(
                            rs.getInt("id"),
                            rs.getInt("version"),
                            rs.getString("username"),
                            rs.getString("relative_path"),
                            rs.getLong("file_size"),
                            rs.getString("sha256"),
                            rs.getString("backup_path"),
                            rs.getString("created_at")
                    );
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // --- Recycle Bin Management (Quản lý thùng rác an toàn) ---

    public synchronized void addRecycleBin(String username, String relativePath, String trashPath, long fileSize) {
        String sql = "INSERT INTO recycle_bin (username, relative_path, trash_path, file_size) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, relativePath);
            ps.setString(3, trashPath);
            ps.setLong(4, fileSize);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<Map<String, String>> getAllRecycleBin(String username) {
        List<Map<String, String>> list = new ArrayList<>();
        String sql = (username != null && !username.equalsIgnoreCase("admin"))
                ? "SELECT id, username, relative_path, trash_path, file_size, deleted_at FROM recycle_bin WHERE username = ? ORDER BY id DESC"
                : "SELECT id, username, relative_path, trash_path, file_size, deleted_at FROM recycle_bin ORDER BY id DESC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (username != null && !username.equalsIgnoreCase("admin")) {
                ps.setString(1, username);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("id", String.valueOf(rs.getInt("id")));
                    row.put("username", rs.getString("username"));
                    row.put("path", rs.getString("relative_path"));
                    row.put("trash_path", rs.getString("trash_path"));
                    row.put("size", String.valueOf(rs.getLong("file_size")));
                    row.put("deleted_at", rs.getString("deleted_at"));
                    list.add(row);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public Map<String, String> getRecycleBinItem(int id) {
        String sql = "SELECT id, username, relative_path, trash_path, file_size, deleted_at FROM recycle_bin WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("id", String.valueOf(rs.getInt("id")));
                    row.put("username", rs.getString("username"));
                    row.put("path", rs.getString("relative_path"));
                    row.put("trash_path", rs.getString("trash_path"));
                    row.put("size", String.valueOf(rs.getLong("file_size")));
                    row.put("deleted_at", rs.getString("deleted_at"));
                    return row;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void deleteRecycleBinItem(int id) {
        String sql = "DELETE FROM recycle_bin WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // --- Hash Security Helpers ---

    private String generateSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        StringBuilder sb = new StringBuilder();
        for (byte b : salt) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes());
            byte[] bytes = md.digest(password.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return password;
        }
    }
}
