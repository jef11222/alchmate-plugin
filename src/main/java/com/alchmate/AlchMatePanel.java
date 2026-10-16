package com.alchmate;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public class AlchMatePanel extends PluginPanel {
    private static final Color GOLD = new Color(243, 156, 18);
    private static final Color PROFIT_GREEN = new Color(46, 204, 113);
    private static final Color HUB_BLUE = new Color(52, 152, 219);
    private static final Color CARD_BG = new Color(26, 30, 38);
    private static final Color CARD_BORDER = new Color(42, 52, 73);

    private final JLabel rsnLabel = new JLabel("● Not Logged In");
    private final JLabel statusLabel = new JLabel("● Hub: 127.0.0.1:18833");
    private final AlchMatePlugin plugin;

    public AlchMatePanel(AlchMatePlugin plugin) {
        super(false);
        this.plugin = plugin;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel mainContainer = new JPanel();
        mainContainer.setLayout(new BoxLayout(mainContainer, BoxLayout.Y_AXIS));
        mainContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        mainContainer.setBorder(new EmptyBorder(12, 8, 12, 8));

        // 1. Header Banner
        JLabel titleLabel = new JLabel("AlchMate");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 17));
        titleLabel.setForeground(GOLD);
        titleLabel.setAlignmentX(CENTER_ALIGNMENT);

        rsnLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        rsnLabel.setForeground(Color.GRAY);
        rsnLabel.setAlignmentX(CENTER_ALIGNMENT);

        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        statusLabel.setForeground(PROFIT_GREEN);
        statusLabel.setAlignmentX(CENTER_ALIGNMENT);

        JLabel subLabel = new JLabel("100% Pure Local Standalone");
        subLabel.setFont(new Font("Segoe UI", Font.ITALIC, 10));
        subLabel.setForeground(new Color(150, 150, 150));
        subLabel.setAlignmentX(CENTER_ALIGNMENT);

        mainContainer.add(titleLabel);
        mainContainer.add(Box.createVerticalStrut(4));
        mainContainer.add(rsnLabel);
        mainContainer.add(Box.createVerticalStrut(2));
        mainContainer.add(statusLabel);
        mainContainer.add(Box.createVerticalStrut(2));
        mainContainer.add(subLabel);
        mainContainer.add(Box.createVerticalStrut(14));

        // 2. Primary Action Button: Open Local Web Dashboard
        JButton openWebBtn = new JButton("🌐 Open Dashboard");
        openWebBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        openWebBtn.setBackground(new Color(245, 158, 11));
        openWebBtn.setForeground(Color.BLACK);
        openWebBtn.setFocusPainted(false);
        openWebBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        openWebBtn.setAlignmentX(CENTER_ALIGNMENT);
        openWebBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        openWebBtn.setPreferredSize(new Dimension(185, 40));
        openWebBtn.setToolTipText("Opens the full AlchMate suite locally in your default browser (127.0.0.1:18833)");
        openWebBtn.addActionListener(e -> openBrowser(plugin.getLocalDashboardUrl()));

        mainContainer.add(openWebBtn);
        mainContainer.add(Box.createVerticalStrut(8));

        // 3. Secondary Action Button: Toggle In-Game Overlay
        JButton toggleOverlayBtn = new JButton("👁️ Toggle Overlay");
        toggleOverlayBtn.setFont(new Font("Segoe UI", Font.BOLD, 11));
        toggleOverlayBtn.setBackground(new Color(35, 42, 54));
        toggleOverlayBtn.setForeground(Color.WHITE);
        toggleOverlayBtn.setFocusPainted(false);
        toggleOverlayBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        toggleOverlayBtn.setAlignmentX(CENTER_ALIGNMENT);
        toggleOverlayBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        toggleOverlayBtn.setPreferredSize(new Dimension(185, 36));
        toggleOverlayBtn.setToolTipText("Show or hide the real-time Alchs/hr and Profit HUD overlay above inventory");
        toggleOverlayBtn.addActionListener(e -> plugin.toggleOverlay());

        mainContainer.add(toggleOverlayBtn);
        mainContainer.add(Box.createVerticalStrut(12));

        // 4. Beta Build Notice & GitHub Issue Reporter Card
        JPanel betaCard = new JPanel();
        betaCard.setLayout(new BoxLayout(betaCard, BoxLayout.Y_AXIS));
        betaCard.setBackground(new Color(36, 28, 14));
        betaCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(245, 158, 11, 140), 1),
            new EmptyBorder(8, 8, 8, 8)
        ));
        betaCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        betaCard.setAlignmentX(CENTER_ALIGNMENT);

        JLabel betaTitle = new JLabel("⚠️ Public Beta Build");
        betaTitle.setFont(new Font("Segoe UI", Font.BOLD, 11));
        betaTitle.setForeground(new Color(251, 191, 36));

        JLabel betaDesc = new JLabel("<html><body style='width: 170px; color: #fef3c7; font-size: 9px; line-height: 1.35;'>"
            + "This is a first-release <b>Beta</b> running the new 100% local architecture. You may encounter edge-case rate bugs or display quirks.<br><br>"
            + "Found a bug or calculation error? Please report it on GitHub to help us polish the release!"
            + "</body></html>");

        JButton bugReportBtn = new JButton("🐛 Report Bug / Feedback");
        bugReportBtn.setFont(new Font("Segoe UI", Font.BOLD, 10));
        bugReportBtn.setBackground(new Color(245, 158, 11));
        bugReportBtn.setForeground(Color.BLACK);
        bugReportBtn.setFocusPainted(false);
        bugReportBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        bugReportBtn.setAlignmentX(LEFT_ALIGNMENT);
        bugReportBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        bugReportBtn.setPreferredSize(new Dimension(170, 28));
        bugReportBtn.setToolTipText("Open GitHub Issues tracker to report bugs, feedback, or suggestions");
        bugReportBtn.addActionListener(e -> openBrowser("https://github.com/jef11222/alchmate-plugin/issues"));

        betaCard.add(betaTitle);
        betaCard.add(Box.createVerticalStrut(4));
        betaCard.add(betaDesc);
        betaCard.add(Box.createVerticalStrut(8));
        betaCard.add(bugReportBtn);

        mainContainer.add(betaCard);
        mainContainer.add(Box.createVerticalStrut(14));

        // 5. Clean System & Architecture Info Card
        JPanel infoCard = new JPanel();
        infoCard.setLayout(new BoxLayout(infoCard, BoxLayout.Y_AXIS));
        infoCard.setBackground(CARD_BG);
        infoCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(CARD_BORDER, 1),
            new EmptyBorder(10, 8, 10, 8)
        ));
        infoCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 300));
        infoCard.setAlignmentX(CENTER_ALIGNMENT);

        JLabel infoTitle = new JLabel("⚡ Multi-Account Hub");
        infoTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        infoTitle.setForeground(GOLD);

        JLabel infoLine1 = new JLabel("<html><body style='width: 170px; color: #b0b8c4; font-size: 10px; line-height: 1.4;'>"
            + "<b>100% Local PC Architecture:</b><br>"
            + "The web dashboard is hosted locally inside this plugin on your PC.<br><br>"
            + "<b>Multi-Client Sync:</b><br>"
            + "All RuneLite accounts on this computer aggregate seamlessly into the local dashboard.<br><br>"
            + "<b>Dashboard Features:</b><br>"
            + "• Live High Alch & GE Tables<br>"
            + "• 229 Workshop liquidations<br>"
            + "• W308 Bulk Trade Calculator<br>"
            + "• 4-Hour GE Limit Timers<br>"
            + "• Bond Roadmap & Session Ledger"
            + "</body></html>");

        infoCard.add(infoTitle);
        infoCard.add(Box.createVerticalStrut(6));
        infoCard.add(infoLine1);

        mainContainer.add(infoCard);
        mainContainer.add(Box.createVerticalGlue());

        add(mainContainer, BorderLayout.CENTER);
    }

    public void updateStatus(String rsn, String serverUrl, boolean isServerRunning) {
        SwingUtilities.invokeLater(() -> {
            if (rsn != null && !rsn.isEmpty() && !"Unknown".equalsIgnoreCase(rsn)) {
                rsnLabel.setText("● Active: " + rsn);
                rsnLabel.setForeground(PROFIT_GREEN);
            } else {
                rsnLabel.setText("● Not Logged In");
                rsnLabel.setForeground(Color.GRAY);
            }

            String displayUrl = (serverUrl != null && !serverUrl.isEmpty())
                ? serverUrl.replace("http://", "")
                : "127.0.0.1:18833";

            if (isServerRunning) {
                statusLabel.setText("● Hub: " + displayUrl);
                statusLabel.setForeground(PROFIT_GREEN);
            } else {
                statusLabel.setText("● Spoke -> Hub: " + displayUrl);
                statusLabel.setForeground(HUB_BLUE);
            }
        });
    }

    private void openBrowser(String url) {
        if (url == null || url.isEmpty()) return;
        net.runelite.client.util.LinkBrowser.browse(url);
    }

    // Legacy no-op stubs to maintain interface compatibility with AlchMatePlugin
    public void updateTop10(List<Map<String, Object>> items, boolean connected) {}
    public void updateInventoryBatch(List<AlchMatePlugin.BatchItemEntry> entries, int totalQty, long totalProfit, long totalGross, long estSecs, int natRunes, String fireSource, int cachedAlchsPerHour) {}
    public void updateBondFreedom(long wealth, long bondPrice, long cushion, boolean stage2, long profitHr) {}
    public void updateSkillingProgression(String skillName, String activity, int curLvl, int nextLvl, int remXp, double xpHr, long estSecs) {}
}
