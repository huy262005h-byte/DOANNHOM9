package com.mycompany.doancs.gui;

import com.mycompany.doancs.gui.components.ModernUI;
import com.mycompany.doancs.util.ThemeManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;

/**
 * Màn hình khởi chạy trung tâm (Launcher Dashboard) tinh gọn và hiện đại.
 */
public class MainDashboard extends JFrame {

    public MainDashboard() {
        initComponents();
    }

    private void initComponents() {
        setTitle("Đồng Bộ Tệp Tin (Client - Server)");
        setSize(460, 390);
        setResizable(false);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel mainPanel = new JPanel(new BorderLayout(0, 16));
        mainPanel.setBorder(new EmptyBorder(24, 28, 20, 28));

        // 1. TIÊU ĐỀ ĐƠN GIẢN, HIỆN ĐẠI
        JPanel headerPanel = new JPanel(new GridLayout(2, 1, 4, 4));
        headerPanel.setOpaque(false);

        JLabel lblTitle = new JLabel("HỆ THỐNG ĐỒNG BỘ TỆP TIN", SwingConstants.CENTER);
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 19));
        lblTitle.setForeground(ModernUI.PRIMARY);

        JLabel lblSub = new JLabel("Mô hình Client - Server qua TCP/IP Socket", SwingConstants.CENTER);
        lblSub.setFont(ModernUI.FONT_REGULAR);
        lblSub.setForeground(ModernUI.TEXT_MUTED);

        headerPanel.add(lblTitle);
        headerPanel.add(lblSub);
        mainPanel.add(headerPanel, BorderLayout.NORTH);

        // 2. DANH SÁCH NÚT THAO TÁC TRỰC QUAN
        JPanel actionsPanel = new JPanel(new GridLayout(3, 1, 0, 12));
        actionsPanel.setOpaque(false);
        actionsPanel.setBorder(new EmptyBorder(8, 0, 8, 0));

        JButton btnServer = ModernUI.createButton("Khởi Động Máy Chủ (Server)", ModernUI.PRIMARY, Color.WHITE);
        btnServer.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnServer.addActionListener(e -> openServerWindow());

        JButton btnClient = ModernUI.createButton("Mở Máy Khách (Client)", ModernUI.SUCCESS, Color.WHITE);
        btnClient.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnClient.addActionListener(e -> openClientWindow());

        JButton btnBoth = ModernUI.createButton("Chạy Cả Hai Cùng Lúc", new Color(99, 102, 241), Color.WHITE);
        btnBoth.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnBoth.addActionListener(e -> runQuickTestMode());

        actionsPanel.add(btnServer);
        actionsPanel.add(btnClient);
        actionsPanel.add(btnBoth);
        mainPanel.add(actionsPanel, BorderLayout.CENTER);

        // 3. THANH THÔNG TIN DƯỚI CÙNG (FOOTER)
        JPanel footerPanel = new JPanel(new BorderLayout());
        footerPanel.setOpaque(false);

        JLabel lblVersion = new JLabel("Phiên bản 1.0 | TCP Socket");
        lblVersion.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblVersion.setForeground(ModernUI.TEXT_MUTED);

        JButton btnThemeToggle = ModernUI.createButton("Đổi Giao Diện", new Color(241, 245, 249), new Color(30, 41, 59));
        btnThemeToggle.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btnThemeToggle.setPreferredSize(new Dimension(110, 26));
        btnThemeToggle.addActionListener(e -> ThemeManager.toggleTheme());

        footerPanel.add(lblVersion, BorderLayout.WEST);
        footerPanel.add(btnThemeToggle, BorderLayout.EAST);
        mainPanel.add(footerPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private void openServerWindow() {
        ServerFrame serverFrame = new ServerFrame();
        serverFrame.setVisible(true);
    }

    private void openClientWindow() {
        ClientFrame clientFrame = new ClientFrame();
        clientFrame.setVisible(true);
    }

    private void runQuickTestMode() {
        File clientDir = new File(System.getProperty("user.dir"), "SyncData/ClientLocal");
        File serverDir = new File(System.getProperty("user.dir"), "SyncData/ServerShared");
        if (!clientDir.exists()) clientDir.mkdirs();
        if (!serverDir.exists()) serverDir.mkdirs();

        // Mở Server ở nửa bên trái màn hình
        ServerFrame serverFrame = new ServerFrame();
        serverFrame.setLocation(40, 60);
        serverFrame.setVisible(true);

        // Mở Client ở nửa bên phải màn hình
        ClientFrame clientFrame = new ClientFrame();
        clientFrame.setLocation(1000, 60);
        clientFrame.setVisible(true);
    }
}
