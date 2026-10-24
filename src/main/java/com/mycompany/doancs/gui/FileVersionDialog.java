package com.mycompany.doancs.gui;

import com.mycompany.doancs.gui.components.ModernUI;
import com.mycompany.doancs.model.FileVersionInfo;
import com.mycompany.doancs.util.FileUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Hộp thoại Xem Lịch Sử Phiên Bản & Khôi Phục (File Versioning & Rollback Dialog).
 */
public class FileVersionDialog extends JDialog {
    private final JTable table;
    private final DefaultTableModel tableModel;
    private final JButton btnRollback;
    private final List<FileVersionInfo> versions;

    public FileVersionDialog(Frame parent, String fileName, List<FileVersionInfo> versions, Consumer<FileVersionInfo> rollbackCallback) {
        super(parent, "Lịch Sử Phiên Bản: " + fileName, true);
        this.versions = versions;

        setSize(680, 420);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout());

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(15, 18, 15, 18));

        // Top Header
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        JLabel lblTitle = new JLabel("Lịch sử phiên bản tệp: " + fileName);
        lblTitle.setFont(ModernUI.FONT_TITLE);
        lblTitle.setForeground(ModernUI.PRIMARY);

        JLabel lblSub = new JLabel("Tìm thấy " + versions.size() + " phiên bản sao lưu cũ trên máy chủ. Bạn có thể chọn và khôi phục (Rollback).");
        lblSub.setFont(ModernUI.FONT_REGULAR);
        lblSub.setForeground(ModernUI.TEXT_MUTED);

        topPanel.add(lblTitle, BorderLayout.NORTH);
        topPanel.add(lblSub, BorderLayout.CENTER);
        root.add(topPanel, BorderLayout.NORTH);

        // Center Table
        String[] columns = {"Phiên Bản", "Dung Lượng", "Mã Băm SHA-256", "Thời Gian Cập Nhật"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(tableModel);
        table.setRowHeight(28);
        table.setFont(ModernUI.FONT_REGULAR);
        table.getTableHeader().setFont(ModernUI.FONT_BOLD);
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(3).setPreferredWidth(160);

        for (FileVersionInfo v : versions) {
            tableModel.addRow(new Object[]{
                    "v" + v.getVersion(),
                    FileUtils.formatBytes(v.getFileSize()),
                    v.getSha256(),
                    v.getCreatedAt()
            });
        }

        JScrollPane scrollPane = new JScrollPane(table);
        root.add(scrollPane, BorderLayout.CENTER);

        // Bottom Actions
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        JButton btnClose = ModernUI.createButton("Đóng", new Color(241, 245, 249), Color.DARK_GRAY);
        btnClose.addActionListener(e -> dispose());

        btnRollback = ModernUI.createButton("Khôi Phục Phiên Bản Đã Chọn (Rollback)", ModernUI.PRIMARY, Color.WHITE);
        btnRollback.setEnabled(false);

        table.getSelectionModel().addListSelectionListener(e -> {
            btnRollback.setEnabled(table.getSelectedRow() >= 0);
        });

        btnRollback.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0 && selectedRow < versions.size()) {
                FileVersionInfo selectedVer = versions.get(selectedRow);
                int confirm = JOptionPane.showConfirmDialog(this,
                        "Bạn có chắc chắn muốn khôi phục tệp '" + fileName + "' về phiên bản v" + selectedVer.getVersion() + "?\n" +
                        "(Tệp hiện tại trên máy chủ sẽ được cập nhật và thông báo đẩy tới các Client)",
                        "Xác Nhận Khôi Phục (Rollback)",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
                if (confirm == JOptionPane.YES_OPTION) {
                    rollbackCallback.accept(selectedVer);
                    dispose();
                }
            }
        });

        bottomPanel.add(btnClose);
        bottomPanel.add(btnRollback);
        root.add(bottomPanel, BorderLayout.SOUTH);

        add(root);
    }
}
