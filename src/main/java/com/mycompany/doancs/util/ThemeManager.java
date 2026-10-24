package com.mycompany.doancs.util;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;

public class ThemeManager {
    private static boolean isDarkMode = false; // Default to clean modern Light or Dark theme

    public static void setupTheme(boolean dark) {
        isDarkMode = dark;
        try {
            // Thiết lập thuộc tính giao diện FlatLaf hiện đại
            UIManager.put("Button.arc", 12);
            UIManager.put("Component.arc", 10);
            UIManager.put("ProgressBar.arc", 10);
            UIManager.put("TextComponent.arc", 10);
            UIManager.put("ScrollBar.thumbArc", 8);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("TabbedPane.showTabSeparators", true);
            UIManager.put("Table.rowHeight", 28);
            UIManager.put("Table.alternateRowColor", dark ? new Color(38, 42, 50) : new Color(248, 250, 252));

            // Font chữ giao diện sắc nét
            Font defaultFont = new Font("Segoe UI", Font.PLAIN, 13);
            UIManager.put("defaultFont", defaultFont);

            if (dark) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }

            // Cập nhật toàn bộ cửa sổ đang mở
            for (Window window : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(window);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isDarkMode() {
        return isDarkMode;
    }

    public static void toggleTheme() {
        setupTheme(!isDarkMode);
    }
}
