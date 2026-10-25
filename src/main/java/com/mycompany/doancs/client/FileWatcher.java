package com.mycompany.doancs.client;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

/**
 * File Watcher (Phía Máy Trạm):
 * Theo dõi thư mục cục bộ của người dùng theo thời gian thực bằng Java NIO WatchService.
 * Nhận diện kịp thời các sự kiện: Thêm mới (ENTRY_CREATE), Chỉnh sửa (ENTRY_MODIFY), và Xóa (ENTRY_DELETE).
 */
public class FileWatcher {
    private final File directory;
    private final Runnable changeCallback;
    private WatchService watchService;
    private final Map<WatchKey, Path> watchKeys = new HashMap<>();
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> debounceTask;
    private Thread watchThread;

    public FileWatcher(File directory, Runnable changeCallback) {
        this.directory = directory;
        this.changeCallback = changeCallback;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.watchKeys.clear();
        this.running = true;

        registerRecursive(directory.toPath());

        watchThread = new Thread(this::processWatchEvents, "FileWatcher-Thread");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            if (watchService != null) {
                watchService.close();
            }
        } catch (IOException ignored) {}

        if (debounceTask != null) {
            debounceTask.cancel(true);
        }
        debounceExecutor.shutdownNow();
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    private void registerRecursive(final Path startPath) throws IOException {
        Files.walkFileTree(startPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                WatchKey key = dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                watchKeys.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void processWatchEvents() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException x) {
                return;
            }

            Path dir = watchKeys.get(key);
            if (dir == null) continue;

            boolean changeDetected = false;

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path filename = ev.context();
                Path fullPath = dir.resolve(filename);

                String nameStr = filename.toString();
                // Bỏ qua các tệp ẩn, tệp tạm và file syncignore
                if (nameStr.startsWith(".") || nameStr.endsWith("~") || nameStr.endsWith(".tmp") || nameStr.toLowerCase().contains("syncignore")) {
                    continue;
                }

                // Nếu tạo thư mục mới, đăng ký lắng nghe đệ quy thư mục đó
                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    try {
                        if (Files.isDirectory(fullPath, LinkOption.NOFOLLOW_LINKS)) {
                            registerRecursive(fullPath);
                        }
                    } catch (IOException ignored) {}
                }

                changeDetected = true;
            }

            boolean valid = key.reset();
            if (!valid) {
                watchKeys.remove(key);
            }

            if (changeDetected && !paused) {
                triggerDebouncedSync();
            }
        }
    }

    private synchronized void triggerDebouncedSync() {
        if (debounceTask != null && !debounceTask.isDone()) {
            debounceTask.cancel(false);
        }
        // Đợi 500ms sau sự kiện cuối cùng trước khi tự động kích hoạt đồng bộ (Debounce nhanh)
        debounceTask = debounceExecutor.schedule(() -> {
            if (running && !paused) {
                changeCallback.run();
            }
        }, 500, TimeUnit.MILLISECONDS);
    }
}
