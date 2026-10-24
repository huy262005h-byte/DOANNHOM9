package com.mycompany.doancs.util;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.*;

/**
 * Bộ lọc tệp tin loại trừ (.syncignore Filter).
 * Hỗ trợ các quy tắc loại trừ tương tự .gitignore để người dùng không đồng bộ
 * các tệp tạm, thư mục thư viện nặng hoặc tệp cấu hình IDE.
 */
public class SyncIgnoreFilter {
    public static final String IGNORE_FILE_NAME = ".syncignore";

    private final List<String> patterns = new ArrayList<>();
    private final List<PathMatcher> matchers = new ArrayList<>();

    public SyncIgnoreFilter(File rootDir) {
        loadDefaultPatterns();
        if (rootDir != null && rootDir.exists()) {
            File ignoreFile = new File(rootDir, IGNORE_FILE_NAME);
            if (ignoreFile.exists() && ignoreFile.isFile()) {
                loadFromFile(ignoreFile);
            }
        }
        compileMatchers();
    }

    private void loadDefaultPatterns() {
        // Mặc định bỏ qua các tệp ẩn hệ thống và thư mục tạm
        patterns.add(".*");
        patterns.add(".*/**");
        patterns.add(".syncignore");
        patterns.add("**/.syncignore");
        patterns.add("syncignore");
        patterns.add("**/syncignore");
        patterns.add("*.syncignore");
        patterns.add("*~");
        patterns.add("*.tmp");
        patterns.add("*.part");
        patterns.add("desktop.ini");
        patterns.add("Thumbs.db");
        patterns.add(".DS_Store");
        patterns.add("target/**");
        patterns.add("node_modules/**");
        patterns.add(".versions/**");
        patterns.add(".trash/**");
    }

    private void loadFromFile(File file) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue; // Bỏ qua dòng trống và comment
                }
                patterns.add(line);
            }
        } catch (IOException e) {
            System.err.println("Không thể đọc tệp " + IGNORE_FILE_NAME + ": " + e.getMessage());
        }
    }

    private void compileMatchers() {
        matchers.clear();
        for (String p : patterns) {
            try {
                String glob = p.replace('\\', '/');
                if (glob.startsWith("/")) glob = glob.substring(1);
                // Tạo matcher glob
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
                matchers.add(matcher);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Kiểm tra xem đường dẫn tệp tương đối có nằm trong danh sách bị bỏ qua hay không.
     */
    public boolean shouldIgnore(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return false;
        String normalized = FileUtils.normalizePath(relativePath);

        // Kiểm tra nhanh các trường hợp đặc biệt
        String lower = normalized.toLowerCase();
        if (lower.equals(".syncignore") || lower.endsWith("/.syncignore")
                || lower.equals("syncignore") || lower.endsWith("/syncignore")
                || lower.endsWith(".syncignore")) {
            return true; // Tệp cấu hình syncignore / .syncignore không đồng bộ
        }
        if (normalized.startsWith(".versions/") || normalized.startsWith(".trash/")) return true;

        Path path = Paths.get(normalized);
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(path)) {
                return true;
            }
        }

        // Kiểm tra từng phần tên (filename / folder name)
        String fileName = path.getFileName() != null ? path.getFileName().toString() : "";
        if (fileName.startsWith(".")) return true;
        if (fileName.endsWith("~") || fileName.endsWith(".tmp") || fileName.endsWith(".part")) return true;

        return false;
    }

    /**
     * Tạo tệp .syncignore mẫu mặc định nếu chưa tồn tại trong thư mục.
     */
    public static void createDefaultSyncIgnoreFile(File rootDir) {
        if (rootDir == null || !rootDir.exists()) return;
        File ignoreFile = new File(rootDir, IGNORE_FILE_NAME);
        if (!ignoreFile.exists()) {
            try (FileWriter fw = new FileWriter(ignoreFile)) {
                fw.write("# TẬP TIN CẤU HÌNH LOẠI TRỪ ĐỒNG BỘ (.syncignore)\n");
                fw.write("# Các tệp và thư mục khớp với danh sách bên dưới sẽ được bỏ qua khi đồng bộ.\n\n");
                fw.write("# Thư viện & Thư mục build\n");
                fw.write("target/\n");
                fw.write("node_modules/\n");
                fw.write("bin/\n\n");
                fw.write("# Tệp tạm & Tệp hệ thống\n");
                fw.write("*.tmp\n");
                fw.write("*.part\n");
                fw.write("*~\n");
                fw.write("~$*\n");
                fw.write("Thumbs.db\n");
                fw.write(".DS_Store\n\n");
                fw.write("# Tệp log\n");
                fw.write("*.log\n");
            } catch (IOException ignored) {}
        }
    }
}
