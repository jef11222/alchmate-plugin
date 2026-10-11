package net.runelite.client.plugins.alchbridge;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class AlchBridgeOverlay extends OverlayPanel {
    private final AlchBridgePlugin plugin;
    private final AlchBridgeConfig config;

    @Inject
    public AlchBridgeOverlay(AlchBridgePlugin plugin, AlchBridgeConfig config) {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.BOTTOM_RIGHT);
        setPriority(OverlayPriority.MED);
        getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, "Toggle Bond Tracker", "Alch Session"));
        getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, "Toggle Cushion Tracker", "Alch Session"));
        getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, "Toggle Cost Basis", "Alch Session"));
        getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, "Reset", "Alch Session"));
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showOverlay()) {
            return null;
        }

        panelComponent.setPreferredSize(new Dimension(185, 0));
        panelComponent.getChildren().clear();

        // Header Title
        panelComponent.getChildren().add(TitleComponent.builder()
            .text("AlchMate")
            .color(new Color(243, 156, 18))
            .build());

        int alchs = plugin.getSessionAlchs();
        boolean isActive = plugin.isSessionActive();
        String statusText = alchs == 0 ? "Idle" : (isActive ? "Alching \uD83D\uDFE2" : "Paused \u23F8\uFE0F");
        Color statusColor = alchs == 0 ? Color.GRAY : (isActive ? new Color(76, 175, 80) : new Color(255, 193, 7));

        // Alchs Count & Status
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Alchs:")
            .right(String.format("%,d", alchs))
            .rightColor(Color.WHITE)
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Status:")
            .right(statusText)
            .rightColor(statusColor)
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Time:")
            .right(plugin.getFormattedSessionTime())
            .rightColor(Color.WHITE)
            .build());

        // Alchs / Hour
        int alchsPerHour = plugin.getAlchsPerHour();
        String alchsHrStr = alchs < 3 ? "Warming up..." : String.format("%,d / hr", alchsPerHour);
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Alchs/hr:")
            .right(alchsHrStr)
            .rightColor(new Color(0, 220, 255))
            .build());

        // Remaining Batch ETA (if items detected in inventory)
        if (plugin.getBatchItemQty() > 0) {
            long remSec = plugin.getBatchEstSeconds();
            String timeStr = formatDuration(remSec);
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Batch ETA:")
                .right(String.format("%s (%,d)", timeStr, plugin.getBatchItemQty()))
                .rightColor(new Color(255, 183, 77)) // Soft Orange
                .build());
        }

        // Live Skilling Progression (Auto-detected activity, XP/hr, and Next Lvl ETA)
        if (plugin.isSkillingActive()) {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Training:")
                .right(plugin.getDetectedActivityName())
                .rightColor(new Color(0, 229, 255))
                .build());

            if (plugin.getLiveSkillingXpHr() > 1000) {
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Skill XP/hr:")
                    .right(String.format("%,d / hr", (int) Math.round(plugin.getLiveSkillingXpHr())))
                    .rightColor(new Color(255, 235, 59))
                    .build());
            }

            if (plugin.getSkillingRemSecsNext() > 0) {
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Next Lvl ETA:")
                    .right(formatDuration(plugin.getSkillingRemSecsNext()))
                    .rightColor(new Color(105, 240, 174))
                    .build());
            }
        }

        // Cost Basis Mode (W308 Trade vs Live GE)
        CostBasisMode mode = plugin.getCostBasisMode();
        String basisText;
        Color basisColor;
        if (mode == CostBasisMode.AUTO_DETECT) {
            int activeId = plugin.getLastAlchedItemId();
            String detected = (activeId > 0) ? plugin.resolveItemSource(activeId) : "Auto";
            basisText = "Auto [" + detected + "]";
            basisColor = new Color(129, 199, 132);
        } else if (mode == CostBasisMode.W308_TRADE) {
            int price = plugin.getActiveW308Price();
            if (price > 0) {
                basisText = String.format("P2P (%,d gp)", price);
            } else {
                basisText = "P2P / W308";
            }
            basisColor = new Color(255, 179, 0);
        } else {
            basisText = "Live GE";
            basisColor = new Color(79, 195, 247);
        }

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Basis:")
            .right(basisText)
            .rightColor(basisColor)
            .build());

        // Profit / Hour
        if (config.showProfit()) {
            long profitPerHour = plugin.getProfitPerHour();
            Color profitColor = profitPerHour >= 0 ? new Color(76, 175, 80) : new Color(244, 67, 54);
            String profitHrStr = alchs < 3 ? "--" : ((profitPerHour >= 0 ? "+" : "") + formatGp(profitPerHour) + " / hr");
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Profit/hr:")
                .right(profitHrStr)
                .rightColor(profitColor)
                .build());

            long totalProfit = plugin.getSessionProfit();
            Color totalProfColor = totalProfit >= 0 ? new Color(76, 175, 80) : new Color(244, 67, 54);
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Net Profit:")
                .right((totalProfit >= 0 ? "+" : "") + formatGp(totalProfit))
                .rightColor(totalProfColor)
                .build());
        }

        // Bond & Freedom Cushion Progress (Dynamic Adaptive 2-Line HUD)
        if (plugin.isBondTrackerEnabled()) {
            long totalWealth = plugin.getTotalWealth();
            long bondPrice = plugin.getBondPrice();
            long cushion = plugin.getCushionAmount();
            boolean stage2 = plugin.isStage2Enabled();
            long totalGoal = bondPrice + (stage2 ? cushion : 0);
            long profitPerHour = plugin.getProfitPerHour();

            if (totalWealth < bondPrice) {
                // Phase A: Stage 1 Accumulating
                double bondPct = bondPrice > 0 ? ((double) totalWealth / (double) bondPrice * 100.0) : 0.0;
                String bondText = String.format("%.1fM / %.1fM (%.1f%%)", totalWealth / 1_000_000.0, bondPrice / 1_000_000.0, bondPct);
                Color bondColor = bondPct >= 50.0 ? new Color(255, 193, 7) : new Color(255, 152, 0); // Gold or Orange

                panelComponent.getChildren().add(LineComponent.builder()
                    .left(stage2 ? "Bond (S1):" : "Bond:")
                    .right(bondText)
                    .rightColor(bondColor)
                    .build());

                if (profitPerHour > 0) {
                    long remGp = bondPrice - totalWealth;
                    double hrs = (double) remGp / (double) profitPerHour;
                    String etaText = hrs >= 24.0 ? String.format("~%.1f days", hrs / 24.0) : String.format("~%.1f hrs", hrs);
                    panelComponent.getChildren().add(LineComponent.builder()
                        .left("Bond ETA:")
                        .right(etaText)
                        .rightColor(new Color(186, 104, 200)) // Pastel Purple
                        .build());
                } else if (stage2 && cushion > 0) {
                    panelComponent.getChildren().add(LineComponent.builder()
                        .left("S2 Cushion:")
                        .right(String.format("%.1fM target", cushion / 1_000_000.0))
                        .rightColor(new Color(170, 170, 170))
                        .build());
                }
            } else if (stage2 && totalWealth < totalGoal) {
                // Phase B: Stage 2 Cushion Accumulating (Bond ticket secured!)
                long cushionSaved = totalWealth - bondPrice;
                double cPct = cushion > 0 ? ((double) cushionSaved / (double) cushion * 100.0) : 0.0;
                String cText = String.format("%.1fM / %.1fM (%.1f%%)", cushionSaved / 1_000_000.0, cushion / 1_000_000.0, cPct);

                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Cushion (S2):")
                    .right(cText)
                    .rightColor(new Color(243, 156, 18)) // Amber / Orange
                    .build());

                if (profitPerHour > 0) {
                    long remCushion = totalGoal - totalWealth;
                    double hrs = (double) remCushion / (double) profitPerHour;
                    String etaText = hrs >= 24.0 ? String.format("~%.1f days", hrs / 24.0) : String.format("~%.1f hrs", hrs);
                    panelComponent.getChildren().add(LineComponent.builder()
                        .left("P2P ETA:")
                        .right(etaText)
                        .rightColor(new Color(230, 126, 34)) // Warm Orange
                        .build());
                } else {
                    panelComponent.getChildren().add(LineComponent.builder()
                        .left("Bond Status:")
                        .right(String.format("Secured! (+%.1fM)", cushionSaved / 1_000_000.0))
                        .rightColor(new Color(46, 204, 113))
                        .build());
                }
            } else {
                // Phase C: Freedom Achieved!
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Freedom:")
                    .right(String.format("READY! (%.1fM)", totalWealth / 1_000_000.0))
                    .rightColor(new Color(46, 204, 113)) // Bright Green
                    .build());

                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status:")
                    .right("Ready for P2P! \uD83C\uDF89")
                    .rightColor(new Color(46, 204, 113))
                    .build());
            }
        }

        // Magic XP / Hour
        if (config.showMagicXp()) {
            int xpPerHour = plugin.getXpPerHour();
            String xpHrStr = alchs < 3 ? "--" : String.format("%,d / hr", xpPerHour);
            panelComponent.getChildren().add(LineComponent.builder()
                .left("XP/hr:")
                .right(xpHrStr)
                .rightColor(new Color(255, 235, 59))
                .build());
        }

        // Last Alched Item
        String lastItem = plugin.getLastAlchedItem();
        if (lastItem != null && !lastItem.isEmpty()) {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Last:")
                .right(lastItem)
                .rightColor(Color.LIGHT_GRAY)
                .build());
        }

        return super.render(graphics);
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0) return "0s";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return String.format("%dh %02dm", h, m);
        } else if (m > 0) {
            return String.format("%dm %02ds", m, s);
        } else {
            return String.format("%ds", s);
        }
    }

    private String formatGp(long amount) {
        long abs = Math.abs(amount);
        if (abs >= 1_000_000) {
            return String.format("%.2fM gp", amount / 1_000_000.0);
        } else if (abs >= 1_000) {
            return String.format("%.1fk gp", amount / 1_000.0);
        } else {
            return String.format("%,d gp", amount);
        }
    }
}
