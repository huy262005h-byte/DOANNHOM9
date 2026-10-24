package com.mycompany.doancs.gui;

import com.mycompany.doancs.gui.components.ModernUI;
import com.mycompany.doancs.model.SyncAction;
import com.mycompany.doancs.model.SyncItem;
import com.mycompany.doancs.util.FileUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Hộp thoại Giải quyết Xung đột Dữ liệu Tương tác (Interactive Conflict Resolution Dialog).
 * Cho phép người dùng trực tiếp lựa chọn phương án xử lý khi phát hiện xung đột:
 * 1. Giữ bản của tôi (Client ghi đè Server)
 * 2. Giữ bản trên Server (Server ghi đè Client)
 * 3. Bảo toàn cả hai bản (Tự động đổi tên _conflict_...)
 */
public class ConflictResolutionDialog extends JDialog {
    public enum ResolutionChoice {
        KEEP_CLIENT,
        KEEP_SERVER,
        KEEP_BOTH,
        SKIP
    }

    private ResolutionChoice choice = ResolutionChoice.KEEP_BOTH;
    private boolean applyToAll = false;

    private JRadioButton rdoClient;
    private JRadioButton rdoServer;
    private JRadioButton rdoBoth;
    private JCheckBox chkApplyAll;

    public ConflictResolutionDialog(Frame parent, SyncItem item, int remainingConflicts) {
        super(parent, "Phát Hiện Xung Đột Tệp Tin", true);
        initUI(item, remainingConflicts);
    }

