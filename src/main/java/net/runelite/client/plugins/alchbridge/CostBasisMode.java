package net.runelite.client.plugins.alchbridge;

public enum CostBasisMode {
    AUTO_DETECT("Auto-Detect (GE / P2P)"),
    W308_TRADE("Force P2P / W308"),
    GRAND_EXCHANGE("Force Live GE");

    private final String displayName;

    CostBasisMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
