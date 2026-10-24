package com.mycompany.doancs;

import com.mycompany.doancs.gui.ClientFrame;
import com.mycompany.doancs.gui.MainDashboard;
import com.mycompany.doancs.gui.ServerFrame;
import com.mycompany.doancs.util.ThemeManager;

import javax.swing.SwingUtilities;

/**
 * Điểm khởi chạy chính của ứng dụng Đồng bộ Tệp tin Client - Server.
 */
public class DOANCS {

    public static void main(String[] args) {
        // Khởi tạo giao diện đồ họa hiện đại với FlatLaf
        ThemeManager.setupTheme(false); // false = Modern Light, true = Modern Dark

        SwingUtilities.invokeLater(() -> {
            if (args != null && args.length > 0) {
                if ("--server".equalsIgnoreCase(args[0])) {
                    new ServerFrame().setVisible(true);
                    return;
                } else if ("--client".equalsIgnoreCase(args[0])) {
                    new ClientFrame().setVisible(true);
                    return;
                }
            }
            // Mặc định mở Bảng điều khiển chính (Dashboard)
            new MainDashboard().setVisible(true);
        });
    }
}
