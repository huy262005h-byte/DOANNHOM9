package com.mycompany.doancs.common;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Tiện ích băm dữ liệu theo chuẩn bảo mật SHA-256.
 * Dùng để so sánh sự thay đổi nội dung tệp tin chính xác tuyệt đối.
 */
public class HashUtils {

    /**
     * Tính mã băm SHA-256 của một tệp tin bằng cách đọc theo từng khối byte (Chunking).
     * Tránh nạp toàn bộ file vào RAM, an toàn với file lớn.
     */
    public static String calculateSHA256(File file) {
        if (file == null || !file.exists() || file.isDirectory()) {
            return "";
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[64 * 1024]; // Đọc từng khối 64KB
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().toUpperCase();

        } catch (NoSuchAlgorithmException | IOException e) {
            System.err.println("Lỗi tính SHA-256 cho tệp " + file.getName() + ": " + e.getMessage());
            return "";
        }
    }
}