    private void initUI(SyncItem item, int remainingConflicts) {
        setSize(560, 420);
        setLocationRelativeTo(getParent());
        setResizable(false);
        setLayout(new BorderLayout());

        JPanel root = new JPanel(new BorderLayout(15, 15));
        root.setBorder(new EmptyBorder(18, 20, 18, 20));

        // Header Panel
        JPanel headerPanel = new JPanel(new BorderLayout(8, 8));
        JLabel lblTitle = new JLabel("Phát hiện xung đột dữ liệu!");
        lblTitle.setFont(ModernUI.FONT_TITLE);
        lblTitle.setForeground(ModernUI.WARNING.darker());

        JLabel lblDesc = new JLabel("<html>Tệp <b>" + item.getRelativePath() +
                "</b> đã bị chỉnh sửa khác nhau ở cả Máy khách và Máy chủ.<br/>" +
                "Vui lòng chọn phiên bản bạn muốn ưu tiên lưu giữ:</html>");
        lblDesc.setFont(ModernUI.FONT_REGULAR);
        headerPanel.add(lblTitle, BorderLayout.NORTH);
        headerPanel.add(lblDesc, BorderLayout.CENTER);
        root.add(headerPanel, BorderLayout.NORTH);

        // Center Content: Info Comparison Cards & Radio Options
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        // Comparison Table / Panel
        JPanel compPanel = new JPanel(new GridLayout(1, 2, 12, 0));
        compPanel.setBorder(new EmptyBorder(10, 0, 15, 0));

        // Local Box
        JPanel localBox = ModernUI.createCardPanel();
        localBox.setLayout(new BoxLayout(localBox, BoxLayout.Y_AXIS));
        JLabel lblLocalHeader = new JLabel("[Client] Phiên bản của bạn");
        lblLocalHeader.setFont(ModernUI.FONT_BOLD);
        lblLocalHeader.setForeground(ModernUI.PRIMARY);
        long lSize = item.getLocalInfo() != null ? item.getLocalInfo().getSize() : 0;
        long lTime = item.getLocalInfo() != null ? item.getLocalInfo().getLastModified() : 0;
        JLabel lblLocalSize = new JLabel("- Dung lượng: " + FileUtils.formatBytes(lSize));
        JLabel lblLocalTime = new JLabel("- Sửa đổi: " + (lTime > 0 ? sdf.format(new Date(lTime)) : "Không rõ"));
        localBox.add(lblLocalHeader);
        localBox.add(Box.createVerticalStrut(5));
        localBox.add(lblLocalSize);
        localBox.add(lblLocalTime);

        // Remote Box
        JPanel remoteBox = ModernUI.createCardPanel();
        remoteBox.setLayout(new BoxLayout(remoteBox, BoxLayout.Y_AXIS));
        JLabel lblRemoteHeader = new JLabel("[Server] Phiên bản máy chủ");
        lblRemoteHeader.setFont(ModernUI.FONT_BOLD);
        lblRemoteHeader.setForeground(ModernUI.SUCCESS.darker());
        long rSize = item.getRemoteInfo() != null ? item.getRemoteInfo().getSize() : 0;
        long rTime = item.getRemoteInfo() != null ? item.getRemoteInfo().getLastModified() : 0;
        JLabel lblRemoteSize = new JLabel("- Dung lượng: " + FileUtils.formatBytes(rSize));
        JLabel lblRemoteTime = new JLabel("- Sửa đổi: " + (rTime > 0 ? sdf.format(new Date(rTime)) : "Không rõ"));
        remoteBox.add(lblRemoteHeader);
        remoteBox.add(Box.createVerticalStrut(5));
        remoteBox.add(lblRemoteSize);
        remoteBox.add(lblRemoteTime);

        compPanel.add(localBox);
        compPanel.add(remoteBox);
        centerPanel.add(compPanel);

        // Options Radio
        JPanel optionsPanel = new JPanel();
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
        optionsPanel.setBorder(BorderFactory.createTitledBorder("Phương Án Xử Lý"));

        rdoBoth = new JRadioButton("<html><b>Bảo toàn cả hai bản</b> (Lưu bản Client thành <i>filename_conflict_...</i> và lấy bản Server) - <i>Khuyên dùng</i></html>", true);
        rdoClient = new JRadioButton("<html><b>Giữ bản của tôi</b> (Tải tệp Client lên ghi đè máy chủ Server)</html>");
        rdoServer = new JRadioButton("<html><b>Giữ bản của Server</b> (Tải tệp Server về ghi đè máy cục bộ Client)</html>");

        ButtonGroup group = new ButtonGroup();
        group.add(rdoBoth);
        group.add(rdoClient);
        group.add(rdoServer);

        optionsPanel.add(rdoBoth);
        optionsPanel.add(Box.createVerticalStrut(6));
        optionsPanel.add(rdoClient);
        optionsPanel.add(Box.createVerticalStrut(6));
        optionsPanel.add(rdoServer);

        centerPanel.add(optionsPanel);

        // Checkbox Apply to all
        chkApplyAll = new JCheckBox("Áp dụng lựa chọn này cho tất cả " + remainingConflicts + " tệp xung đột còn lại");
        chkApplyAll.setFont(ModernUI.FONT_REGULAR);
        if (remainingConflicts <= 1) {
            chkApplyAll.setVisible(false);
        }
        centerPanel.add(Box.createVerticalStrut(8));
        centerPanel.add(chkApplyAll);

        root.add(centerPanel, BorderLayout.CENTER);

        // Bottom Button Panel
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        JButton btnSkip = ModernUI.createButton("Bỏ qua", new Color(241, 245, 249), Color.DARK_GRAY);
        btnSkip.addActionListener(e -> {
            choice = ResolutionChoice.SKIP;
            dispose();
        });

        JButton btnConfirm = ModernUI.createButton("Áp Dụng", ModernUI.PRIMARY, Color.WHITE);
        btnConfirm.addActionListener(e -> {
            if (rdoClient.isSelected()) {
                choice = ResolutionChoice.KEEP_CLIENT;
            } else if (rdoServer.isSelected()) {
                choice = ResolutionChoice.KEEP_SERVER;
            } else {
                choice = ResolutionChoice.KEEP_BOTH;
            }
            applyToAll = chkApplyAll.isSelected();
            dispose();
        });

        bottomPanel.add(btnSkip);
        bottomPanel.add(btnConfirm);
        root.add(bottomPanel, BorderLayout.SOUTH);

        add(root);
    }

    public ResolutionChoice getChoice() {
        return choice;
    }

    public boolean isApplyToAll() {
        return applyToAll;
    }
}
