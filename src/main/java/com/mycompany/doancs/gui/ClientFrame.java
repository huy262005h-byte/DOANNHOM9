package com.mycompany.doancs.gui;

import com.mycompany.doancs.client.ClientEventListener;
import com.mycompany.doancs.client.FileWatcher;
import com.mycompany.doancs.client.NetworkClient;
import com.mycompany.doancs.common.FileMetadata;
import com.mycompany.doancs.gui.components.ModernUI;
import com.mycompany.doancs.model.*;
import com.mycompany.doancs.util.FileUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class ClientFrame extends JFrame implements ClientEventListener {
    private final NetworkClient client;
    private FileWatcher autoSyncWatcher;
    private File localFolder;
    private List<SyncItem> currentSyncItems = new ArrayList<>();
    private volatile boolean isSyncInProgress = false;
    private TrayIcon trayIcon;

    // UI Controls
    private JLabel lblStatusBadge;
    private JTextField txtHost;
    private JTextField txtPort;
    private JButton btnConnect;

    // Authentication Controls
    private JLabel lblUserBadge;
    private JButton btnLogin;
    private JButton btnRegister;

    private JTextField txtLocalDir;
    private JButton btnBrowseLocal;
    private JButton btnOpenLocal;
    private JComboBox<SyncMode> cmbSyncMode;
    private JCheckBox chkAutoSync;

    // Action buttons (Tinh gọn theo phản hồi)
    private JButton btnScan;
    private JButton btnSyncSelected;
    private JButton btnSyncAll;
    private JButton btnDeleteLocal;
    private JButton btnDeleteServer;
    private JButton btnCancel;

    // Progress
    private JProgressBar progressTotal;
    private JLabel lblTotalProgressText;
    private JLabel lblSpeed;
    private JProgressBar progressCurrentFile;
    private JLabel lblCurrentFileText;

    // Table
    private DefaultTableModel syncTableModel;
    private JTable syncTable;
    private TableRowSorter<DefaultTableModel> tableSorter;
    private JTextField txtFilter;
    private JComboBox<String> cmbStatusFilter;

    // Logs
    private JTextArea txtLogs;
    private JCheckBox chkAutoScroll;

    public ClientFrame() {
        this.client = new NetworkClient();
        this.client.addListener(this);

        // Mặc định thư mục cục bộ của Client
        File defaultDir = new File(System.getProperty("user.dir"), "SyncData/ClientLocal");
        if (!defaultDir.exists()) {
            defaultDir.mkdirs();
        }
        this.localFolder = defaultDir;

        initComponents();
        setupSystemTray();

        // Kích hoạt ngay WatchService giám sát thời gian thực và nạp danh sách tệp ban đầu
        startAutoWatcher();
        refreshLocalOnlyView();
    }

    private void initComponents() {
        setTitle("Máy Khách Đồng Bộ Tệp Tin (File Sync Client) - TCP Socket & SHA-256");
        setSize(1080, 750);
        setMinimumSize(new Dimension(960, 640));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel mainPanel = new JPanel(new BorderLayout(12, 12));
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        // 1. TOP SECTION (Header, Config, Progress)
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.add(createHeaderPanel(), BorderLayout.NORTH);
        topPanel.add(createConfigCard(), BorderLayout.CENTER);
        topPanel.add(createProgressCard(), BorderLayout.SOUTH);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 2. MAIN TABBED PANE
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(ModernUI.FONT_BOLD);
        tabbedPane.addTab("  Bảng So Sánh & Trạng Thái Đồng Bộ  ", createMatrixTab());
        tabbedPane.addTab("  Nhật Ký Chi Tiết (Client Logs)  ", createLogsTab());

        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        setContentPane(mainPanel);

        // Đóng kết nối khi tắt cửa sổ
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                stopAutoWatcher();
                if (client.isConnected()) {
                    client.disconnect();
                }
            }
        });
    }

    private JPanel createHeaderPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        JLabel lblTitle = new JLabel("HỆ THỐNG MÁY KHÁCH ĐỒNG BỘ (CLIENT)");
        lblTitle.setFont(ModernUI.FONT_TITLE);
        lblTitle.setForeground(ModernUI.PRIMARY);

        lblStatusBadge = ModernUI.createBadge("CHƯA KẾT NỐI", new Color(254, 226, 226), new Color(153, 27, 27));

        panel.add(lblTitle, BorderLayout.WEST);
        panel.add(lblStatusBadge, BorderLayout.EAST);
        return panel;
    }

    private JPanel createConfigCard() {
        JPanel card = ModernUI.createCardPanel();
        card.setLayout(new GridLayout(5, 1, 6, 6));

        // Dòng 1: Máy chủ IP & Cổng & Nút Kết nối
        JPanel row1 = new JPanel(new BorderLayout(10, 0));
        row1.setOpaque(false);

        JPanel row1Left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row1Left.setOpaque(false);
        row1Left.add(new JLabel("Máy chủ (Server IP):"));
        txtHost = new JTextField("127.0.0.1", 12);
        txtHost.setFont(ModernUI.FONT_BOLD);
        txtHost.setToolTipText("Nhập 127.0.0.1 nếu chạy cùng máy, hoặc nhập IP LAN của Server (ví dụ 192.168.1.x) nếu chạy 2 máy khác nhau");
        row1Left.add(txtHost);

        row1Left.add(Box.createHorizontalStrut(10));
        row1Left.add(new JLabel("Cổng:"));
        txtPort = new JTextField("8888", 5);
        txtPort.setFont(ModernUI.FONT_BOLD);
        row1Left.add(txtPort);

        row1.add(row1Left, BorderLayout.WEST);

        btnConnect = ModernUI.createButton("Kết Nối Server", ModernUI.PRIMARY, Color.WHITE);
        btnConnect.setPreferredSize(new Dimension(140, 30));
        btnConnect.addActionListener(e -> toggleConnection());
        row1.add(btnConnect, BorderLayout.EAST);

        // Dòng 2: Xác thực tài khoản người dùng
        JPanel row2 = new JPanel(new BorderLayout(10, 0));
        row2.setOpaque(false);

        JPanel row2Left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row2Left.setOpaque(false);
        row2Left.add(new JLabel("Tài khoản người dùng:"));
        lblUserBadge = ModernUI.createBadge("Chưa đăng nhập", new Color(241, 245, 249), new Color(100, 116, 139));
        row2Left.add(lblUserBadge);

        btnLogin = ModernUI.createButton("Đăng Nhập", new Color(224, 231, 255), new Color(67, 56, 202));
        btnLogin.setEnabled(false);
        btnLogin.addActionListener(e -> showLoginDialog());
        row2Left.add(btnLogin);

        btnRegister = ModernUI.createButton("Đăng Ký Tài Khoản", new Color(241, 245, 249), new Color(30, 41, 59));
        btnRegister.setEnabled(false);
        btnRegister.addActionListener(e -> showRegisterDialog());
        row2Left.add(btnRegister);

        row2.add(row2Left, BorderLayout.WEST);

        // Dòng 3: Thư mục đồng bộ & Nút duyệt
        JPanel row3 = new JPanel(new BorderLayout(10, 0));
        row3.setOpaque(false);

        JLabel lblFolder = new JLabel("Thư mục đồng bộ: ");
        lblFolder.setFont(ModernUI.FONT_BOLD);
        row3.add(lblFolder, BorderLayout.WEST);

        txtLocalDir = new JTextField(localFolder.getAbsolutePath());
        txtLocalDir.setEditable(false);
        txtLocalDir.setFont(ModernUI.FONT_REGULAR);
        txtLocalDir.setToolTipText(localFolder.getAbsolutePath());
        row3.add(txtLocalDir, BorderLayout.CENTER);

        JPanel folderActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        folderActions.setOpaque(false);
        btnBrowseLocal = ModernUI.createButton("Chọn thư mục...", new Color(241, 245, 249), new Color(30, 41, 59));
        btnBrowseLocal.addActionListener(e -> chooseLocalDirectory());

        btnOpenLocal = ModernUI.createButton("Mở thư mục", new Color(241, 245, 249), new Color(30, 41, 59));
        btnOpenLocal.addActionListener(e -> FileUtils.openFolderInExplorer(localFolder));

        folderActions.add(btnBrowseLocal);
        folderActions.add(btnOpenLocal);
        row3.add(folderActions, BorderLayout.EAST);

        // Dòng 4: Chế độ đồng bộ & Tự động đồng bộ (Mặc định BẬT)
        JPanel row4 = new JPanel(new BorderLayout(10, 0));
        row4.setOpaque(false);

        JPanel row4Left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row4Left.setOpaque(false);
        row4Left.add(new JLabel("Chế độ đồng bộ:"));
        cmbSyncMode = new JComboBox<>(SyncMode.values());
        cmbSyncMode.setFont(ModernUI.FONT_REGULAR);
        cmbSyncMode.setPreferredSize(new Dimension(320, 28));
        row4Left.add(cmbSyncMode);
        row4.add(row4Left, BorderLayout.WEST);

        chkAutoSync = new JCheckBox("Tự động đồng bộ hai chiều thời gian thực (Auto-Sync & Push)", false);
        chkAutoSync.setFont(ModernUI.FONT_BOLD);
        chkAutoSync.addActionListener(e -> onAutoSyncToggled());
        row4.add(chkAutoSync, BorderLayout.EAST);

        // Dòng 5: Thanh công cụ thao tác TINH GỌN
        JPanel row5 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row5.setOpaque(false);

        btnScan = ModernUI.createButton("Quét & So Sánh", new Color(241, 245, 249), new Color(30, 41, 59));
        btnScan.setEnabled(false);
        btnScan.addActionListener(e -> performScanAndCompare(false));

        btnSyncSelected = ModernUI.createButton("Đồng Bộ Tệp Đã Chọn", new Color(14, 165, 233), Color.WHITE);
        btnSyncSelected.setEnabled(false);
        btnSyncSelected.setToolTipText("Chỉ đồng bộ đúng 1 hoặc các tệp đang được chọn trong bảng (Không đồng bộ hàng loạt)");
        btnSyncSelected.addActionListener(e -> performSyncSelected());

        btnSyncAll = ModernUI.createButton("Đồng Bộ Tất Cả", ModernUI.SUCCESS, Color.WHITE);
        btnSyncAll.setEnabled(false);
        btnSyncAll.addActionListener(e -> performExecuteSyncAll());

        btnDeleteLocal = ModernUI.createButton("Xóa Trên Máy Này", new Color(245, 158, 11), Color.WHITE);
        btnDeleteLocal.setEnabled(false);
        btnDeleteLocal.setToolTipText("Chỉ xóa tệp khỏi máy Client này (giải phóng ổ đĩa). Tệp trên Server và các máy khác vẫn được BẢO TOÀN NGUYÊN VẸN 100%!");
        btnDeleteLocal.addActionListener(e -> performDeleteLocalOnly());

        btnDeleteServer = ModernUI.createButton("Xóa Trên Server", ModernUI.DANGER, Color.WHITE);
        btnDeleteServer.setEnabled(false);
        btnDeleteServer.setToolTipText("Xóa tệp khỏi Máy Chủ Server (chuyển vào Thùng rác an toàn .recycle_bin trên Server) và dọn dẹp bản sao trên máy này");
        btnDeleteServer.addActionListener(e -> performDeleteServerAndLocal());

        btnCancel = ModernUI.createButton("Hủy Bỏ", new Color(241, 245, 249), new Color(100, 116, 139));
        btnCancel.setEnabled(false);
        btnCancel.addActionListener(e -> {
            client.requestCancel();
            btnCancel.setEnabled(false);
        });

        row5.add(btnScan);
        row5.add(btnSyncSelected);
        row5.add(btnSyncAll);
        row5.add(btnDeleteLocal);
        row5.add(btnDeleteServer);
        row5.add(btnCancel);

        card.add(row1);
        card.add(row2);
        card.add(row3);
        card.add(row4);
        card.add(row5);
        return card;
    }

    private JPanel createProgressCard() {
        JPanel card = ModernUI.createCardPanel();
        card.setLayout(new GridLayout(2, 1, 6, 6));

        // Tiến trình tổng
        JPanel panelTotal = new JPanel(new BorderLayout(8, 2));
        lblTotalProgressText = new JLabel("Tiến trình tổng: Sẵn sàng");
        lblTotalProgressText.setFont(ModernUI.FONT_BOLD);

        lblSpeed = new JLabel("0.0 KB/s");
        lblSpeed.setFont(ModernUI.FONT_BOLD);
        lblSpeed.setForeground(ModernUI.PRIMARY);

        JPanel totalHeader = new JPanel(new BorderLayout());
        totalHeader.add(lblTotalProgressText, BorderLayout.WEST);
        totalHeader.add(lblSpeed, BorderLayout.EAST);

        progressTotal = new JProgressBar(0, 100);
        progressTotal.setStringPainted(true);
        progressTotal.setValue(0);
        progressTotal.setPreferredSize(new Dimension(100, 18));

        panelTotal.add(totalHeader, BorderLayout.NORTH);
        panelTotal.add(progressTotal, BorderLayout.CENTER);

        // Tiến trình tệp hiện tại
        JPanel panelCurrent = new JPanel(new BorderLayout(8, 2));
        lblCurrentFileText = new JLabel("Tệp hiện tại: -");
        lblCurrentFileText.setFont(ModernUI.FONT_REGULAR);
        lblCurrentFileText.setForeground(ModernUI.TEXT_MUTED);

        progressCurrentFile = new JProgressBar(0, 100);
        progressCurrentFile.setStringPainted(true);
        progressCurrentFile.setValue(0);
        progressCurrentFile.setPreferredSize(new Dimension(100, 14));

        panelCurrent.add(lblCurrentFileText, BorderLayout.NORTH);
        panelCurrent.add(progressCurrentFile, BorderLayout.CENTER);

        card.add(panelTotal);
        card.add(panelCurrent);
        return card;
    }

    private JPanel createMatrixTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(10, 2, 2, 2));

        // Toolbar Tìm kiếm & Lọc trạng thái tệp (Nâng cấp UI/UX)
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        filterBar.setBorder(new EmptyBorder(0, 0, 6, 0));

        JLabel lblSearch = new JLabel("Tìm kiếm:");
        lblSearch.setFont(ModernUI.FONT_BOLD);
        txtFilter = new JTextField(18);
        txtFilter.putClientProperty("JTextField.placeholderText", "Nhập tên tệp hoặc đuôi file...");

        JLabel lblFilter = new JLabel("Lọc trạng thái:");
        lblFilter.setFont(ModernUI.FONT_BOLD);
        cmbStatusFilter = new JComboBox<>(new String[]{
                "Tất cả trạng thái",
                "Cần tải lên (UPLOAD)",
                "Cần tải về (DOWNLOAD)",
                "Đã đồng bộ (UP_TO_DATE)",
                "Xung đột (CONFLICT)"
        });

        filterBar.add(lblSearch);
        filterBar.add(txtFilter);
        filterBar.add(Box.createHorizontalStrut(12));
        filterBar.add(lblFilter);
        filterBar.add(cmbStatusFilter);

        panel.add(filterBar, BorderLayout.NORTH);

        String[] columns = {
                "Hành Động", "Đường Dẫn Tệp", "Kích Thước Client", "Kích Thước Server",
                "Thời Gian Client", "Thời Gian Server", "Lý Do So Sánh", "Trạng Thái"
        };

        syncTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        syncTable = new JTable(syncTableModel);
        tableSorter = new TableRowSorter<>(syncTableModel);
        syncTable.setRowSorter(tableSorter);

        Runnable applyFilters = () -> {
            String text = txtFilter.getText().trim();
            int selectedStatusIndex = cmbStatusFilter.getSelectedIndex();

            List<RowFilter<Object, Object>> filters = new ArrayList<>();
            if (!text.isEmpty()) {
                filters.add(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text), 1));
            }
            if (selectedStatusIndex == 1) { // UPLOAD
                filters.add(RowFilter.regexFilter("(?i)UPLOAD|Tải lên", 0));
            } else if (selectedStatusIndex == 2) { // DOWNLOAD
                filters.add(RowFilter.regexFilter("(?i)DOWNLOAD|Tải về", 0));
            } else if (selectedStatusIndex == 3) { // UP_TO_DATE
                filters.add(RowFilter.regexFilter("(?i)UP_TO_DATE|Đã khớp", 0));
            } else if (selectedStatusIndex == 4) { // CONFLICT
                filters.add(RowFilter.regexFilter("(?i)CONFLICT|Xung đột", 0));
            }

            if (filters.isEmpty()) {
                tableSorter.setRowFilter(null);
            } else {
                tableSorter.setRowFilter(RowFilter.andFilter(filters));
            }
        };

        txtFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilters.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilters.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilters.run(); }
        });
        cmbStatusFilter.addActionListener(e -> applyFilters.run());

        syncTable.getColumnModel().getColumn(0).setPreferredWidth(130);
        syncTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        syncTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        syncTable.getColumnModel().getColumn(3).setPreferredWidth(100);
        syncTable.getColumnModel().getColumn(4).setPreferredWidth(140);
        syncTable.getColumnModel().getColumn(5).setPreferredWidth(140);
        syncTable.getColumnModel().getColumn(6).setPreferredWidth(230);
        syncTable.getColumnModel().getColumn(7).setPreferredWidth(130);

        syncTable.getColumnModel().getColumn(0).setCellRenderer(new ModernUI.BadgeCellRenderer());
        syncTable.getColumnModel().getColumn(7).setCellRenderer(new ModernUI.BadgeCellRenderer());

        // Bật nút xóa và đồng bộ tệp đã chọn khi có dòng được chọn
        syncTable.getSelectionModel().addListSelectionListener(e -> {
            boolean hasSelection = syncTable.getSelectedRowCount() > 0 && !isSyncInProgress;
            btnDeleteLocal.setEnabled(hasSelection);
            btnDeleteServer.setEnabled(hasSelection && client.isConnected());
            btnSyncSelected.setEnabled(hasSelection && client.isConnected());
        });

        // Thiết lập Menu chuột phải (Context Menu) cho từng tệp
        setupTableContextMenu();

        panel.add(new JScrollPane(syncTable), BorderLayout.CENTER);
        return panel;
    }

    private void setupTableContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem menuSync = new JMenuItem("Đồng bộ tệp này (Chỉ mục đã chọn)");
        menuSync.setFont(ModernUI.FONT_BOLD);
        menuSync.addActionListener(e -> performSyncSelected());

        JMenuItem menuDeleteLocal = new JMenuItem("Xóa trên máy này (Chỉ xóa cục bộ, giữ nguyên Server)");
        menuDeleteLocal.addActionListener(e -> performDeleteLocalOnly());

        JMenuItem menuDeleteServer = new JMenuItem("Xóa trên Server & Máy này (Vào thùng rác Server)");
        menuDeleteServer.setForeground(new Color(185, 28, 28));
        menuDeleteServer.addActionListener(e -> performDeleteServerAndLocal());

        JMenuItem menuUpload = new JMenuItem("Tải lên tệp này (Upload)");
        menuUpload.addActionListener(e -> performUploadSelected());

        JMenuItem menuDownload = new JMenuItem("Tải về tệp này (Download)");
        menuDownload.addActionListener(e -> performDownloadSelected());

        JMenuItem menuVersions = new JMenuItem("Xem lịch sử phiên bản (Versioning)...");
        menuVersions.addActionListener(e -> showFileVersionDialog());

        JMenuItem menuOpenLocal = new JMenuItem("Mở tệp này trên máy");
        menuOpenLocal.addActionListener(e -> {
            int row = syncTable.getSelectedRow();
            if (row >= 0) {
                int modelRow = syncTable.convertRowIndexToModel(row);
                String path = (String) syncTableModel.getValueAt(modelRow, 1);
                File file = new File(localFolder, path);
                if (file.exists()) {
                    try {
                        Desktop.getDesktop().open(file);
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(this, "Không thể mở tệp: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                    }
                } else {
                    JOptionPane.showMessageDialog(this, "Tệp chưa có trên máy cục bộ!", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        });

        contextMenu.add(menuSync);
        contextMenu.addSeparator();
        contextMenu.add(menuDeleteLocal);
        contextMenu.add(menuDeleteServer);
        contextMenu.addSeparator();
        contextMenu.add(menuUpload);
        contextMenu.add(menuDownload);
        contextMenu.addSeparator();
        contextMenu.add(menuVersions);
        contextMenu.add(menuOpenLocal);

        syncTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = syncTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && !syncTable.isRowSelected(row)) {
                        syncTable.setRowSelectionInterval(row, row);
                    }
                    if (syncTable.getSelectedRowCount() > 0) {
                        contextMenu.show(syncTable, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    private void showFileVersionDialog() {
        int row = syncTable.getSelectedRow();
        if (row < 0) return;
        int modelRow = syncTable.convertRowIndexToModel(row);
        String path = (String) syncTableModel.getValueAt(modelRow, 1);
        if (path == null || path.isEmpty()) return;

        new Thread(() -> {
            try {
                List<com.mycompany.doancs.model.FileVersionInfo> versions = client.fetchFileVersions(path);
                SwingUtilities.invokeLater(() -> {
                    if (versions.isEmpty()) {
                        JOptionPane.showMessageDialog(this,
                                "Tệp '" + path + "' chưa có phiên bản cũ nào được ghi nhận trên máy chủ.",
                                "Thông Báo Lịch Sử Phiên Bản", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    FileVersionDialog dlg = new FileVersionDialog(this, path, versions, (ver) -> {
                        new Thread(() -> {
                            try {
                                boolean ok = client.restoreFileVersion(ver.getId(), path);
                                if (ok) {
                                    SwingUtilities.invokeLater(() -> {
                                        JOptionPane.showMessageDialog(this,
                                                "Đã khôi phục thành công tệp '" + path + "' về phiên bản v" + ver.getVersion() + "!",
                                                "Khôi Phục Thành Công", JOptionPane.INFORMATION_MESSAGE);
                                        performScanAndCompare(false);
                                    });
                                }
                            } catch (Exception ex) {
                                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                                        "Lỗi khôi phục: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE));
                            }
                        }).start();
                    });
                    dlg.setVisible(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Lỗi tải danh sách phiên bản: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE));
            }
        }).start();
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

    private void chooseLocalDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn Thư Mục Đồng Bộ Của Client");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setSelectedFile(localFolder);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            localFolder = chooser.getSelectedFile();
            txtLocalDir.setText(localFolder.getAbsolutePath());
            txtLocalDir.setToolTipText(localFolder.getAbsolutePath());
            startAutoWatcher();
            if (client.isConnected()) {
                performScanAndCompare(false);
            } else {
                refreshLocalOnlyView();
            }
        }
    }

    private void toggleConnection() {
        if (!client.isConnected()) {
            String host = txtHost.getText().trim();
            int port;
            try {
                port = Integer.parseInt(txtPort.getText().trim());
                if (port < 1024 || port > 65535) throw new Exception();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Cổng TCP không hợp lệ!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }

            btnConnect.setEnabled(false);
            btnConnect.setText("Đang kết nối...");

            new Thread(() -> {
                try {
                    client.connect(host, port);
                } catch (IOException ex) {
                    SwingUtilities.invokeLater(() -> {
                        btnConnect.setEnabled(true);
                        btnConnect.setText("Kết Nối Server");
                        JOptionPane.showMessageDialog(this,
                                "Không thể kết nối đến " + host + ":" + port + "\n\n" +
                                "Gợi ý kiểm tra:\n" +
                                "1. Máy chủ Server đã bấm 'Khởi Động Server' chưa?\n" +
                                "2. Nếu chạy 2 máy khác nhau, hãy đảm bảo 2 máy cùng bắt chung một mạng Wi-Fi/LAN.\n" +
                                "3. Nhập đúng IP của máy chủ (xem mục 'IP khả dụng' trên cửa sổ Server).\n" +
                                "4. Kiểm tra tường lửa (Windows Firewall) đã cho phép Java chưa.\n\n" +
                                "Chi tiết lỗi: " + ex.getMessage(),
                                "Lỗi Kết Nối Socket", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }).start();
        } else {
            client.disconnect();
        }
    }

    private void showLoginDialog() {
        if (!client.isConnected()) {
            JOptionPane.showMessageDialog(this, "Vui lòng bấm 'Kết Nối Server' trước khi đăng nhập!", "Chưa kết nối", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JDialog dialog = new JDialog(this, "Xác Thực Tài Khoản Người Dùng", true);
        dialog.setSize(420, 270);
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(new EmptyBorder(16, 22, 10, 22));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;

        JLabel lblUser = new JLabel("Tên đăng nhập (Username):");
        lblUser.setFont(ModernUI.FONT_BOLD);
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 6, 0);
        formPanel.add(lblUser, gbc);

        JTextField txtUser = new JTextField("", 15);
        txtUser.setPreferredSize(new Dimension(340, 36));
        txtUser.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 14, 0);
        formPanel.add(txtUser, gbc);

        JLabel lblPass = new JLabel("Mật khẩu:");
        lblPass.setFont(ModernUI.FONT_BOLD);
        gbc.gridy = 2;
        gbc.insets = new Insets(0, 0, 6, 0);
        formPanel.add(lblPass, gbc);

        JPasswordField txtPass = new JPasswordField("", 15);
        txtPass.setPreferredSize(new Dimension(340, 36));
        txtPass.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        gbc.gridy = 3;
        gbc.insets = new Insets(0, 0, 4, 0);
        formPanel.add(txtPass, gbc);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 14));
        bottomPanel.setBorder(new EmptyBorder(0, 22, 14, 22));

        JButton btnSubmit = ModernUI.createButton("Đăng Nhập", ModernUI.PRIMARY, Color.WHITE);
        btnSubmit.setPreferredSize(new Dimension(110, 34));
        JButton btnCancelDialog = ModernUI.createButton("Hủy", new Color(241, 245, 249), new Color(30, 41, 59));
        btnCancelDialog.setPreferredSize(new Dimension(80, 34));

        btnSubmit.addActionListener(e -> {
            String u = txtUser.getText().trim();
            String p = new String(txtPass.getPassword()).trim();
            if (u.isEmpty() || p.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Vui lòng nhập đầy đủ tên đăng nhập và mật khẩu!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }
            btnSubmit.setEnabled(false);
            btnSubmit.setText("Đang xác thực...");

            new Thread(() -> {
                try {
                    client.login(u, p);
                    SwingUtilities.invokeLater(() -> {
                        dialog.dispose();
                        lblUserBadge.setText("@" + client.getCurrentUser() + " (Đã xác thực)");
                        lblUserBadge.setBackground(new Color(209, 250, 229));
                        lblUserBadge.setForeground(new Color(6, 95, 70));
                        btnLogin.setText("Đổi tài khoản");
                        btnScan.setEnabled(true);
                        JOptionPane.showMessageDialog(this, "Đăng nhập thành công với tài khoản @" + client.getCurrentUser() + "!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
                        performScanAndCompare(false);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        btnSubmit.setEnabled(true);
                        btnSubmit.setText("Đăng Nhập");
                        JOptionPane.showMessageDialog(dialog, "Đăng nhập thất bại: " + ex.getMessage(), "Lỗi Xác Thực", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }).start();
        });

        btnCancelDialog.addActionListener(e -> dialog.dispose());

        bottomPanel.add(btnSubmit);
        bottomPanel.add(btnCancelDialog);

        dialog.add(formPanel, BorderLayout.CENTER);
        dialog.add(bottomPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void showRegisterDialog() {
        if (!client.isConnected()) {
            JOptionPane.showMessageDialog(this, "Vui lòng bấm 'Kết Nối Server' trước khi đăng ký!", "Chưa kết nối", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JDialog dialog = new JDialog(this, "Đăng Ký Tài Khoản Mới", true);
        dialog.setSize(440, 350);
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(new EmptyBorder(16, 22, 10, 22));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;

        JLabel lblUser = new JLabel("Tên đăng nhập mới (tối thiểu 3 ký tự):");
        lblUser.setFont(ModernUI.FONT_BOLD);
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 6, 0);
        formPanel.add(lblUser, gbc);

        JTextField txtUser = new JTextField("", 15);
        txtUser.setPreferredSize(new Dimension(350, 36));
        txtUser.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 12, 0);
        formPanel.add(txtUser, gbc);

        JLabel lblPass = new JLabel("Mật khẩu (tối thiểu 4 ký tự):");
        lblPass.setFont(ModernUI.FONT_BOLD);
        gbc.gridy = 2;
        gbc.insets = new Insets(0, 0, 6, 0);
        formPanel.add(lblPass, gbc);

        JPasswordField txtPass = new JPasswordField("", 15);
        txtPass.setPreferredSize(new Dimension(350, 36));
        txtPass.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        gbc.gridy = 3;
        gbc.insets = new Insets(0, 0, 12, 0);
        formPanel.add(txtPass, gbc);

        JLabel lblConfirm = new JLabel("Xác nhận mật khẩu:");
        lblConfirm.setFont(ModernUI.FONT_BOLD);
        gbc.gridy = 4;
        gbc.insets = new Insets(0, 0, 6, 0);
        formPanel.add(lblConfirm, gbc);

        JPasswordField txtConfirm = new JPasswordField("", 15);
        txtConfirm.setPreferredSize(new Dimension(350, 36));
        txtConfirm.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        gbc.gridy = 5;
        gbc.insets = new Insets(0, 0, 4, 0);
        formPanel.add(txtConfirm, gbc);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 14));
        bottomPanel.setBorder(new EmptyBorder(0, 22, 14, 22));

        JButton btnSubmit = ModernUI.createButton("Tạo Tài Khoản", ModernUI.SUCCESS, Color.WHITE);
        btnSubmit.setPreferredSize(new Dimension(130, 34));
        JButton btnCancelDialog = ModernUI.createButton("Hủy", new Color(241, 245, 249), new Color(30, 41, 59));
        btnCancelDialog.setPreferredSize(new Dimension(80, 34));

        btnSubmit.addActionListener(e -> {
            String u = txtUser.getText().trim();
            String p = new String(txtPass.getPassword()).trim();
            String cp = new String(txtConfirm.getPassword()).trim();

            if (u.length() < 3) {
                JOptionPane.showMessageDialog(dialog, "Tên đăng nhập phải có ít nhất 3 ký tự!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (p.length() < 4) {
                JOptionPane.showMessageDialog(dialog, "Mật khẩu phải có ít nhất 4 ký tự!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!p.equals(cp)) {
                JOptionPane.showMessageDialog(dialog, "Mật khẩu xác nhận không khớp!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }

            btnSubmit.setEnabled(false);
            btnSubmit.setText("Đang đăng ký...");

            new Thread(() -> {
                try {
                    client.register(u, p);
                    SwingUtilities.invokeLater(() -> {
                        dialog.dispose();
                        JOptionPane.showMessageDialog(this, "Đăng ký tài khoản @" + u + " thành công! Bạn có thể đăng nhập ngay.", "Đăng Ký Thành Công", JOptionPane.INFORMATION_MESSAGE);
                        showLoginDialog();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        btnSubmit.setEnabled(true);
                        btnSubmit.setText("Tạo Tài Khoản");
                        JOptionPane.showMessageDialog(dialog, "Đăng ký thất bại: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }).start();
        });

        btnCancelDialog.addActionListener(e -> dialog.dispose());

        bottomPanel.add(btnSubmit);
        bottomPanel.add(btnCancelDialog);

        dialog.add(formPanel, BorderLayout.CENTER);
        dialog.add(bottomPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void performScanAndCompare() {
        performScanAndCompare(false);
    }

    /**
     * Quét và so sánh tệp tin giữa Client và Server.
     * @param autoExecuteIfChangesFound Nếu true, tự động thực hiện đồng bộ ngay khi thấy có tệp cần truyền
     */
    private void performScanAndCompare(boolean autoExecuteIfChangesFound) {
        if (!client.isConnected() || isSyncInProgress) return;

        setButtonsBusyState(true);

        new Thread(() -> {
            try {
                // 1. Quét tệp cục bộ (lập chỉ mục & tính SHA-256)
                List<FileMetadata> localFiles = client.scanLocalFiles(localFolder);

                // 2. Gửi Packet yêu cầu danh mục tệp từ máy chủ
                List<FileMetadata> remoteFiles = client.fetchRemoteFileList();

                // 3. So sánh siêu dữ liệu tệp (kích thước, timestamp, mã băm SHA-256)
                SyncMode mode = (SyncMode) cmbSyncMode.getSelectedItem();
                currentSyncItems = client.compare(localFiles, remoteFiles, mode);

                // 4. Cập nhật bảng
                SwingUtilities.invokeLater(() -> {
                    updateTableWithComparison(currentSyncItems);
                    setButtonsBusyState(false);

                    if (autoExecuteIfChangesFound && !currentSyncItems.isEmpty()) {
                        boolean hasAction = currentSyncItems.stream().anyMatch(i ->
                                i.getAction() == SyncAction.UPLOAD ||
                                i.getAction() == SyncAction.DOWNLOAD ||
                                i.getAction() == SyncAction.CONFLICT);
                        if (hasAction) {
                            performExecuteSyncAll();
                        }
                    }
                });

            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    setButtonsBusyState(false);
                    JOptionPane.showMessageDialog(this, "Lỗi khi quét & so sánh tệp: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    /**
     * Đồng bộ tất cả các tệp có trong danh sách.
     */
    private void performExecuteSyncAll() {
        if (!client.isConnected() || isSyncInProgress || currentSyncItems.isEmpty()) return;
        executeSpecificItems(new ArrayList<>(currentSyncItems));
    }

    /**
     * CHẾ ĐỘ 1: XÓA CỤC BỘ (CHỈ XÓA TRÊN MÁY NÀY)
     * - Chỉ xóa tệp vật lý trong thư mục máy Client để giải phóng dung lượng đĩa.
     * - HOÀN TOÀN KHÔNG gửi lệnh xóa lên Server.
     * - Tệp trên Server và các máy Client khác được BẢO TOÀN NGUYÊN VẸN 100%.
     * - Sau khi xóa, tệp chuyển sang trạng thái DOWNLOAD trên bảng để có thể tải lại khi cần.
     */
    private void performDeleteLocalOnly() {
        int[] selectedRows = syncTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this,
                    "Vui lòng nhấp chọn 1 hoặc nhiều tệp trong bảng để xóa trên máy này!",
                    "Chưa Chọn Tệp", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int count = selectedRows.length;
        int firstModelRow = syncTable.convertRowIndexToModel(selectedRows[0]);
        String firstPath = (String) syncTableModel.getValueAt(firstModelRow, 1);
        String msg = (count == 1)
                ? "Bạn có chắc chắn muốn XÓA TỆP NÀY KHỎI MÁY CỤC BỘ NÀY?\n\n" +
                  "- Tệp: " + firstPath + "\n\n" +
                  "- Tệp trên Server và các máy Client khác vẫn được BẢO TOÀN NGUYÊN VẸN 100%.\n" +
                  "- Bạn có thể tải lại tệp này về máy bất cứ lúc nào qua nút [Đồng Bộ Tệp Đã Chọn]."
                : "Bạn có chắc chắn muốn XÓA " + count + " TỆP ĐÃ CHỌN KHỎI MÁY CỤC BỘ NÀY?\n\n" +
                  "- Các tệp trên Server và các máy Client khác vẫn được BẢO TOÀN NGUYÊN VẸN 100%.\n" +
                  "- Bạn có thể tải lại về bất cứ lúc nào qua nút [Đồng Bộ Tệp Đã Chọn].";

        int confirm = JOptionPane.showConfirmDialog(this, msg, "Xác Nhận Xóa Cục Bộ (Bảo Toàn Server)",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        List<String> pathsToDelete = new ArrayList<>();
        for (int viewRow : selectedRows) {
            int modelRow = syncTable.convertRowIndexToModel(viewRow);
            pathsToDelete.add((String) syncTableModel.getValueAt(modelRow, 1));
        }

        isSyncInProgress = true;
        if (autoSyncWatcher != null) {
            autoSyncWatcher.setPaused(true);
        }
        setButtonsBusyState(true);

        new Thread(() -> {
            int successCount = 0;
            for (String relPath : pathsToDelete) {
                File localFile = new File(localFolder, relPath);
                if (localFile.exists()) {
                    boolean locDel = localFile.delete();
                    if (locDel) {
                        successCount++;
                        client.log(ClientEventListener.LogLevel.INFO, "[Xóa Cục Bộ] Đã xóa tệp trên máy này: " + relPath);
                    }
                } else {
                    client.log(ClientEventListener.LogLevel.INFO, "[Xóa Cục Bộ] Tệp chưa tải về máy: " + relPath);
                }
            }

            final int finalSuccess = successCount;
            isSyncInProgress = false;
            SwingUtilities.invokeLater(() -> {
                new javax.swing.Timer(1200, evt -> {
                    if (autoSyncWatcher != null && !isSyncInProgress) {
                        autoSyncWatcher.setPaused(false);
                    }
                }) {{
                    setRepeats(false);
                    start();
                }};
                setButtonsBusyState(false);
                JOptionPane.showMessageDialog(this,
                        "Đã xóa thành công " + finalSuccess + " tệp khỏi máy cục bộ!\n" +
                        "Dữ liệu trên Server vẫn được bảo toàn nguyên vẹn 100%.",
                        "Hoàn Tất Xóa Cục Bộ", JOptionPane.INFORMATION_MESSAGE);
                if (client.isConnected()) {
                    performScanAndCompare(false);
                } else {
                    refreshLocalOnlyView();
                }
            });
        }).start();
    }

    /**
     * CHẾ ĐỘ 2: XÓA TRÊN SERVER (VÀ DỌN DẸP MÁY NÀY)
     * - Gửi yêu cầu xóa lên Server (Server chuyển tệp vào Thùng rác an toàn .recycle_bin).
     * - Đồng thời dọn dẹp bản sao cục bộ tương ứng trên máy này.
     * - Có cảnh báo màu đỏ xác nhận rõ ràng trước khi thực thi.
     */
    private void performDeleteServerAndLocal() {
        if (!client.isConnected()) {
            JOptionPane.showMessageDialog(this,
                    "Bạn chưa kết nối đến Server! Chỉ có thể dùng chức năng 'Xóa Trên Máy Này'.",
                    "Chưa Kết Nối Server", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int[] selectedRows = syncTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this,
                    "Vui lòng nhấp chọn 1 hoặc nhiều tệp trong bảng để xóa trên Server!",
                    "Chưa Chọn Tệp", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int count = selectedRows.length;
        int firstModelRow = syncTable.convertRowIndexToModel(selectedRows[0]);
        String firstPath = (String) syncTableModel.getValueAt(firstModelRow, 1);
        String msg = (count == 1)
                ? "CẢNH BÁO XÓA TỆP TRÊN MÁY CHỦ (SERVER):\n\n" +
                  "Bạn đang yêu cầu xóa tệp '" + firstPath + "' khỏi Server!\n\n" +
                  "- Tệp sẽ được chuyển vào Thùng rác an toàn (.recycle_bin) trên Server (có thể khôi phục).\n" +
                  "- Bản sao trên máy này cũng sẽ được dọn dẹp.\n\n" +
                  "Bạn có chắc chắn muốn xóa tệp này trên Server không?"
                : "CẢNH BÁO XÓA TỆP TRÊN MÁY CHỦ (SERVER):\n\n" +
                  "Bạn đang yêu cầu xóa " + count + " tệp đã chọn khỏi Server!\n\n" +
                  "- Các tệp sẽ được chuyển vào Thùng rác an toàn (.recycle_bin) trên Server (có thể khôi phục).\n" +
                  "- Bản sao trên máy này cũng sẽ được dọn dẹp.\n\n" +
                  "Bạn có chắc chắn muốn xóa các tệp này trên Server không?";

        int confirm = JOptionPane.showConfirmDialog(this, msg, "Xác Nhận Xóa Tệp Trên Server",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        List<String> pathsToDelete = new ArrayList<>();
        for (int viewRow : selectedRows) {
            int modelRow = syncTable.convertRowIndexToModel(viewRow);
            pathsToDelete.add((String) syncTableModel.getValueAt(modelRow, 1));
        }

        isSyncInProgress = true;
        if (autoSyncWatcher != null) {
            autoSyncWatcher.setPaused(true);
        }
        setButtonsBusyState(true);

        new Thread(() -> {
            int successCount = 0;
            for (String relPath : pathsToDelete) {
                // 1. Xóa ở máy cục bộ
                File localFile = new File(localFolder, relPath);
                if (localFile.exists()) {
                    boolean locDel = localFile.delete();
                    if (locDel) {
                        client.log(ClientEventListener.LogLevel.INFO, "Đã xóa bản sao cục bộ: " + relPath);
                    }
                }

                // 2. Gửi lệnh xóa lên Server
                try {
                    boolean remDel = client.deleteRemoteFile(relPath);
                    if (remDel) {
                        successCount++;
                        client.log(ClientEventListener.LogLevel.INFO, "[Server Recycle Bin] Đã chuyển tệp vào thùng rác: " + relPath);
                    }
                } catch (IOException ex) {
                    client.log(ClientEventListener.LogLevel.ERROR, "Lỗi khi gửi lệnh xóa lên Server: " + ex.getMessage());
                }
            }

            final int finalSuccess = successCount;
            isSyncInProgress = false;
            SwingUtilities.invokeLater(() -> {
                new javax.swing.Timer(1200, evt -> {
                    if (autoSyncWatcher != null && !isSyncInProgress) {
                        autoSyncWatcher.setPaused(false);
                    }
                }) {{
                    setRepeats(false);
                    start();
                }};
                setButtonsBusyState(false);
                JOptionPane.showMessageDialog(this,
                        "Đã chuyển thành công " + finalSuccess + " / " + pathsToDelete.size() + " tệp vào Thùng rác trên Server!",
                        "Hoàn Tất Xóa Trên Server", JOptionPane.INFORMATION_MESSAGE);
                performScanAndCompare(false);
            });
        }).start();
    }

    /**
     * Đồng bộ CHỈ các tệp đang được người dùng bôi đen / chọn trong bảng danh sách.
     * Giải quyết triệt để vấn đề "đồng bộ 1 loạt tất cả các tệp" khi chỉ muốn đồng bộ 1 tệp xác định.
     */
    private void performSyncSelected() {
        if (!client.isConnected() || isSyncInProgress) return;
        int[] selectedRows = syncTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this,
                    "Vui lòng nhấp chọn 1 hoặc nhiều tệp trong bảng để đồng bộ!",
                    "Chưa Chọn Tệp", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        List<SyncItem> selectedItems = new ArrayList<>();
        for (int viewRow : selectedRows) {
            int modelRow = syncTable.convertRowIndexToModel(viewRow);
            String path = (String) syncTableModel.getValueAt(modelRow, 1);
            for (SyncItem item : currentSyncItems) {
                if (item.getRelativePath().equals(path)) {
                    if (item.getAction() == SyncAction.UPLOAD ||
                        item.getAction() == SyncAction.DOWNLOAD ||
                        item.getAction() == SyncAction.CONFLICT) {
                        selectedItems.add(item);
                    }
                    break;
                }
            }
        }

        if (selectedItems.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Các tệp bạn vừa chọn đều đã đồng bộ khớp hoàn toàn (UP_TO_DATE) hoặc không cần đồng bộ thêm!",
                    "Thông Báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        executeSpecificItems(selectedItems);
    }

    /**
     * Chỉ đồng bộ đúng 1 tệp xác định dựa trên tên tệp / đường dẫn tương đối.
     * Tránh quét & kéo toàn bộ danh mục của tất cả tệp khác.
     */
    private void performSyncSingleFile(String fileName) {
        if (!client.isConnected() || isSyncInProgress) return;

        new Thread(() -> {
            try {
                List<FileMetadata> localFiles = client.scanLocalFiles(localFolder);
                List<FileMetadata> remoteFiles = client.fetchRemoteFileList();
                SyncMode mode = (SyncMode) cmbSyncMode.getSelectedItem();
                List<SyncItem> compared = client.compare(localFiles, remoteFiles, mode);

                SyncItem targetItem = null;
                for (SyncItem item : compared) {
                    String itemPath = item.getRelativePath().replace('\\', '/');
                    String targetPath = fileName.replace('\\', '/');
                    if (itemPath.equalsIgnoreCase(targetPath)) {
                        targetItem = item;
                        break;
                    }
                }

                if (targetItem != null && (targetItem.getAction() == SyncAction.UPLOAD ||
                                           targetItem.getAction() == SyncAction.DOWNLOAD ||
                                           targetItem.getAction() == SyncAction.CONFLICT)) {
                    final SyncItem toSync = targetItem;
                    SwingUtilities.invokeLater(() -> executeSpecificItems(Collections.singletonList(toSync)));
                } else {
                    SwingUtilities.invokeLater(() -> performScanAndCompare(false));
                }
            } catch (Exception ex) {
                client.log(LogLevel.ERROR, "Lỗi đồng bộ tệp đơn lẻ " + fileName + ": " + ex.getMessage());
            }
        }).start();
    }

    /**
     * Chỉ tải lên các tệp được người dùng chọn trong bảng (Menu ngữ cảnh).
     */
    private void performUploadSelected() {
        if (!client.isConnected() || isSyncInProgress) return;
        int[] selectedRows = syncTable.getSelectedRows();
        if (selectedRows.length == 0) return;

        List<SyncItem> selectedItems = new ArrayList<>();
        for (int viewRow : selectedRows) {
            int modelRow = syncTable.convertRowIndexToModel(viewRow);
            String path = (String) syncTableModel.getValueAt(modelRow, 1);
            for (SyncItem item : currentSyncItems) {
                if (item.getRelativePath().equals(path)) {
                    File localFile = new File(localFolder, item.getRelativePath());
                    if (localFile.exists()) {
                        item.setAction(SyncAction.UPLOAD);
                        item.setReason("Tải lên theo yêu cầu người dùng");
                        selectedItems.add(item);
                    }
                    break;
                }
            }
        }

        if (!selectedItems.isEmpty()) {
            executeSpecificItems(selectedItems);
        }
    }

    /**
     * Chỉ tải về các tệp được người dùng chọn trong bảng (Menu ngữ cảnh).
     */
    private void performDownloadSelected() {
        if (!client.isConnected() || isSyncInProgress) return;
        int[] selectedRows = syncTable.getSelectedRows();
        if (selectedRows.length == 0) return;

        List<SyncItem> selectedItems = new ArrayList<>();
        for (int viewRow : selectedRows) {
            int modelRow = syncTable.convertRowIndexToModel(viewRow);
            String path = (String) syncTableModel.getValueAt(modelRow, 1);
            for (SyncItem item : currentSyncItems) {
                if (item.getRelativePath().equals(path)) {
                    if (item.getRemoteInfo() != null) {
                        item.setAction(SyncAction.DOWNLOAD);
                        item.setReason("Tải về theo yêu cầu người dùng");
                        selectedItems.add(item);
                    }
                    break;
                }
            }
        }

        if (!selectedItems.isEmpty()) {
            executeSpecificItems(selectedItems);
        }
    }

    /**
     * Thực thi đồng bộ danh sách mục tệp xác định.
     * Tự động hiển thị Hộp thoại Giải quyết Xung đột (Interactive Conflict Resolution Dialog)
     * nếu phát hiện tệp có trạng thái CONFLICT.
     */
    private void executeSpecificItems(List<SyncItem> items) {
        if (items.isEmpty()) return;

        // Xử lý xung đột tương tác nếu có tệp CONFLICT
        List<SyncItem> conflictItems = new ArrayList<>();
        for (SyncItem item : items) {
            if (item.getAction() == SyncAction.CONFLICT) {
                conflictItems.add(item);
            }
        }

        if (!conflictItems.isEmpty()) {
            boolean applyToAll = false;
            ConflictResolutionDialog.ResolutionChoice globalChoice = ConflictResolutionDialog.ResolutionChoice.KEEP_BOTH;

            for (int i = 0; i < conflictItems.size(); i++) {
                SyncItem ci = conflictItems.get(i);
                ConflictResolutionDialog.ResolutionChoice currentChoice;

                if (applyToAll) {
                    currentChoice = globalChoice;
                } else {
                    ConflictResolutionDialog dlg = new ConflictResolutionDialog(this, ci, conflictItems.size() - i);
                    dlg.setVisible(true);
                    currentChoice = dlg.getChoice();
                    if (dlg.isApplyToAll()) {
                        applyToAll = true;
                        globalChoice = currentChoice;
                    }
                }

                if (currentChoice == ConflictResolutionDialog.ResolutionChoice.KEEP_CLIENT) {
                    ci.setAction(SyncAction.UPLOAD);
                    ci.setReason("Người dùng chọn giữ bản Client (Ghi đè Server)");
                } else if (currentChoice == ConflictResolutionDialog.ResolutionChoice.KEEP_SERVER) {
                    ci.setAction(SyncAction.DOWNLOAD);
                    ci.setReason("Người dùng chọn giữ bản Server (Ghi đè Client)");
                } else if (currentChoice == ConflictResolutionDialog.ResolutionChoice.SKIP) {
                    ci.setAction(SyncAction.SKIP);
                    ci.setReason("Người dùng chọn bỏ qua xung đột");
                } else {
                    ci.setAction(SyncAction.CONFLICT);
                    ci.setReason("Người dùng chọn bảo toàn cả 2 phiên bản (_conflict_...)");
                }
            }
            updateTableWithComparison(currentSyncItems);
        }

        isSyncInProgress = true;
        if (autoSyncWatcher != null) {
            autoSyncWatcher.setPaused(true);
        }
        setButtonsBusyState(true);
        btnCancel.setEnabled(true);

        new Thread(() -> {
            try {
                client.executeSync(items, localFolder);
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this,
                            "Lỗi quá trình đồng bộ: " + ex.getMessage(),
                            "Lỗi", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                isSyncInProgress = false;
                SwingUtilities.invokeLater(() -> {
                    // Trì hoãn 1.2s trước khi unpause WatchService để tránh nhận sự kiện I/O ghi đĩa còn sót lại
                    new javax.swing.Timer(1200, evt -> {
                        if (autoSyncWatcher != null && !isSyncInProgress) {
                            autoSyncWatcher.setPaused(false);
                        }
                    }) {{
                        setRepeats(false);
                        start();
                    }};
                    setButtonsBusyState(false);
                    btnCancel.setEnabled(false);
                });
            }
        }).start();
    }

    private void setButtonsBusyState(boolean busy) {
        btnScan.setEnabled(!busy && client.isConnected());
        btnSyncAll.setEnabled(!busy && client.isConnected() && !currentSyncItems.isEmpty());
        btnBrowseLocal.setEnabled(!busy);
        cmbSyncMode.setEnabled(!busy);

        boolean hasSelection = syncTable.getSelectedRowCount() > 0;
        btnDeleteLocal.setEnabled(!busy && hasSelection);
        btnDeleteServer.setEnabled(!busy && client.isConnected() && hasSelection);
        btnSyncSelected.setEnabled(!busy && client.isConnected() && hasSelection);
    }

    private void updateTableWithComparison(List<SyncItem> items) {
        syncTableModel.setRowCount(0);

        long bytesTotal = 0;
        int toUpload = 0;
        int toDownload = 0;
        int upToDate = 0;
        int conflicts = 0;

        for (SyncItem item : items) {
            String clientSize = item.getLocalInfo() != null ? item.getLocalInfo().getFormattedSize() : "-";
            String serverSize = item.getRemoteInfo() != null ? item.getRemoteInfo().getFormattedSize() : "-";
            String clientTime = item.getLocalInfo() != null ? item.getLocalInfo().getFormattedDate() : "-";
            String serverTime = item.getRemoteInfo() != null ? item.getRemoteInfo().getFormattedDate() : "-";

            syncTableModel.addRow(new Object[]{
                    item.getAction().getTitle(),
                    item.getRelativePath(),
                    clientSize,
                    serverSize,
                    clientTime,
                    serverTime,
                    item.getReason(),
                    item.getTransferStatus()
            });

            if (item.getAction() == SyncAction.UPLOAD) {
                toUpload++;
                bytesTotal += item.getFileSizeToTransfer();
            } else if (item.getAction() == SyncAction.DOWNLOAD) {
                toDownload++;
                bytesTotal += item.getFileSizeToTransfer();
            } else if (item.getAction() == SyncAction.CONFLICT) {
                conflicts++;
                bytesTotal += item.getFileSizeToTransfer();
            } else if (item.getAction() == SyncAction.UP_TO_DATE) {
                upToDate++;
            }
        }

        lblTotalProgressText.setText(String.format("Kết quả quét: Cần tải lên %d, tải về %d, xung đột %d, đã khớp %d (Tổng: %s)",
                toUpload, toDownload, conflicts, upToDate, FileUtils.formatBytes(bytesTotal)));
        progressTotal.setValue(0);
        progressCurrentFile.setValue(0);

        btnSyncAll.setEnabled(toUpload > 0 || toDownload > 0 || conflicts > 0);
    }

    /**
     * Hiển thị danh sách tệp cục bộ khi Client chưa kết nối đến Server.
     */
    private void refreshLocalOnlyView() {
        if (client.isConnected()) return;
        List<FileMetadata> localFiles = client.scanLocalFiles(localFolder);
        syncTableModel.setRowCount(0);
        long bytesTotal = 0;
        for (FileMetadata f : localFiles) {
            bytesTotal += f.getSize();
            syncTableModel.addRow(new Object[]{
                    "Chưa đồng bộ",
                    f.getRelativePath(),
                    f.getFormattedSize(),
                    "-",
                    f.getFormattedDate(),
                    "-",
                    "Tệp cục bộ (Chưa kết nối Server)",
                    "Chờ kết nối"
            });
        }
        lblTotalProgressText.setText(String.format("Thư mục cục bộ có %d tệp (%s) - Hãy bấm [Kết Nối Server] để đồng bộ",
                localFiles.size(), FileUtils.formatBytes(bytesTotal)));
    }

    private void onAutoSyncToggled() {
        if (chkAutoSync.isSelected()) {
            startAutoWatcher();
            if (client.isConnected()) {
                performScanAndCompare(true);
            }
        }
    }

    /**
     * Khởi động Java NIO WatchService để theo dõi thư mục cục bộ thời gian thực.
     */
    private void startAutoWatcher() {
        stopAutoWatcher();
        try {
            autoSyncWatcher = new FileWatcher(localFolder, () -> {
                onLocalFilesystemChanged();
            });
            autoSyncWatcher.start();
            client.log(ClientEventListener.LogLevel.SUCCESS, "Giám sát thư mục thời gian thực (Java NIO WatchService) đã sẵn sàng.");
        } catch (IOException e) {
            client.log(ClientEventListener.LogLevel.ERROR, "Không thể khởi động theo dõi thư mục: " + e.getMessage());
        }
    }

    private void stopAutoWatcher() {
        if (autoSyncWatcher != null) {
            autoSyncWatcher.stop();
            autoSyncWatcher = null;
        }
    }

    /**
     * Hàm được gọi tự động khi WatchService phát hiện có thay đổi tệp trên ổ đĩa.
     */
    private void onLocalFilesystemChanged() {
        SwingUtilities.invokeLater(() -> {
            client.log(ClientEventListener.LogLevel.INFO, "[WatchService] Phát hiện thay đổi tệp trên thư mục cục bộ!");
            if (client.isConnected()) {
                if (chkAutoSync.isSelected() && !isSyncInProgress) {
                    client.log(ClientEventListener.LogLevel.INFO, "Auto-Sync: Đang tự động quét và đồng bộ dữ liệu...");
                    performScanAndCompare(true); // Quét và tự động đồng bộ ngay
                } else {
                    // Nếu không bật Auto-Sync, vẫn tự động quét để hiển thị ngay tệp bị sửa đổi lên bảng
                    performScanAndCompare(false);
                }
            } else {
                // Chưa kết nối Server -> Quét và cập nhật bảng danh sách tệp cục bộ tức thì
                refreshLocalOnlyView();
            }
        });
    }

    // --- ClientEventListener Callbacks ---

    @Override
    public void onConnected(String host, int port) {
        SwingUtilities.invokeLater(() -> {
            lblStatusBadge.setText("ONLINE");
            lblStatusBadge.setBackground(new Color(209, 250, 229));
            lblStatusBadge.setForeground(new Color(6, 95, 70));
            btnConnect.setText("Ngắt Kết Nối");
            btnConnect.setBackground(ModernUI.DANGER);
            btnConnect.setEnabled(true);
            txtHost.setEditable(false);
            txtPort.setEditable(false);

            btnLogin.setEnabled(true);
            btnRegister.setEnabled(true);
            btnScan.setEnabled(true);
            boolean hasSel = syncTable.getSelectedRowCount() > 0;
            btnSyncSelected.setEnabled(hasSel);
            btnDeleteLocal.setEnabled(hasSel);
            btnDeleteServer.setEnabled(hasSel);

            // Tự động nhắc đăng nhập nếu chưa đăng nhập
            if (client.getCurrentUser() == null) {
                showLoginDialog();
            } else {
                performScanAndCompare(false);
            }
        });
    }

    @Override
    public void onDisconnected() {
        SwingUtilities.invokeLater(() -> {
            lblStatusBadge.setText("CHƯA KẾT NỐI");
            lblStatusBadge.setBackground(new Color(254, 226, 226));
            lblStatusBadge.setForeground(new Color(153, 27, 27));

            lblUserBadge.setText("Chưa đăng nhập");
            lblUserBadge.setBackground(new Color(241, 245, 249));
            lblUserBadge.setForeground(new Color(100, 116, 139));
            btnLogin.setText("Đăng Nhập");

            btnConnect.setText("Kết Nối Server");
            btnConnect.setBackground(ModernUI.PRIMARY);
            btnConnect.setEnabled(true);
            txtHost.setEditable(true);
            txtPort.setEditable(true);

            btnLogin.setEnabled(false);
            btnRegister.setEnabled(false);
            btnScan.setEnabled(false);
            btnSyncSelected.setEnabled(false);
            btnSyncAll.setEnabled(false);
            btnDeleteServer.setEnabled(false);
            btnDeleteLocal.setEnabled(syncTable.getSelectedRowCount() > 0);
            btnCancel.setEnabled(false);

            refreshLocalOnlyView();
        });
    }

    private void setupSystemTray() {
        if (!SystemTray.isSupported()) return;

        try {
            SystemTray tray = SystemTray.getSystemTray();
            Image image = createTrayImage();

            PopupMenu popup = new PopupMenu();
            MenuItem openItem = new MenuItem("Mo Bang Dieu Khien");
            openItem.addActionListener(e -> {
                setVisible(true);
                setExtendedState(JFrame.NORMAL);
                toFront();
            });

            MenuItem syncItem = new MenuItem("Dong Bo Ngay");
            syncItem.addActionListener(e -> {
                if (client.isConnected()) {
                    performScanAndCompare(false);
                }
            });

            MenuItem exitItem = new MenuItem("Thoat Ung Dung");
            exitItem.addActionListener(e -> {
                stopAutoWatcher();
                if (client.isConnected()) {
                    client.disconnect();
                }
                System.exit(0);
            });

            popup.add(openItem);
            popup.add(syncItem);
            popup.addSeparator();
            popup.add(exitItem);

            trayIcon = new TrayIcon(image, "Dong Bo Tep Tin Client", popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> {
                setVisible(true);
                setExtendedState(JFrame.NORMAL);
                toFront();
            });

            tray.add(trayIcon);

            // Bắt sự kiện khi người dùng nhấn nút thu nhỏ
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowIconified(WindowEvent e) {
                    setVisible(false);
                    if (trayIcon != null) {
                        trayIcon.displayMessage("Đồng bộ tệp tin",
                                "Ứng dụng đang chạy ngầm trong khay hệ thống.",
                                TrayIcon.MessageType.INFO);
                    }
                }
            });
        } catch (Exception ignored) {}
    }

    private Image createTrayImage() {
        int w = 16, h = 16;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(37, 99, 235));
        g2.fillOval(1, 1, 14, 14);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        g2.drawString("S", 5, 12);
        g2.dispose();
        return img;
    }

    @Override
    public void onServerChangeNotification(String fileName, String action) {
        SwingUtilities.invokeLater(() -> {
            client.log(LogLevel.INFO, "[Real-time Push] Máy chủ thông báo thay đổi tệp: " + fileName + " (" + action + ")");

            if (trayIcon != null) {
                trayIcon.displayMessage("Đồng bộ tệp tin (Push Event)",
                        "Máy chủ vừa cập nhật tệp: " + fileName + " (" + action + ")",
                        TrayIcon.MessageType.INFO);
            }

            if ("DELETE".equalsIgnoreCase(action)) {
                // Tệp đã bị xóa trên Máy chủ:
                // Nếu người dùng BẬT Tự động đồng bộ (Auto-Sync) thì mới xóa bản sao theo Máy chủ
                if (chkAutoSync.isSelected()) {
                    File localTarget = new File(localFolder, fileName);
                    if (localTarget.exists()) {
                        if (autoSyncWatcher != null) autoSyncWatcher.setPaused(true);
                        try {
                            boolean del = localTarget.delete();
                            if (del) {
                                client.log(LogLevel.INFO, "[Push Event] Đã tự động xóa bản sao cục bộ theo Máy chủ: " + fileName);
                            }
                        } finally {
                            new javax.swing.Timer(1200, evt -> {
                                if (autoSyncWatcher != null && !isSyncInProgress) {
                                    autoSyncWatcher.setPaused(false);
                                }
                            }) {{
                                setRepeats(false);
                                start();
                            }};
                        }
                    }
                    lblTotalProgressText.setText("Máy chủ đã xóa tệp: " + fileName);
                } else {
                    // Nếu TẮT Auto-Sync: BẢO VỆ DỮ LIỆU CỤC BỘ - Không tự động can thiệp xóa tệp trên máy này!
                    client.log(LogLevel.INFO, "[Bảo Vệ Dữ Liệu] Tệp '" + fileName + "' vừa bị xóa trên Máy chủ, nhưng tệp trên máy bạn vẫn được GIỮ NGUYÊN AN TOÀN!");
                    lblTotalProgressText.setText("Máy chủ vừa xóa tệp: " + fileName + " (Bản sao trên máy bạn vẫn an toàn)");
                }
                performScanAndCompare(false);
            } else if (chkAutoSync.isSelected() && !isSyncInProgress) {
                client.log(LogLevel.INFO, "Auto-Sync: Đang tự động kéo đúng tệp vừa cập nhật từ máy chủ: " + fileName);
                performSyncSingleFile(fileName);
            } else {
                lblTotalProgressText.setText("Máy chủ có tệp mới: " + fileName + " (" + action + ") - Hãy chọn tệp và bấm [Đồng Bộ Tệp Đã Chọn]!");
                performScanAndCompare(false);
            }
        });
    }

    @Override
    public void onScanCompleted(List<SyncItem> comparisonList, SyncStats stats) {
        SwingUtilities.invokeLater(() -> updateTableWithComparison(comparisonList));
    }

    @Override
    public void onSyncStarted(int totalItems, long totalBytes) {
        SwingUtilities.invokeLater(() -> {
            progressTotal.setValue(0);
            progressCurrentFile.setValue(0);
            lblSpeed.setText("0.0 KB/s");
        });
    }

    @Override
    public void onItemProgress(SyncItem item, int percent, long bytesTransferred, long fileSize) {
        SwingUtilities.invokeLater(() -> {
            progressCurrentFile.setValue(percent);
            lblCurrentFileText.setText(String.format("Đang truyền: %s (%s / %s - %d%%)",
                    item.getRelativePath(), FileUtils.formatBytes(bytesTransferred), FileUtils.formatBytes(fileSize), percent));

            // Cập nhật dòng trong bảng
            for (int r = 0; r < syncTableModel.getRowCount(); r++) {
                if (item.getRelativePath().equals(syncTableModel.getValueAt(r, 1))) {
                    syncTableModel.setValueAt("Đang truyền " + percent + "%", r, 7);
                    break;
                }
            }
        });
    }

    @Override
    public void onItemCompleted(SyncItem item, boolean success, String message) {
        SwingUtilities.invokeLater(() -> {
            for (int r = 0; r < syncTableModel.getRowCount(); r++) {
                if (item.getRelativePath().equals(syncTableModel.getValueAt(r, 1))) {
                    syncTableModel.setValueAt(success ? "Thành công" : "Lỗi: " + message, r, 7);
                    break;
                }
            }
        });
    }

    @Override
    public void onSyncFinished(SyncStats stats) {
        SwingUtilities.invokeLater(() -> {
            progressTotal.setValue(100);
            progressCurrentFile.setValue(100);
            lblTotalProgressText.setText("Hoàn tất xử lý! Tốc độ: " + stats.getFormattedSpeed());
            lblCurrentFileText.setText("Tất cả tệp đã được xử lý xong.");
            lblSpeed.setText(stats.getFormattedSpeed());
            // Quét lại để cập nhật bảng sang trạng thái 'Đã đồng bộ'
            performScanAndCompare(false);
        });
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
}
