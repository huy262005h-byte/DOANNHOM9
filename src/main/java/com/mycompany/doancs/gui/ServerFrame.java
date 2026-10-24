package com.mycompany.doancs.gui;

import com.mycompany.doancs.common.FileMetadata;
import com.mycompany.doancs.common.HashUtils;
import com.mycompany.doancs.gui.components.ModernUI;
import com.mycompany.doancs.model.ClientSessionInfo;
import com.mycompany.doancs.model.FileVersionInfo;
import com.mycompany.doancs.server.ServerCore;
import com.mycompany.doancs.server.ServerEventListener;
import com.mycompany.doancs.server.StorageManager;
import com.mycompany.doancs.server.db.DatabaseManager;
import com.mycompany.doancs.util.FileUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class ServerFrame extends JFrame implements ServerEventListener {
    private final ServerCore server;
    private File sharedFolder;

    // UI Components
    private JLabel lblStatusBadge;
    private JComboBox<String> cmbIpAddresses;
    private JTextField txtPort;
    private JTextField txtSharedDir;
    private JButton btnBrowseFolder;
    private JButton btnOpenFolder;
    private JButton btnStartStop;

    // Stat Cards
    private JPanel cardClients;
    private JPanel cardFiles;
    private JPanel cardStorage;
    private JPanel cardTransfers;
    private int totalTransfersCount = 0;

    // Tables
    private DefaultTableModel filesTableModel;
    private JTable filesTable;
    private DefaultTableModel clientsTableModel;
    private JTable clientsTable;

    // Database Tables
    private DefaultTableModel usersTableModel;
    private JTable usersTable;
    private DefaultTableModel conflictTableModel;
    private JTable conflictTable;
    private DefaultTableModel dbMetadataTableModel;
    private JTable dbMetadataTable;
    private DefaultTableModel recycleBinTableModel;
    private JTable recycleBinTable;
    private TableRowSorter<DefaultTableModel> filesTableSorter;

    // Logs
    private JTextArea txtLogs;
    private JCheckBox chkAutoScroll;

    public ServerFrame() {
        this.server = new ServerCore();
        this.server.addListener(this);

        // Mặc định thư mục lưu trữ chia sẻ của Server
        File defaultDir = new File(System.getProperty("user.dir"), "SyncData/ServerShared");
        if (!defaultDir.exists()) {
            defaultDir.mkdirs();
        }
        this.sharedFolder = defaultDir;

        initComponents();
        refreshServerFileList();
        refreshDatabaseTables();
    }

    private void initComponents() {
        setTitle("Máy Chủ Đồng Bộ Tệp Tin (File Sync Server) - TCP Socket & SQLite");
        setSize(1040, 720);
        setMinimumSize(new Dimension(880, 600));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel mainPanel = new JPanel(new BorderLayout(12, 12));
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        // 1. HEADER & CONTROL CARD
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.add(createHeaderPanel(), BorderLayout.NORTH);
        topPanel.add(createConfigCard(), BorderLayout.CENTER);
        topPanel.add(createMetricsGrid(), BorderLayout.SOUTH);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 2. MAIN TABBED PANE
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(ModernUI.FONT_BOLD);
        tabbedPane.addTab("  Danh Sách Tệp Trên Máy Chủ  ", createFilesTab());
        tabbedPane.addTab("  Máy Khách Đang Kết Nối  ", createClientsTab());
        tabbedPane.addTab("  Cơ Sở Dữ Liệu SQLite (Users & Conflicts)  ", createDatabaseTab());
        tabbedPane.addTab("  Nhật Ký Hoạt Động (Logs)  ", createLogsTab());

        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        setContentPane(mainPanel);

        // Lắng nghe khi đóng cửa sổ
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                if (server.isRunning()) {
                    server.stop();
                }
            }
        });
    }

    private JPanel createHeaderPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        JLabel lblTitle = new JLabel("HỆ THỐNG MÁY CHỦ ĐỒNG BỘ (SERVER)");
        lblTitle.setFont(ModernUI.FONT_TITLE);
        lblTitle.setForeground(ModernUI.PRIMARY);

        lblStatusBadge = ModernUI.createBadge("OFFLINE", new Color(254, 226, 226), new Color(153, 27, 27));

        panel.add(lblTitle, BorderLayout.WEST);
        panel.add(lblStatusBadge, BorderLayout.EAST);
        return panel;
    }

    private JPanel createConfigCard() {
        JPanel card = ModernUI.createCardPanel();
        card.setLayout(new GridLayout(2, 1, 8, 8));

        // Dòng 1: Cổng TCP & Địa chỉ IP lắng nghe & Nút Khởi động
        JPanel row1 = new JPanel(new BorderLayout(10, 0));
        row1.setOpaque(false);

        JPanel row1Left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row1Left.setOpaque(false);

        row1Left.add(new JLabel("Cổng TCP (Port):"));
        txtPort = new JTextField("8888", 5);
        txtPort.setFont(ModernUI.FONT_BOLD);
        row1Left.add(txtPort);

        row1Left.add(Box.createHorizontalStrut(15));
        row1Left.add(new JLabel("IP khả dụng:"));
        cmbIpAddresses = new JComboBox<>(ServerCore.getAvailableIPAddresses().toArray(new String[0]));
        cmbIpAddresses.setFont(ModernUI.FONT_REGULAR);
        cmbIpAddresses.setPreferredSize(new Dimension(280, 30));
        cmbIpAddresses.setToolTipText("Chọn IP mạng LAN (ví dụ 192.168.x.x) để máy tính khác trong cùng mạng kết nối đến");
        row1Left.add(cmbIpAddresses);

        row1.add(row1Left, BorderLayout.WEST);

        btnStartStop = ModernUI.createButton("Khởi Động Server", ModernUI.SUCCESS, Color.WHITE);
        btnStartStop.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnStartStop.setPreferredSize(new Dimension(160, 32));
        btnStartStop.addActionListener(e -> toggleServerState());
        row1.add(btnStartStop, BorderLayout.EAST);

        // Dòng 2: Thư mục lưu trữ chia sẻ
        JPanel row2 = new JPanel(new BorderLayout(10, 0));
        row2.setOpaque(false);

        JLabel lblFolder = new JLabel("Thư mục lưu trữ: ");
        lblFolder.setFont(ModernUI.FONT_BOLD);
        row2.add(lblFolder, BorderLayout.WEST);

        txtSharedDir = new JTextField(sharedFolder.getAbsolutePath());
        txtSharedDir.setEditable(false);
        txtSharedDir.setFont(ModernUI.FONT_REGULAR);
        txtSharedDir.setToolTipText(sharedFolder.getAbsolutePath());
        row2.add(txtSharedDir, BorderLayout.CENTER);

        JPanel folderActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        folderActions.setOpaque(false);
        btnBrowseFolder = ModernUI.createButton("Chọn thư mục...", new Color(241, 245, 249), new Color(30, 41, 59));
        btnBrowseFolder.addActionListener(e -> chooseSharedDirectory());

        btnOpenFolder = ModernUI.createButton("Mở Explorer", new Color(241, 245, 249), new Color(30, 41, 59));
        btnOpenFolder.addActionListener(e -> FileUtils.openFolderInExplorer(sharedFolder));

        folderActions.add(btnBrowseFolder);
        folderActions.add(btnOpenFolder);
        row2.add(folderActions, BorderLayout.EAST);

        card.add(row1);
        card.add(row2);
        return card;
    }

    private JPanel createMetricsGrid() {
        JPanel grid = new JPanel(new GridLayout(1, 4, 12, 12));
        cardClients = ModernUI.createStatCard("MÁY KHÁCH ONLINE", "0", ModernUI.PRIMARY);
        cardFiles = ModernUI.createStatCard("TỔNG SỐ TỆP", "0 tệp", ModernUI.SUCCESS);
        cardStorage = ModernUI.createStatCard("DUNG LƯỢNG LƯU TRỮ", "0 B", ModernUI.WARNING);
        cardTransfers = ModernUI.createStatCard("LƯỢT TRUYỀN DỮ LIỆU", "0", ModernUI.PRIMARY_HOVER);

        grid.add(cardClients);
        grid.add(cardFiles);
        grid.add(cardStorage);
        grid.add(cardTransfers);
        return grid;
    }

    private JPanel createFilesTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(10, 2, 2, 2));

        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton btnRefresh = ModernUI.createButton("Quét Lại Kho Tệp", new Color(241, 245, 249), new Color(30, 41, 59));
        btnRefresh.addActionListener(e -> refreshServerFileList());

        JButton btnOpen = ModernUI.createButton("Mở Thư Mục Explorer", new Color(241, 245, 249), new Color(30, 41, 59));
        btnOpen.addActionListener(e -> FileUtils.openFolderInExplorer(sharedFolder));

        JLabel lblSearch = new JLabel("Tìm kiếm:");
        lblSearch.setFont(ModernUI.FONT_BOLD);
        JTextField txtSearch = new JTextField(18);
        txtSearch.putClientProperty("JTextField.placeholderText", "Lọc tệp theo tên hoặc đuôi...");

        toolbar.add(btnRefresh);
        toolbar.add(btnOpen);
        toolbar.add(Box.createHorizontalStrut(15));
        toolbar.add(lblSearch);
        toolbar.add(txtSearch);
        panel.add(toolbar, BorderLayout.NORTH);

        // Table
        String[] columns = {"STT", "Tên Tệp", "Đường Dẫn Tương Đối", "Kích Thước", "Thời Gian Sửa Đổi", "Mã Băm (SHA-256)"};
        filesTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        filesTable = new JTable(filesTableModel);
        filesTableSorter = new TableRowSorter<>(filesTableModel);
        filesTable.setRowSorter(filesTableSorter);

        txtSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void update() {
                String text = txtSearch.getText().trim();
                if (text.isEmpty()) {
                    filesTableSorter.setRowFilter(null);
                } else {
                    filesTableSorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text), 1, 2));
                }
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
        });

        filesTable.getColumnModel().getColumn(0).setPreferredWidth(45);
        filesTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        filesTable.getColumnModel().getColumn(2).setPreferredWidth(230);
        filesTable.getColumnModel().getColumn(3).setPreferredWidth(100);
        filesTable.getColumnModel().getColumn(4).setPreferredWidth(140);
        filesTable.getColumnModel().getColumn(5).setPreferredWidth(200);

        // Context Menu
        JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem menuVersions = new JMenuItem("Xem lịch sử phiên bản (Versioning)...");
        menuVersions.addActionListener(e -> showServerFileVersionDialog());

        JMenuItem menuOpenFile = new JMenuItem("Mở tệp này trên máy");
        menuOpenFile.addActionListener(e -> {
            int row = filesTable.getSelectedRow();
            if (row >= 0) {
                int modelRow = filesTable.convertRowIndexToModel(row);
                String relPath = (String) filesTableModel.getValueAt(modelRow, 2);
                File file = new File(sharedFolder, relPath);
                if (file.exists()) {
                    try { Desktop.getDesktop().open(file); } catch (Exception ignored) {}
                }
            }
        });

        JMenuItem menuDeleteFile = new JMenuItem("Xóa tệp này (Vào Thùng rác & Báo Client)");
        menuDeleteFile.addActionListener(e -> {
            int row = filesTable.getSelectedRow();
            if (row < 0) return;
            int modelRow = filesTable.convertRowIndexToModel(row);
            String relPath = (String) filesTableModel.getValueAt(modelRow, 2);
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Bạn có chắc muốn chuyển tệp '" + relPath + "' vào thùng rác và báo cho tất cả Client?",
                    "Xác Nhận Xóa", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;

            StorageManager sm = server.getStorageManager() != null ? server.getStorageManager() : new StorageManager(sharedFolder);
            DatabaseManager db = server.getDatabaseManager();
            boolean ok = false;
            if (db != null) {
                ok = sm.moveToRecycleBin(relPath, "admin", db);
                db.deleteFileMetadata(relPath);
            }
            if (!ok) {
                ok = sm.deleteFile(relPath);
            }
            if (ok) {
                server.broadcastFileChange("admin", relPath, "DELETE", null);
                refreshServerFileList();
                refreshDatabaseTables();
                JOptionPane.showMessageDialog(this, "Đã xóa tệp và thông báo tới các Client!", "Thành Công", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        contextMenu.add(menuVersions);
        contextMenu.add(menuDeleteFile);
        contextMenu.addSeparator();
        contextMenu.add(menuOpenFile);

        filesTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = filesTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && !filesTable.isRowSelected(row)) {
                        filesTable.setRowSelectionInterval(row, row);
                    }
                    if (filesTable.getSelectedRowCount() > 0) {
                        contextMenu.show(filesTable, e.getX(), e.getY());
                    }
                }
            }
        });

        panel.add(new JScrollPane(filesTable), BorderLayout.CENTER);
        return panel;
    }

    private void showServerFileVersionDialog() {
        int row = filesTable.getSelectedRow();
        if (row < 0) return;
        int modelRow = filesTable.convertRowIndexToModel(row);
        String relPath = (String) filesTableModel.getValueAt(modelRow, 2);
        DatabaseManager db = server.getDatabaseManager();
        if (db == null) return;

        List<FileVersionInfo> versions = db.getFileVersions("general", relPath);
        if (versions.isEmpty()) {
            versions = db.getFileVersions("admin", relPath);
        }
        if (versions.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Tệp '" + relPath + "' chưa có phiên bản sao lưu cũ nào trong SQLite.",
                    "Thông Báo Lịch Sử Phiên Bản", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        FileVersionDialog dlg = new FileVersionDialog(this, relPath, versions, (ver) -> {
            StorageManager sm = server.getStorageManager();
            if (sm != null) {
                boolean ok = sm.restoreFileVersion(relPath, ver.getBackupPath());
                if (ok) {
                    JOptionPane.showMessageDialog(this,
                            "Đã khôi phục thành công tệp '" + relPath + "' về phiên bản v" + ver.getVersion() + "!",
                            "Khôi Phục Thành Công", JOptionPane.INFORMATION_MESSAGE);
                    refreshServerFileList();
                    refreshDatabaseTables();
                    server.broadcastFileChange("general", relPath, "RESTORE", null);
                }
            }
        });
        dlg.setVisible(true);
    }

    private JPanel createClientsTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(10, 2, 2, 2));

        String[] columns = {"Mã Client (IP:Port)", "Tài Khoản", "Thời Điểm Kết Nối", "Số Tệp Đã Truyền", "Dung Lượng Đã Truyền", "Trạng Thái Hiện Tại"};
        clientsTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        clientsTable = new JTable(clientsTableModel);
        clientsTable.getColumnModel().getColumn(0).setPreferredWidth(160);
        clientsTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        clientsTable.getColumnModel().getColumn(2).setPreferredWidth(120);
        clientsTable.getColumnModel().getColumn(3).setPreferredWidth(110);
        clientsTable.getColumnModel().getColumn(4).setPreferredWidth(130);
        clientsTable.getColumnModel().getColumn(5).setPreferredWidth(200);
        clientsTable.getColumnModel().getColumn(5).setCellRenderer(new ModernUI.BadgeCellRenderer());

        panel.add(new JScrollPane(clientsTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createDatabaseTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(10, 2, 2, 2));

        JTabbedPane subTab = new JTabbedPane();
        subTab.setFont(ModernUI.FONT_BOLD);

        // SubTab 1: Users
        JPanel usersPanel = new JPanel(new BorderLayout(6, 6));
        JPanel userToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton btnAddUser = ModernUI.createButton("Thêm Tài Khoản Mới...", ModernUI.PRIMARY, Color.WHITE);
        btnAddUser.addActionListener(e -> showAddUserDialog());
        JButton btnRefreshUsers = ModernUI.createButton("Làm Mới", new Color(241, 245, 249), new Color(30, 41, 59));
        btnRefreshUsers.addActionListener(e -> refreshDatabaseTables());
        userToolbar.add(btnAddUser);
        userToolbar.add(btnRefreshUsers);
        usersPanel.add(userToolbar, BorderLayout.NORTH);

        String[] userCols = {"ID", "Tên Đăng Nhập (Username)", "Thời Gian Đăng Ký"};
        usersTableModel = new DefaultTableModel(userCols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        usersTable = new JTable(usersTableModel);
        usersTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        usersTable.getColumnModel().getColumn(1).setPreferredWidth(250);
        usersTable.getColumnModel().getColumn(2).setPreferredWidth(250);
        usersPanel.add(new JScrollPane(usersTable), BorderLayout.CENTER);

        // SubTab 2: Conflict Logs
        JPanel conflictPanel = new JPanel(new BorderLayout(6, 6));
        JPanel confToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton btnRefreshConflicts = ModernUI.createButton("Làm Mới Nhật Ký", new Color(241, 245, 249), new Color(30, 41, 59));
        btnRefreshConflicts.addActionListener(e -> refreshDatabaseTables());
        confToolbar.add(btnRefreshConflicts);
        conflictPanel.add(confToolbar, BorderLayout.NORTH);

        String[] confCols = {"Thời Gian", "Người Dùng", "Tệp Gốc (Original Path)", "Bản Sao Xung Đột (Conflict Backup File)"};
        conflictTableModel = new DefaultTableModel(confCols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        conflictTable = new JTable(conflictTableModel);
        conflictTable.getColumnModel().getColumn(0).setPreferredWidth(160);
        conflictTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        conflictTable.getColumnModel().getColumn(2).setPreferredWidth(260);
        conflictTable.getColumnModel().getColumn(3).setPreferredWidth(320);
        conflictPanel.add(new JScrollPane(conflictTable), BorderLayout.CENTER);

        // SubTab 3: Files Metadata
        JPanel metaPanel = new JPanel(new BorderLayout(6, 6));
        JPanel metaToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton btnRefreshMeta = ModernUI.createButton("Làm Mới Metadata", new Color(241, 245, 249), new Color(30, 41, 59));
        btnRefreshMeta.addActionListener(e -> refreshDatabaseTables());
        metaToolbar.add(btnRefreshMeta);
        metaPanel.add(metaToolbar, BorderLayout.NORTH);

        String[] metaCols = {"Người Dùng", "Đường Dẫn Tệp", "Dung Lượng", "Phiên Bản", "Mã Băm (SHA-256)", "Cập Nhật Cuối"};
        dbMetadataTableModel = new DefaultTableModel(metaCols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        dbMetadataTable = new JTable(dbMetadataTableModel);
        dbMetadataTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        dbMetadataTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        dbMetadataTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        dbMetadataTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        dbMetadataTable.getColumnModel().getColumn(4).setPreferredWidth(220);
        dbMetadataTable.getColumnModel().getColumn(5).setPreferredWidth(140);
        metaPanel.add(new JScrollPane(dbMetadataTable), BorderLayout.CENTER);

        subTab.addTab("  Tài Khoản Người Dùng (users)  ", usersPanel);
        subTab.addTab("  Nhật Ký Xung Đột (conflict_logs)  ", conflictPanel);
        subTab.addTab("  Chỉ Mục Metadata (files_metadata)  ", metaPanel);

        // SubTab 4: Recycle Bin (Thùng rác an toàn)
        JPanel trashPanel = new JPanel(new BorderLayout(6, 6));
        JPanel trashToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton btnRestoreTrash = ModernUI.createButton("Khôi Phục Tệp Đã Chọn (Restore)", ModernUI.PRIMARY, Color.WHITE);
        btnRestoreTrash.addActionListener(e -> performRestoreFromTrash());
        JButton btnRefreshTrash = ModernUI.createButton("Làm Mới", new Color(241, 245, 249), new Color(30, 41, 59));
        btnRefreshTrash.addActionListener(e -> refreshDatabaseTables());

        trashToolbar.add(btnRestoreTrash);
        trashToolbar.add(btnRefreshTrash);
        trashPanel.add(trashToolbar, BorderLayout.NORTH);

        String[] trashCols = {"ID", "Người Dùng", "Đường Dẫn Tệp", "Dung Lượng", "Thời Gian Xóa"};
        recycleBinTableModel = new DefaultTableModel(trashCols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        recycleBinTable = new JTable(recycleBinTableModel);
        recycleBinTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        recycleBinTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        recycleBinTable.getColumnModel().getColumn(2).setPreferredWidth(250);
        recycleBinTable.getColumnModel().getColumn(3).setPreferredWidth(100);
        recycleBinTable.getColumnModel().getColumn(4).setPreferredWidth(150);
        trashPanel.add(new JScrollPane(recycleBinTable), BorderLayout.CENTER);

        subTab.addTab("  Thùng Rác An Toàn (recycle_bin)  ", trashPanel);

        panel.add(subTab, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createLogsTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(10, 2, 2, 2));

        JPanel logToolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        chkAutoScroll = new JCheckBox("Tự động cuộn xuống", true);
        JButton btnClear = ModernUI.createButton("Xóa Nhật Ký", new Color(241, 245, 249), new Color(30, 41, 59));
        btnClear.addActionListener(e -> txtLogs.setText(""));

        logToolbar.add(chkAutoScroll);
        logToolbar.add(btnClear);
        panel.add(logToolbar, BorderLayout.NORTH);

        txtLogs = new JTextArea();
        txtLogs.setEditable(false);
        txtLogs.setFont(ModernUI.FONT_MONO);
        txtLogs.setBackground(new Color(248, 250, 252));
        txtLogs.setMargin(new Insets(8, 8, 8, 8));

        panel.add(new JScrollPane(txtLogs), BorderLayout.CENTER);
        return panel;
    }

    private void chooseSharedDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn Thư Mục Chia Sẻ Cho Máy Chủ");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setSelectedFile(sharedFolder);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            sharedFolder = chooser.getSelectedFile();
            txtSharedDir.setText(sharedFolder.getAbsolutePath());
            refreshServerFileList();
        }
    }

    private void toggleServerState() {
        if (!server.isRunning()) {
            int port;
            try {
                port = Integer.parseInt(txtPort.getText().trim());
                if (port < 1024 || port > 65535) throw new Exception();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Vui lòng nhập cổng TCP hợp lệ (1024 - 65535)", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }

            try {
                server.start(port, sharedFolder);
                refreshDatabaseTables();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Không thể khởi động Server: " + ex.getMessage(), "Lỗi Socket", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            server.stop();
        }
    }

    public void refreshServerFileList() {
        SwingUtilities.invokeLater(() -> {
            StorageManager sm = server.getStorageManager() != null ? server.getStorageManager() : new StorageManager(sharedFolder);
            List<FileMetadata> files = sm.scanFiles();
            filesTableModel.setRowCount(0);

            long totalBytes = 0;
            int idx = 1;
            for (FileMetadata f : files) {
                totalBytes += f.getSize();
                filesTableModel.addRow(new Object[]{
                        idx++,
                        f.getFileName(),
                        f.getRelativePath(),
                        f.getFormattedSize(),
                        f.getFormattedDate(),
                        f.getSha256()
                });
            }

            ModernUI.updateStatCardValue(cardFiles, files.size() + " tệp");
            ModernUI.updateStatCardValue(cardStorage, FileUtils.formatBytes(totalBytes));
        });
    }

    private void refreshClientsTable() {
        SwingUtilities.invokeLater(() -> {
            clientsTableModel.setRowCount(0);
            for (ClientSessionInfo c : server.getActiveClients()) {
                clientsTableModel.addRow(new Object[]{
                        c.getClientId(),
                        "@" + c.getUsername(),
                        c.getFormattedConnectedTime(),
                        c.getFilesTransferred() + " tệp",
                        c.getFormattedTransferredSize(),
                        c.getCurrentStatus()
                });
            }
            ModernUI.updateStatCardValue(cardClients, String.valueOf(server.getActiveClients().size()));
        });
    }

    public void refreshDatabaseTables() {
        SwingUtilities.invokeLater(() -> {
            DatabaseManager db = server.getDatabaseManager();
            if (db == null) {
                File dbFile = new File("SyncData/server_database.db");
                db = new DatabaseManager(dbFile);
            }

            // 1. Users
            if (usersTableModel != null) {
                usersTableModel.setRowCount(0);
                List<Map<String, String>> users = db.getAllRegisteredUsers();
                for (Map<String, String> u : users) {
                    usersTableModel.addRow(new Object[]{ u.get("id"), "@" + u.get("username"), u.get("created_at") });
                }
            }

            // 2. Conflicts
            if (conflictTableModel != null) {
                conflictTableModel.setRowCount(0);
                List<Map<String, String>> conflicts = db.getAllConflictLogs();
                for (Map<String, String> c : conflicts) {
                    conflictTableModel.addRow(new Object[]{ c.get("time"), "@" + c.get("user"), c.get("original"), c.get("conflict") });
                }
            }

            // 3. Metadata
            if (dbMetadataTableModel != null) {
                dbMetadataTableModel.setRowCount(0);
                List<Map<String, String>> metaList = db.getAllFileMetadata();
                for (Map<String, String> m : metaList) {
                    long size = 0;
                    try { size = Long.parseLong(m.get("size")); } catch (Exception ignored) {}
                    dbMetadataTableModel.addRow(new Object[]{
                            "@" + m.get("user"),
                            m.get("path"),
                            FileUtils.formatBytes(size),
                            "v" + m.get("version"),
                            m.get("sha256"),
                            m.get("updated_at")
                    });
                }
            }

            // 4. Recycle Bin
            if (recycleBinTableModel != null) {
                recycleBinTableModel.setRowCount(0);
                List<Map<String, String>> trashList = db.getAllRecycleBin(null);
                for (Map<String, String> t : trashList) {
                    long size = 0;
                    try { size = Long.parseLong(t.get("size")); } catch (Exception ignored) {}
                    recycleBinTableModel.addRow(new Object[]{
                            t.get("id"),
                            "@" + t.get("username"),
                            t.get("path"),
                            FileUtils.formatBytes(size),
                            t.get("deleted_at")
                    });
                }
            }
        });
    }

    private void performRestoreFromTrash() {
        int row = recycleBinTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn 1 tệp trong thùng rác để khôi phục!", "Chưa chọn tệp", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int id = Integer.parseInt((String) recycleBinTableModel.getValueAt(row, 0));
        DatabaseManager db = server.getDatabaseManager();
        if (db == null) return;

        Map<String, String> item = db.getRecycleBinItem(id);
        if (item != null) {
            String path = item.get("path");
            String trashPath = item.get("trash_path");
            StorageManager sm = server.getStorageManager();
            if (sm != null && sm.restoreRecycledFile(path, trashPath)) {
                db.deleteRecycleBinItem(id);
                File f = sm.getFile(path);
                if (f != null && f.exists()) {
                    String sha = HashUtils.calculateSHA256(f);
                    db.upsertFileMetadata(item.get("username"), new FileMetadata(path, f.length(), f.lastModified(), sha, false));
                }
                JOptionPane.showMessageDialog(this, "Đã khôi phục thành công tệp: " + path, "Thành Công", JOptionPane.INFORMATION_MESSAGE);
                refreshServerFileList();
                refreshDatabaseTables();
                server.broadcastFileChange(item.get("username"), path, "RESTORE", null);
            } else {
                JOptionPane.showMessageDialog(this, "Không thể khôi phục tệp từ sao lưu thùng rác!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void showAddUserDialog() {
        JDialog dialog = new JDialog(this, "Thêm Tài Khoản Vào SQLite", true);
        dialog.setSize(420, 270);
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(16, 22, 10, 22));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;

        JLabel lblUser = new JLabel("Tên đăng nhập (Username):");
        lblUser.setFont(ModernUI.FONT_BOLD);
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 6, 0);
        form.add(lblUser, gbc);

        JTextField txtU = new JTextField("", 15);
        txtU.setPreferredSize(new Dimension(340, 36));
        txtU.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 14, 0);
        form.add(txtU, gbc);

        JLabel lblPass = new JLabel("Mật khẩu:");
        lblPass.setFont(ModernUI.FONT_BOLD);
        gbc.gridy = 2;
        gbc.insets = new Insets(0, 0, 6, 0);
        form.add(lblPass, gbc);

        JPasswordField txtP = new JPasswordField("", 15);
        txtP.setPreferredSize(new Dimension(340, 36));
        txtP.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        gbc.gridy = 3;
        gbc.insets = new Insets(0, 0, 4, 0);
        form.add(txtP, gbc);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 14));
        btnPanel.setBorder(new EmptyBorder(0, 22, 14, 22));
        JButton btnAdd = ModernUI.createButton("Thêm Tài Khoản", ModernUI.PRIMARY, Color.WHITE);
        btnAdd.setPreferredSize(new Dimension(140, 34));
        JButton btnClose = ModernUI.createButton("Hủy", new Color(241, 245, 249), new Color(30, 41, 59));
        btnClose.setPreferredSize(new Dimension(80, 34));

        btnAdd.addActionListener(e -> {
            String u = txtU.getText().trim();
            String p = new String(txtP.getPassword()).trim();
            if (u.length() < 3 || p.length() < 4) {
                JOptionPane.showMessageDialog(dialog, "Tên >=3 ký tự, Mật khẩu >=4 ký tự!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }
            DatabaseManager db = server.getDatabaseManager();
            if (db == null) {
                db = new DatabaseManager(new File("SyncData/server_database.db"));
            }
            boolean ok = db.registerUser(u, p);
            if (ok) {
                JOptionPane.showMessageDialog(this, "Đã tạo tài khoản @" + u + " thành công!", "Thành Công", JOptionPane.INFORMATION_MESSAGE);
                refreshDatabaseTables();
                dialog.dispose();
            } else {
                JOptionPane.showMessageDialog(dialog, "Tên tài khoản @" + u + " đã tồn tại!", "Trùng Lặp", JOptionPane.ERROR_MESSAGE);
            }
        });

        btnClose.addActionListener(e -> dialog.dispose());
        btnPanel.add(btnAdd);
        btnPanel.add(btnClose);

        dialog.add(form, BorderLayout.CENTER);
        dialog.add(btnPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    // --- ServerEventListener Callbacks ---

    @Override
    public void onServerStarted(int port, String sharedPath) {
        SwingUtilities.invokeLater(() -> {
            lblStatusBadge.setText("ONLINE");
            lblStatusBadge.setBackground(new Color(209, 250, 229));
            lblStatusBadge.setForeground(new Color(6, 95, 70));
            btnStartStop.setText("Dừng Server");
            btnStartStop.setBackground(ModernUI.DANGER);
            txtPort.setEditable(false);
            btnBrowseFolder.setEnabled(false);
            refreshDatabaseTables();
        });
    }

    @Override
    public void onServerStopped() {
        SwingUtilities.invokeLater(() -> {
            lblStatusBadge.setText("OFFLINE");
            lblStatusBadge.setBackground(new Color(254, 226, 226));
            lblStatusBadge.setForeground(new Color(153, 27, 27));
            btnStartStop.setText("Khởi Động Server");
            btnStartStop.setBackground(ModernUI.SUCCESS);
            txtPort.setEditable(true);
            btnBrowseFolder.setEnabled(true);
            refreshClientsTable();
            refreshDatabaseTables();
        });
    }

    @Override
    public void onClientConnected(ClientSessionInfo client) {
        refreshClientsTable();
    }

    @Override
    public void onClientDisconnected(ClientSessionInfo client) {
        refreshClientsTable();
    }

    @Override
    public void onFileReceived(String clientAddress, String relativePath, long size) {
        totalTransfersCount++;
        refreshServerFileList();
        refreshClientsTable();
        refreshDatabaseTables();
        SwingUtilities.invokeLater(() -> ModernUI.updateStatCardValue(cardTransfers, String.valueOf(totalTransfersCount)));
    }

    @Override
    public void onFileSent(String clientAddress, String relativePath, long size) {
        totalTransfersCount++;
        refreshClientsTable();
        SwingUtilities.invokeLater(() -> ModernUI.updateStatCardValue(cardTransfers, String.valueOf(totalTransfersCount)));
    }

    @Override
    public void onLog(LogLevel level, String message) {
        SwingUtilities.invokeLater(() -> {
            String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
            String tag = "[" + level.name() + "]";
            txtLogs.append(String.format("%s %-8s %s\n", time, tag, message));
            if (chkAutoScroll.isSelected()) {
                txtLogs.setCaretPosition(txtLogs.getDocument().getLength());
            }
        });
    }

    @Override
    public void onStatsUpdated() {
        refreshClientsTable();
        refreshServerFileList();
        refreshDatabaseTables();
    }
}
