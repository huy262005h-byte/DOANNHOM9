package com.mycompany.doancs.gui.components;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

public class ModernUI {
    // Bảng màu hiện đại
    public static final Color PRIMARY = new Color(37, 99, 235);       // Blue
    public static final Color PRIMARY_HOVER = new Color(29, 78, 216);
    public static final Color SUCCESS = new Color(16, 185, 129);      // Emerald green
    public static final Color WARNING = new Color(245, 158, 11);      // Amber
    public static final Color DANGER = new Color(239, 68, 68);        // Red
    public static final Color TEXT_MUTED = new Color(100, 116, 139);  // Slate
    public static final Color CARD_BG = new Color(255, 255, 255);
    public static final Color BORDER_COLOR = new Color(226, 232, 240);

    public static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 18);
    public static final Font FONT_SUBTITLE = new Font("Segoe UI", Font.BOLD, 14);
    public static final Font FONT_BOLD = new Font("Segoe UI", Font.BOLD, 12);
    public static final Font FONT_REGULAR = new Font("Segoe UI", Font.PLAIN, 12);
    public static final Font FONT_MONO = new Font("Consolas", Font.PLAIN, 12);

    /**
     * Tạo một Card Panel bo góc với viền mảnh hiện đại.
     */
    public static JPanel createCardPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 226, 235), 1, true),
                new EmptyBorder(12, 14, 12, 14)
        ));
        return panel;
    }

    /**
     * Tạo nút bấm phong cách hiện đại với bo góc và màu nhấn.
     */
    public static JButton createButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BOLD);
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.putClientProperty("JButton.buttonType", "roundRect");
        return btn;
    }

    /**
     * Tạo Badge hiển thị trạng thái dạng bo tròn (Pill Badge).
     */
    public static JLabel createBadge(String text, Color bg, Color fg) {
        JLabel label = new JLabel(text, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        label.setOpaque(false);
        label.setBackground(bg);
        label.setForeground(fg);
        label.setFont(new Font("Segoe UI", Font.BOLD, 11));
        label.setBorder(new EmptyBorder(3, 8, 3, 8));
        return label;
    }

    /**
     * Tạo một thẻ hiển thị thông số thống kê mini (Stat Metric Card).
     */
    public static JPanel createStatCard(String title, String initialValue, Color accentColor) {
        JPanel card = new JPanel(new BorderLayout(5, 5));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, accentColor),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(226, 232, 240), 1, true),
                        new EmptyBorder(8, 12, 8, 12)
                )
        ));

        JLabel lblTitle = new JLabel(title);
        lblTitle.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblTitle.setForeground(TEXT_MUTED);

        JLabel lblValue = new JLabel(initialValue);
        lblValue.setFont(new Font("Segoe UI", Font.BOLD, 18));
        lblValue.setForeground(accentColor.darker());
        lblValue.setName("stat_value");

        card.add(lblTitle, BorderLayout.NORTH);
        card.add(lblValue, BorderLayout.CENTER);
        return card;
    }

    /**
     * Cập nhật giá trị hiển thị của một thẻ thống kê.
     */
    public static void updateStatCardValue(JPanel card, String newValue) {
        for (Component c : card.getComponents()) {
            if ("stat_value".equals(c.getName()) && c instanceof JLabel) {
                ((JLabel) c).setText(newValue);
                break;
            }
        }
    }

    /**
     * Renderer cho cột trạng thái trong bảng để hiển thị nhãn đẹp mắt.
     */
    public static class BadgeCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            String text = value != null ? value.toString() : "";
            Color bg = new Color(241, 245, 249);
            Color fg = new Color(71, 85, 105);

            if (text.contains("Đã đồng bộ") || text.contains("Thành công") || text.contains("ONLINE")) {
                bg = new Color(209, 250, 229);
                fg = new Color(6, 95, 70);
            } else if (text.contains("Tải lên") || text.contains("Client")) {
                bg = new Color(219, 234, 254);
                fg = new Color(30, 64, 175);
            } else if (text.contains("Tải về") || text.contains("Server")) {
                bg = new Color(254, 243, 199);
                fg = new Color(146, 64, 14);
            } else if (text.contains("Xung đột") || text.contains("Lỗi") || text.contains("OFFLINE")) {
                bg = new Color(254, 226, 226);
                fg = new Color(153, 27, 27);
            } else if (text.contains("Đang truyền")) {
                bg = new Color(237, 233, 254);
                fg = new Color(107, 33, 168);
            }

            JLabel badge = createBadge(text, bg, fg);
            badge.setHorizontalAlignment(SwingConstants.CENTER);

            JPanel container = new JPanel(new GridBagLayout());
            container.setOpaque(isSelected);
            if (isSelected) {
                container.setBackground(table.getSelectionBackground());
            } else {
                container.setBackground(row % 2 == 0 ? table.getBackground() : new Color(248, 250, 252));
            }
            container.add(badge);
            return container;
        }
    }
}
