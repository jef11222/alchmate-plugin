package com.alchmate;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("alchmate")
public interface AlchMateConfig extends Config {
    @ConfigItem(
        keyName = "syncCashAndRunes",
        name = "Sync Coins & Nature Runes",
        description = "Automatically track Coins and Nature Runes count for the HUD and web dashboard",
        position = 0
    )
    default boolean syncCashAndRunes() {
        return true;
    }

    @ConfigItem(
        keyName = "trackAlchCasts",
        name = "Track Alch Casts",
        description = "Detect Magic XP drops (+65 XP) to track active High Alch casts",
        position = 2
    )
    default boolean trackAlchCasts() {
        return true;
    }

    @ConfigItem(
        keyName = "showOverlay",
        name = "Show In-Game Overlay",
        description = "Display live Alchs/hr and Profit/hr overlay window above inventory",
        position = 3
    )
    default boolean showOverlay() {
        return true;
    }

    @ConfigItem(
        keyName = "showProfit",
        name = "Show Profit / Hr",
        description = "Display estimated profit and profit/hour in the overlay",
        position = 4
    )
    default boolean showProfit() {
        return true;
    }

    @ConfigItem(
        keyName = "showMagicXp",
        name = "Show Magic XP / Hr",
        description = "Display Magic XP gained and XP/hour in the overlay",
        position = 5
    )
    default boolean showMagicXp() {
        return true;
    }

    @ConfigItem(
        keyName = "costBasisMode",
        name = "Profit Cost Basis",
        description = "Automatically detect whether item was bought via GE or P2P Player Trade, or force a specific basis",
        position = 6
    )
    default CostBasisMode costBasisMode() {
        return CostBasisMode.AUTO_DETECT;
    }

    @ConfigItem(
        keyName = "manualTradeBuyPrice",
        name = "Manual W308 Buy Price",
        description = "Optional fallback manual price per item if trade window wasn't captured (0 = auto-detect)",
        position = 7
    )
    default int manualTradeBuyPrice() {
        return 0;
    }

    @ConfigItem(
        keyName = "showBondTracker",
        name = "Show Bond Progress",
        description = "Display live Bond progress bar, percentage, and ETA on overlay",
        position = 8
    )
    default boolean showBondTracker() {
        return true;
    }

    @ConfigItem(
        keyName = "showStage2Cushion",
        name = "Track Stage 2 Cushion",
        description = "Track Stage 2 P2P operating cushion and completion ETA once Bond ticket is secured",
        position = 9
    )
    default boolean showStage2Cushion() {
        return true;
    }

    @ConfigItem(
        keyName = "cushionAmount",
        name = "Stage 2 Cushion (gp)",
        description = "Target working capital to save after securing the Bond before entering Members (default: 5,000,000 gp)",
        position = 10
    )
    default int cushionAmount() {
        return 5000000;
    }

    @ConfigItem(
        keyName = "enableLocalBridge",
        name = "Local Web Server & Bridge",
        description = "Run embedded local web server (127.0.0.1:18833) for 0-latency multi-accounting and $0 web dashboard",
        position = 98
    )
    default boolean enableLocalBridge() {
        return true;
    }

    @ConfigItem(
        keyName = "bridgePort",
        name = "Local Bridge Port",
        description = "Local port for developer loopback (default: 18833)",
        position = 99
    )
    default int bridgePort() {
        return 18833;
    }
}
