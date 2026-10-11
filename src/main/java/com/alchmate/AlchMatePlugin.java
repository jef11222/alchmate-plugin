package com.alchmate;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
    name = "AlchMate",
    description = "High Alch session tracking, Bond progression HUD & companion bridge",
    tags = {"alch", "ge", "tracker", "bridge", "dashboard", "panel", "overlay", "alchmate", "bond"},
    enabledByDefault = true
)
public class AlchMatePlugin extends Plugin {
    private static final Logger log = LoggerFactory.getLogger(AlchMatePlugin.class);

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ItemManager itemManager;

    @Inject
    private AlchMateConfig config;

    @Inject
    private Gson gson;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private AlchMateOverlay overlay;

    private AlchMatePanel panel;
    private NavigationButton navButton;

    private HttpClient httpClient;
    private ExecutorService httpExecutor;
    private ScheduledExecutorService pollerExecutor;

    private final Map<Integer, String> lastOfferState = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> lastOfferQty = new ConcurrentHashMap<>();
    private int lastMagicXp = -1;
    private int lastCoins = -1;
    private int lastNatureRunes = -1;

    // Real-time In-Game Alch Session Metrics (Runs locally with 0 latency)
    private int sessionAlchs = 0;
    private long sessionProfit = 0;
    private int sessionMagicXp = 0;
    private long sessionStartTime = 0;
    private long lastAlchTimestamp = 0;
    private long totalActiveTimeMs = 0;
    private String lastAlchedItem = null;
    private final Map<Integer, Integer> lastInventoryItems = new ConcurrentHashMap<>();

    // Rate caching & rolling smoothing (RuneLite 60s floor + rolling cast intervals)
    private final java.util.ArrayDeque<Long> recentCastTimestamps = new java.util.ArrayDeque<>();
    private int cachedAlchsPerHour = 0;
    private long cachedProfitPerHour = 0;
    private int cachedXpPerHour = 0;
    private long lastRateUpdateTime = 0;

    // Cost Basis & Trade Tracking (W308 vs GE)
    private CostBasisMode activeCostBasisOverride = null;
    private final Map<Integer, Integer> lastW308TradePrices = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> lastGeBuyPrices = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastW308TradeTimes = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastGeBuyTimes = new ConcurrentHashMap<>();
    private String lastW308TradeItemName = null;
    private int lastW308TradePrice = 0;
    private int lastAlchedItemId = -1;
    private final Map<Integer, Integer> liveTrackerPrices = new ConcurrentHashMap<>();

    // Inventory Batch Scanning & ETA
    private String batchItemName = null;
    private int batchItemQty = 0;
    private int batchDistinctTypes = 0;
    private boolean batchIsNoted = false;
    private int batchHaPrice = 0;
    private int batchBuyCost = 0;
    private int batchProfitEa = 0;
    private long batchTotalProfit = 0;
    private long batchTotalXp = 0;
    private long batchGrossGp = 0;
    private long batchEstSeconds = 0;
    private int batchNatureRunes = 0;
    private String batchFireSource = "--";

    public static class BatchItemEntry {
        public final int unnotedId;
        public final String name;
        public int qty;
        public final boolean isNoted;
        public final int haPrice;
        public final int buyCost;
        public final int profitEa;
        public long totalProfit;
        public long grossGp;

        public BatchItemEntry(int unnotedId, String name, int qty, boolean isNoted,
                              int haPrice, int buyCost, int profitEa, long totalProfit, long grossGp) {
            this.unnotedId = unnotedId;
            this.name = name;
            this.qty = qty;
            this.isNoted = isNoted;
            this.haPrice = haPrice;
            this.buyCost = buyCost;
            this.profitEa = profitEa;
            this.totalProfit = totalProfit;
            this.grossGp = grossGp;
        }
    }

    // Two-Way Trade Tracking (Buy & Sell)
    private int pendingTradeCoinsOffered = 0;
    private int pendingTradeCoinsReceived = 0;
    private final Map<Integer, Integer> pendingTradeItemsOffered = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> pendingTradeItemsReceived = new ConcurrentHashMap<>();
    private long lastTradeActivityTime = 0;

    private final Map<Integer, Integer> sessionAlchedItemCounts = new ConcurrentHashMap<>();
    private long sessionTotalHighAlchGp = 0;
    private long sessionTotalNatCost = 0;
    private long sessionUnknownItemProfit = 0;
    private long sessionTradeProfit = 0;

    // Wealth & Bond Tracking
    private int lastBankCoins = 0;
    private long sessionStartWealth = 0;
    private Boolean bondTrackerOverride = null;
    private Boolean stage2CushionOverride = null;
    private long syncedCushionAmount = -1;

    // Real-time Skilling Progression & Activity Auto-Detection
    private Skill detectedSkill = null;
    private String detectedActivityName = "";
    private double liveSkillingXpHr = 0.0;
    private long lastSkillingDropTime = 0;
    private final Map<Skill, Integer> lastSkillXpMap = new ConcurrentHashMap<>();
    private final java.util.ArrayDeque<long[]> recentSkillingXpDrops = new java.util.ArrayDeque<>();

    @Provides
    AlchMateConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AlchMateConfig.class);
    }

    public AlchMateConfig getConfig() {
        return config;
    }

    @Override
    protected void startUp() {
        httpExecutor = Executors.newSingleThreadExecutor();
        httpClient = HttpClient.newBuilder()
            .executor(httpExecutor)
            .connectTimeout(Duration.ofMillis(800))
            .build();

        // Register in-game overlay above inventory
        if (overlayManager != null && overlay != null) {
            overlayManager.add(overlay);
        }

        // Create in-game sidebar panel
        panel = new AlchMatePanel(this);
        navButton = NavigationButton.builder()
            .tooltip("AlchMate")
            .icon(createIcon())
            .priority(6)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        // Start background poller to fetch Top 10 items and sync live account snapshot every 4 seconds
        pollerExecutor = Executors.newSingleThreadScheduledExecutor();
        pollerExecutor.scheduleWithFixedDelay(() -> {
            fetchTop10Async();
            try {
                if (client != null && client.getGameState() == GameState.LOGGED_IN) {
                    sendAccountSnapshot();
                }
            } catch (Exception ignored) {}
        }, 1, 4, TimeUnit.SECONDS);

        log.info("Alch Dashboard Bridge plugin started (Port: {})", config.bridgePort());

        clientThread.invokeLater(() -> {
            if (client != null && client.getGameState() == GameState.LOGGED_IN) {
                sendAccountSnapshot();
                scanInventoryBatch(null);
            }
        });
    }

    @Override
    protected void shutDown() {
        if (overlayManager != null && overlay != null) {
            overlayManager.remove(overlay);
        }
        if (clientToolbar != null && navButton != null) {
            clientToolbar.removeNavigation(navButton);
        }
        if (pollerExecutor != null && !pollerExecutor.isShutdown()) {
            pollerExecutor.shutdownNow();
        }
        if (httpExecutor != null && !httpExecutor.isShutdown()) {
            httpExecutor.shutdownNow();
        }
        lastOfferState.clear();
        lastOfferQty.clear();
        lastInventoryItems.clear();
        sessionStartTime = 0;
        lastAlchTimestamp = 0;
        totalActiveTimeMs = 0;
        lastAlchedItem = null;
        lastAlchedItemId = -1;
        sessionAlchedItemCounts.clear();
        sessionTotalHighAlchGp = 0;
        sessionTotalNatCost = 0;
        sessionUnknownItemProfit = 0;
        sessionTradeProfit = 0;
        recentCastTimestamps.clear();
        cachedAlchsPerHour = 0;
        cachedProfitPerHour = 0;
        cachedXpPerHour = 0;
        lastRateUpdateTime = 0;
        batchDistinctTypes = 0;
        detectedSkill = null;
        detectedActivityName = "";
        liveSkillingXpHr = 0.0;
        lastSkillingDropTime = 0;
        recentSkillingXpDrops.clear();
        lastMagicXp = -1;
        lastCoins = -1;
        lastNatureRunes = -1;
        log.info("AlchMate plugin stopped");
    }

    public void fetchTop10Async() {
        if (httpClient == null) return;
        try {
            int port = config.bridgePort();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/top10"))
                .timeout(Duration.ofMillis(1200))
                .GET()
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() == 200) {
                        Type listType = new TypeToken<List<Map<String, Object>>>(){}.getType();
                        List<Map<String, Object>> items = gson.fromJson(resp.body(), listType);
                        if (items != null) {
                            for (Map<String, Object> it : items) {
                                Object idObj = it.get("id");
                                Object priceObj = it.get("buy_price");
                                if (idObj != null && priceObj != null) {
                                    try {
                                        int id = ((Number) idObj).intValue();
                                        int price = ((Number) priceObj).intValue();
                                        if (id > 0 && price > 0) {
                                            liveTrackerPrices.put(id, price);
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                        if (panel != null) {
                            panel.updateTop10(items, true);
                        }
                    } else {
                        if (panel != null) {
                            panel.updateTop10(null, false);
                        }
                    }
                })
                .exceptionally(ex -> {
                    if (panel != null) {
                        panel.updateTop10(null, false);
                    }
                    return null;
                });
        } catch (Exception e) {
            if (panel != null) {
                panel.updateTop10(null, false);
            }
        }
    }

    private BufferedImage createIcon() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(243, 156, 18));
        g.fillOval(1, 1, 14, 14);
        g.setColor(new Color(211, 84, 0));
        g.drawOval(1, 1, 14, 14);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        FontMetrics fm = g.getFontMetrics();
        int x = (16 - fm.stringWidth("A")) / 2;
        int y = ((16 - fm.getHeight()) / 2) + fm.getAscent();
        g.drawString("A", x, y);
        g.dispose();
        return image;
    }

    private String getAccountName() {
        Player local = client.getLocalPlayer();
        if (local != null && local.getName() != null && !local.getName().isEmpty()) {
            return local.getName();
        }
        return "Unknown";
    }

    private boolean isMembersWorld() {
        try {
            return client.getWorldType() != null && client.getWorldType().contains(WorldType.MEMBERS);
        } catch (Exception e) {
            return true;
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            sendAccountSnapshot();
        }
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
        GrandExchangeOffer offer = event.getOffer();
        int slot = event.getSlot();

        GrandExchangeOfferState state = offer.getState();
        if (state == GrandExchangeOfferState.EMPTY) {
            lastOfferState.remove(slot);
            lastOfferQty.remove(slot);
            Map<String, Object> data = new HashMap<>();
            data.put("event", "GE_OFFER");
            data.put("account", getAccountName());
            data.put("slot", slot);
            data.put("state", "EMPTY");
            sendPayload(data);
            return;
        }

        String stateName = state.name();
        String lastState = lastOfferState.get(slot);
        int qtySold = offer.getQuantitySold();
        int lastQty = lastOfferQty.getOrDefault(slot, -1);

        if (stateName.equals(lastState) && qtySold == lastQty) {
            return;
        }
        lastOfferState.put(slot, stateName);
        lastOfferQty.put(slot, qtySold);

            // Track BUY offers (active BUYING, completed BOUGHT, or cancelled partial CANCELLED_BUY)
        if (state == GrandExchangeOfferState.BUYING 
            || state == GrandExchangeOfferState.BOUGHT 
            || state == GrandExchangeOfferState.CANCELLED_BUY) {
            int itemId = offer.getItemId();
            if (itemId <= 0) {
                return;
            }
            int unnotedId = itemId;
            try {
                ItemComposition comp = itemManager.getItemComposition(itemId);
                if (comp != null && comp.getNote() != -1 && comp.getLinkedNoteId() > 0) {
                    unnotedId = comp.getLinkedNoteId();
                }
            } catch (Exception ignored) {}

            if (qtySold > 0 && offer.getSpent() > 0) {
                int unitP = (int) Math.round((double) offer.getSpent() / (double) qtySold);
                if (unitP > 0) {
                    lastGeBuyPrices.put(unnotedId, unitP);
                    lastGeBuyTimes.put(unnotedId, System.currentTimeMillis());
                }
            }

            String itemName = itemManager.getItemComposition(itemId).getName();

            Map<String, Object> data = new HashMap<>();
            data.put("event", "GE_OFFER");
            data.put("account", getAccountName());
            data.put("slot", slot);
            data.put("state", stateName);
            data.put("itemId", itemId);
            data.put("itemName", itemName);
            data.put("price", offer.getPrice());
            data.put("spent", offer.getSpent());
            data.put("quantitySold", qtySold);
            data.put("totalQuantity", offer.getTotalQuantity());
            data.put("timestamp", System.currentTimeMillis() / 1000.0);

            sendPayload(data);

            if (state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.CANCELLED_BUY) {
                syncGrandExchangeLimitsAndTrades();
            }
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() == InventoryID.INVENTORY.getId()) {
            ItemContainer inv = event.getItemContainer();
            if (inv == null) {
                return;
            }

            int coins = inv.count(ItemID.COINS_995);
            int natureRunes = inv.count(ItemID.NATURE_RUNE);

            // Snapshot non-currency items: id -> quantity
            Map<Integer, Integer> currentItems = new HashMap<>();
            Item[] items = inv.getItems();
            if (items != null) {
                for (Item itm : items) {
                    if (itm != null && itm.getId() > 0 && itm.getQuantity() > 0) {
                        int id = itm.getId();
                        if (id != ItemID.COINS_995 && id != ItemID.NATURE_RUNE) {
                            currentItems.merge(id, itm.getQuantity(), Integer::sum);
                        }
                    }
                }
            }

            // Real-time profit detection: coins increased after alch
            if (lastCoins >= 0 && coins > lastCoins) {
                int deltaCoins = coins - lastCoins;
                int alchedId = -1;

                for (Map.Entry<Integer, Integer> prev : lastInventoryItems.entrySet()) {
                    int id = prev.getKey();
                    int oldCount = prev.getValue();
                    int newCount = currentItems.getOrDefault(id, 0);
                    if (newCount == oldCount - 1) {
                        alchedId = id;
                        break;
                    }
                }

                int natCost = (int) itemManager.getItemPrice(ItemID.NATURE_RUNE);
                if (natCost <= 0) {
                    natCost = 90;
                }

                if (alchedId > 0) {
                    ItemComposition comp = itemManager.getItemComposition(alchedId);
                    int unnotedId = alchedId;
                    String itemName = comp.getName();
                    if (comp.getNote() != -1 && comp.getLinkedNoteId() > 0) {
                        unnotedId = comp.getLinkedNoteId();
                        ItemComposition unnotedComp = itemManager.getItemComposition(unnotedId);
                        if (unnotedComp != null) {
                            itemName = unnotedComp.getName();
                        }
                    }

                    lastAlchedItemId = unnotedId;
                    sessionAlchedItemCounts.merge(unnotedId, 1, Integer::sum);
                    sessionTotalHighAlchGp += deltaCoins;
                    sessionTotalNatCost += natCost;

                    long itemBuyPrice = getItemBuyCost(unnotedId);
                    int haPrice = deltaCoins;
                    long profit = haPrice - itemBuyPrice - natCost;

                    sessionProfit = calculateSessionProfit();
                    lastAlchedItem = String.format("%s (%+d gp)", itemName, profit);
                } else {
                    // Safe fallback so unknown/unindexed alched items never count full gross coins as profit
                    long itemBuyPrice = Math.max(0, deltaCoins - 550);
                    long profit = Math.max(0, deltaCoins - itemBuyPrice - natCost);
                    sessionUnknownItemProfit += profit;
                    sessionProfit = calculateSessionProfit();
                }
            }

            lastCoins = coins;
            lastNatureRunes = natureRunes;
            lastInventoryItems.clear();
            lastInventoryItems.putAll(currentItems);
            scanInventoryBatch(inv);

            if (config.syncCashAndRunes()) {
                Map<String, Object> data = new HashMap<>();
                data.put("event", "INVENTORY_SYNC");
                data.put("account", getAccountName());
                data.put("coins", coins);
                data.put("natureRunes", natureRunes);
                data.put("totalWealth", getTotalWealth());
                data.put("geInvestedCoins", getGeInvestedCoins());
                data.put("batchGrossGp", batchGrossGp);
                data.put("batchItemName", batchItemName != null ? batchItemName : "");
                data.put("batchItemQty", batchItemQty);
                data.put("profitPerHour", getProfitPerHour());
                data.put("alchsPerHour", getAlchsPerHour());
                data.put("timestamp", System.currentTimeMillis() / 1000.0);
                sendPayload(data);
            }
        } else if (event.getContainerId() == InventoryID.BANK.getId()) {
            ItemContainer bank = event.getItemContainer();
            if (bank == null) {
                return;
            }
            int coins = bank.count(ItemID.COINS_995);
            int natureRunes = bank.count(ItemID.NATURE_RUNE);
            lastBankCoins = coins;
            if (sessionStartWealth <= 0 && coins > 0) {
                sessionStartWealth = (long) Math.max(0, lastCoins) + (long) lastBankCoins;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("event", "BANK_SYNC");
            data.put("account", getAccountName());
            data.put("bankCoins", coins);
            data.put("bankNatureRunes", natureRunes);
            data.put("totalWealth", getTotalWealth());
            data.put("timestamp", System.currentTimeMillis() / 1000.0);

            sendPayload(data);
        } else if (event.getContainerId() == InventoryID.TRADE.getId()) {
            ItemContainer trade = event.getItemContainer();
            if (trade != null) {
                pendingTradeCoinsOffered = trade.count(ItemID.COINS_995);
                Item[] items = trade.getItems();
                Map<Integer, Integer> map = new HashMap<>();
                if (items != null) {
                    for (Item itm : items) {
                        if (itm != null && itm.getId() > 0 && itm.getQuantity() > 0 && itm.getId() != ItemID.COINS_995) {
                            int id = itm.getId();
                            ItemComposition comp = itemManager.getItemComposition(id);
                            int unnotedId = (comp.getNote() != -1 && comp.getLinkedNoteId() > 0) ? comp.getLinkedNoteId() : id;
                            map.merge(unnotedId, itm.getQuantity(), Integer::sum);
                        }
                    }
                }
                pendingTradeItemsOffered.clear();
                pendingTradeItemsOffered.putAll(map);
                lastTradeActivityTime = System.currentTimeMillis();
            }
        } else if (event.getContainerId() == InventoryID.TRADEOTHER.getId()) {
            ItemContainer tradeOther = event.getItemContainer();
            if (tradeOther != null) {
                pendingTradeCoinsReceived = tradeOther.count(ItemID.COINS_995);
                Item[] items = tradeOther.getItems();
                Map<Integer, Integer> map = new HashMap<>();
                if (items != null) {
                    for (Item itm : items) {
                        if (itm != null && itm.getId() > 0 && itm.getQuantity() > 0 && itm.getId() != ItemID.COINS_995) {
                            int id = itm.getId();
                            ItemComposition comp = itemManager.getItemComposition(id);
                            int unnotedId = (comp.getNote() != -1 && comp.getLinkedNoteId() > 0) ? comp.getLinkedNoteId() : id;
                            map.merge(unnotedId, itm.getQuantity(), Integer::sum);
                        }
                    }
                }
                pendingTradeItemsReceived.clear();
                pendingTradeItemsReceived.putAll(map);
                lastTradeActivityTime = System.currentTimeMillis();
            }
        } else if (event.getContainerId() == InventoryID.EQUIPMENT.getId()) {
            scanInventoryBatch(null);
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        String msg = event.getMessage();
        if (msg == null) {
            return;
        }

        if (msg.contains("Accepted trade")) {
            commitPendingTrade();
        } else if (msg.contains("declined") || msg.contains("Declined")) {
            clearPendingTrade();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if ("alchmate".equals(event.getGroup())) {
            if ("costBasisMode".equals(event.getKey())) {
                activeCostBasisOverride = null;
                sessionProfit = calculateSessionProfit();
                recalculateRates(System.currentTimeMillis());
                scanInventoryBatch(null);
            } else if ("manualTradeBuyPrice".equals(event.getKey())) {
                sessionProfit = calculateSessionProfit();
                recalculateRates(System.currentTimeMillis());
                scanInventoryBatch(null);
            } else if ("showBondTracker".equals(event.getKey())) {
                bondTrackerOverride = null;
            } else if ("showStage2Cushion".equals(event.getKey())) {
                stage2CushionOverride = null;
            }
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        Skill skill = event.getSkill();
        int currentXp = event.getXp();

        if (skill == Skill.MAGIC && config.trackAlchCasts()) {
            if (lastMagicXp > 0) {
                int diff = currentXp - lastMagicXp;
                if (diff == 65 || diff == 31) {
                    handleAlchCast(diff, event.getLevel());
                }
            }
            lastMagicXp = currentXp;
        }

        if (skill == Skill.CRAFTING || skill == Skill.FLETCHING || skill == Skill.MAGIC || skill == Skill.SMITHING) {
            Integer prevXp = lastSkillXpMap.get(skill);
            if (prevXp != null && prevXp > 0) {
                int diff = currentXp - prevXp;
                if (diff > 0) {
                    handleSkillingXpDrop(skill, diff);
                }
            }
            lastSkillXpMap.put(skill, currentXp);

            sendSkillsSync(skill);
        }
    }

    private void handleSkillingXpDrop(Skill skill, int diff) {
        long now = System.currentTimeMillis();
        lastSkillingDropTime = now;
        detectedSkill = skill;
        detectedActivityName = identifySkillingActivity(skill, diff);

        recentSkillingXpDrops.addLast(new long[]{now, diff});
        while (!recentSkillingXpDrops.isEmpty() && (now - recentSkillingXpDrops.peekFirst()[0] > 300000L)) {
            recentSkillingXpDrops.removeFirst();
        }

        if (recentSkillingXpDrops.size() >= 2) {
            long windowMs = now - recentSkillingXpDrops.peekFirst()[0];
            if (windowMs >= 8000L) {
                long sumXp = 0;
                for (long[] d : recentSkillingXpDrops) {
                    sumXp += d[1];
                }
                liveSkillingXpHr = (sumXp / (windowMs / 1000.0)) * 3600.0;
            }
        }
    }

    private void sendSkillsSync(Skill activeSkill) {
        Map<String, Object> data = new HashMap<>();
        data.put("event", "SKILLS_SYNC");
        data.put("account", getAccountName());
        data.put("crafting", client.getRealSkillLevel(Skill.CRAFTING));
        data.put("smithing", client.getRealSkillLevel(Skill.SMITHING));
        data.put("fletching", client.getRealSkillLevel(Skill.FLETCHING));
        data.put("magic", client.getRealSkillLevel(Skill.MAGIC));
        data.put("crafting_xp", client.getSkillExperience(Skill.CRAFTING));
        data.put("smithing_xp", client.getSkillExperience(Skill.SMITHING));
        data.put("fletching_xp", client.getSkillExperience(Skill.FLETCHING));
        data.put("magic_xp", client.getSkillExperience(Skill.MAGIC));

        Skill targetSkill = (detectedSkill != null) ? detectedSkill : activeSkill;
        if (targetSkill != null) {
            int curXp = client.getSkillExperience(targetSkill);
            int curLvl = client.getRealSkillLevel(targetSkill);
            int nextLvlXp = getXpForLevel(Math.min(99, curLvl + 1));
            int remXp = Math.max(0, nextLvlXp - curXp);
            int estSecs = (liveSkillingXpHr > 1000) ? (int) Math.round((remXp / liveSkillingXpHr) * 3600.0) : 0;

            data.put("active_skill", targetSkill.getName());
            data.put("detected_activity", detectedActivityName != null ? detectedActivityName : "");
            data.put("skilling_xp_hr", (int) Math.round(liveSkillingXpHr));
            data.put("rem_xp_next", remXp);
            data.put("est_secs_next", estSecs);

            if (panel != null) {
                panel.updateSkillingProgression(
                    targetSkill.getName(),
                    detectedActivityName,
                    curLvl,
                    Math.min(99, curLvl + 1),
                    remXp,
                    liveSkillingXpHr,
                    estSecs
                );
            }
        }

        data.put("timestamp", System.currentTimeMillis() / 1000.0);
        sendPayload(data);
    }

    private String identifySkillingActivity(Skill skill, int xpGained) {
        if (skill == Skill.SMITHING) {
            if (xpGained == 125) return "Bronze Platebody";
            if (xpGained == 250) return "Iron Platebody";
            if (xpGained == 375) return "Steel Platebody";
            if (xpGained == 500) return "Mithril Platebody";
            if (xpGained == 625) return "Adamant Platebody";
            if (xpGained == 750) return "Rune Platebody";
            if (xpGained == 75) return "Bronze Platelegs/Skirt";
            if (xpGained == 150) return "Iron Platelegs/Skirt";
            if (xpGained == 225) return "Steel Platelegs/Skirt";
            if (xpGained == 300) return "Mithril Platelegs/Skirt";
            if (xpGained == 450) return "Rune Platelegs/Skirt";
            if (xpGained == 30) return "Smelting Mithril Bars";
            if (xpGained == 37 || xpGained == 38) return "Smelting Adamant Bars";
            if (xpGained == 50) {
                ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
                if (inv != null && (inv.count(ItemID.RUNITE_ORE) > 0 || inv.count(ItemID.COAL) > 0)) {
                    return "Smelting Rune Bars";
                }
                return "Mithril Dagger";
            }
            if (xpGained == 17 || xpGained == 18) return "Smelting Steel Bars";
            if (xpGained == 12 || xpGained == 13) return "Smelting Iron Bars";
            if (xpGained == 14) return "Smelting Silver Bars";
            if (xpGained == 22 || xpGained == 23) return "Smelting Gold Bars";
            if (xpGained == 56 || xpGained == 57) return "Smelting Gold Bars (Gauntlets)";
            if (xpGained == 6 || xpGained == 7) return "Smelting Bronze Bars";
            if (xpGained == 25 || xpGained == 26) return "Smelting Cannonballs";
            return "Smithing";
        } else if (skill == Skill.CRAFTING) {
            if (xpGained == 50) return "Cutting Sapphires";
            if (xpGained == 67 || xpGained == 68) return "Cutting Emeralds";
            if (xpGained == 85) return "Cutting Rubies";
            if (xpGained == 107 || xpGained == 108) return "Cutting Diamonds";
            if (xpGained == 20) return "Blowing Beer Glass";
            if (xpGained == 52 || xpGained == 53) return "Blowing Unpowered Orbs";
            if (xpGained == 100) return "Blowing Lantern Lenses";
            if (xpGained == 186) return "Green D'hide Bodies";
            if (xpGained == 210) return "Blue D'hide Bodies";
            if (xpGained == 234) return "Red D'hide Bodies";
            if (xpGained == 258) return "Black D'hide Bodies";
            if (xpGained == 105) return "Leather Bodies";
            return "Crafting Items";
        } else if (skill == Skill.FLETCHING) {
            if (xpGained == 5) return "Arrow Shafts";
            if (xpGained == 33 || xpGained == 34) return "Fletching Willow Longbows (u)";
            if (xpGained == 41 || xpGained == 42) return "Stringing Willow Shortbows";
            if (xpGained == 58) return "Fletching Maple Longbows (u)";
            if (xpGained == 67 || xpGained == 68) return "Fletching Yew Longbows (u)";
            if (xpGained == 75) return "Stringing Yew Longbows";
            if (xpGained == 83 || xpGained == 84) return "Fletching Magic Longbows (u)";
            if (xpGained == 91 || xpGained == 92) return "Stringing Magic Longbows";
            if (xpGained == 25) return "Fletching Mithril Darts";
            if (xpGained == 30) return "Fletching Adamant Darts";
            if (xpGained == 40) return "Fletching Broad Bolts";
            return "Fletching Items";
        } else if (skill == Skill.MAGIC) {
            if (xpGained == 65) return "High Level Alchemy";
            if (xpGained == 31) return "Low Level Alchemy";
            if (xpGained == 83) return "Superheat Item";
            return "Magic Training";
        }
        return skill.getName();
    }

    private void detectSkillingFromInventory(ItemContainer inv) {
        if (inv == null) return;

        // 1. Smelting Ores (Check ores first: player can have a hammer in inventory while smelting!)
        if (inv.count(ItemID.MITHRIL_ORE) > 0) {
            detectedSkill = Skill.SMITHING;
            detectedActivityName = "Smelting Mithril Bars";
            return;
        } else if (inv.count(ItemID.ADAMANTITE_ORE) > 0) {
            detectedSkill = Skill.SMITHING;
            detectedActivityName = "Smelting Adamant Bars";
            return;
        } else if (inv.count(ItemID.RUNITE_ORE) > 0) {
            detectedSkill = Skill.SMITHING;
            detectedActivityName = "Smelting Rune Bars";
            return;
        } else if (inv.count(ItemID.IRON_ORE) > 0) {
            detectedSkill = Skill.SMITHING;
            if (inv.count(ItemID.COAL) > 0) {
                detectedActivityName = "Smelting Steel Bars";
            } else {
                detectedActivityName = "Smelting Iron Bars";
            }
            return;
        } else if (inv.count(ItemID.GOLD_ORE) > 0) {
            detectedSkill = Skill.SMITHING;
            detectedActivityName = "Smelting Gold Bars";
            return;
        } else if (inv.count(ItemID.SILVER_ORE) > 0) {
            detectedSkill = Skill.SMITHING;
            detectedActivityName = "Smelting Silver Bars";
            return;
        } else if (inv.count(ItemID.COPPER_ORE) > 0 || inv.count(ItemID.TIN_ORE) > 0) {
            detectedSkill = Skill.SMITHING;
            detectedActivityName = "Smelting Bronze Bars";
            return;
        } else if (inv.count(ItemID.STEEL_BAR) > 0 && inv.count(ItemID.AMMO_MOULD) > 0) {
            detectedSkill = Skill.SMITHING;
            detectedActivityName = "Smelting Cannonballs";
            return;
        }

        // 2. Anvil Smithing with Hammer & Bars
        boolean hasHammer = inv.count(ItemID.HAMMER) > 0;
        if (hasHammer) {
            if (inv.count(ItemID.RUNITE_BAR) > 0) {
                detectedSkill = Skill.SMITHING;
                detectedActivityName = "Smithing Rune Bars";
                return;
            } else if (inv.count(ItemID.ADAMANTITE_BAR) > 0) {
                detectedSkill = Skill.SMITHING;
                detectedActivityName = "Smithing Adamant Bars";
                return;
            } else if (inv.count(ItemID.MITHRIL_BAR) > 0) {
                detectedSkill = Skill.SMITHING;
                detectedActivityName = "Smithing Mithril Bars";
                return;
            } else if (inv.count(ItemID.STEEL_BAR) > 0) {
                detectedSkill = Skill.SMITHING;
                detectedActivityName = "Smithing Steel Bars";
                return;
            } else if (inv.count(ItemID.IRON_BAR) > 0) {
                detectedSkill = Skill.SMITHING;
                detectedActivityName = "Smithing Iron Bars";
                return;
            } else if (inv.count(ItemID.BRONZE_BAR) > 0) {
                detectedSkill = Skill.SMITHING;
                detectedActivityName = "Smithing Bronze Bars";
                return;
            }
        }

        // 3. Glassblowing
        boolean hasGlassPipe = inv.count(ItemID.GLASSBLOWING_PIPE) > 0;
        if (hasGlassPipe && inv.count(ItemID.MOLTEN_GLASS) > 0) {
            detectedSkill = Skill.CRAFTING;
            detectedActivityName = "Blowing Molten Glass";
            return;
        }

        // 4. Gem Cutting
        boolean hasChisel = inv.count(ItemID.CHISEL) > 0;
        if (hasChisel) {
            if (inv.count(ItemID.UNCUT_DIAMOND) > 0) {
                detectedSkill = Skill.CRAFTING;
                detectedActivityName = "Cutting Diamonds";
                return;
            } else if (inv.count(ItemID.UNCUT_RUBY) > 0) {
                detectedSkill = Skill.CRAFTING;
                detectedActivityName = "Cutting Rubies";
                return;
            } else if (inv.count(ItemID.UNCUT_EMERALD) > 0) {
                detectedSkill = Skill.CRAFTING;
                detectedActivityName = "Cutting Emeralds";
                return;
            } else if (inv.count(ItemID.UNCUT_SAPPHIRE) > 0) {
                detectedSkill = Skill.CRAFTING;
                detectedActivityName = "Cutting Sapphires";
                return;
            }
        }

        // 5. Fletching
        boolean hasKnife = inv.count(ItemID.KNIFE) > 0;
        if (hasKnife) {
            if (inv.count(ItemID.MAGIC_LOGS) > 0) {
                detectedSkill = Skill.FLETCHING;
                detectedActivityName = "Fletching Magic Logs";
                return;
            } else if (inv.count(ItemID.YEW_LOGS) > 0) {
                detectedSkill = Skill.FLETCHING;
                detectedActivityName = "Fletching Yew Logs";
                return;
            } else if (inv.count(ItemID.MAPLE_LOGS) > 0) {
                detectedSkill = Skill.FLETCHING;
                detectedActivityName = "Fletching Maple Logs";
                return;
            } else if (inv.count(ItemID.WILLOW_LOGS) > 0) {
                detectedSkill = Skill.FLETCHING;
                detectedActivityName = "Fletching Willow Logs";
                return;
            }
        } else if (inv.count(ItemID.BOW_STRING) > 0) {
            detectedSkill = Skill.FLETCHING;
            detectedActivityName = "Stringing Bows";
            return;
        }
    }

    public static int getXpForLevel(int lvl) {
        if (lvl <= 1) return 0;
        int points = 0;
        for (int i = 1; i < lvl; i++) {
            points += Math.floor(i + 300.0 * Math.pow(2.0, i / 7.0));
        }
        return points / 4;
    }

    private void handleAlchCast(int diff, int magicLevel) {
        long now = System.currentTimeMillis();
        if (sessionStartTime == 0) {
            sessionStartTime = now;
            lastAlchTimestamp = now;
            totalActiveTimeMs = 3000;
        } else {
            long gap = now - lastAlchTimestamp;
            if (gap <= 15000) {
                totalActiveTimeMs += gap;
            } else {
                totalActiveTimeMs += 3000;
            }
            lastAlchTimestamp = now;
        }

        sessionAlchs++;
        sessionMagicXp += diff;

        // Rolling cast timestamps (keep up to 15 recent casts)
        recentCastTimestamps.addLast(now);
        while (recentCastTimestamps.size() > 15) {
            recentCastTimestamps.removeFirst();
        }

        // Immediately recalculate rates on cast completion
        recalculateRates(now);
        scanInventoryBatch(null);

        Map<String, Object> data = new HashMap<>();
        data.put("event", "ALCH_CAST");
        data.put("account", getAccountName());
        data.put("xpGained", diff);
        data.put("magicLevel", magicLevel);
        data.put("totalWealth", getTotalWealth());
        data.put("batchGrossGp", batchGrossGp);
        data.put("profitPerHour", getProfitPerHour());
        data.put("alchsPerHour", getAlchsPerHour());
        data.put("timestamp", now / 1000.0);
        sendPayload(data);
    }

    private void recalculateRates(long now) {
        lastRateUpdateTime = now;

        if (sessionAlchs == 0) {
            cachedAlchsPerHour = 0;
            cachedProfitPerHour = 0;
            cachedXpPerHour = 0;
            return;
        }

        int calculatedRate;
        // If we have at least 4 casts, use the precise rolling interval
        if (recentCastTimestamps.size() >= 4) {
            long spanMs = recentCastTimestamps.peekLast() - recentCastTimestamps.peekFirst();
            if (spanMs > 0) {
                double hours = spanMs / 3600000.0;
                calculatedRate = (int) Math.round((recentCastTimestamps.size() - 1) / hours);
            } else {
                calculatedRate = 1200;
            }
        } else {
            // Apply RuneLite's 60-second floor rule during initial warmup
            long activeSec = getActiveDurationSeconds();
            long effectiveSec = Math.max(60L, activeSec);
            double hours = effectiveSec / 3600.0;
            calculatedRate = (int) Math.round(sessionAlchs / hours);
        }

        // Hard mechanical cap in OSRS: 5 ticks = 3.0s = 1,200 casts/hr max
        cachedAlchsPerHour = Math.min(1200, Math.max(0, calculatedRate));

        // Stable Profit / Hour based strictly on physical alching returns (never corrupted by trade flips)
        long alchProfit = calculateAlchProfit();
        double avgAlchProfit = (sessionAlchs > 0) ? ((double) alchProfit / (double) sessionAlchs) : 0.0;
        cachedProfitPerHour = Math.round(cachedAlchsPerHour * avgAlchProfit);

        // Magic XP / Hour
        cachedXpPerHour = cachedAlchsPerHour * 65;
    }

    @Subscribe
    public void onOverlayMenuClicked(OverlayMenuClicked event) {
        if (event.getOverlay() == overlay) {
            String option = event.getEntry().getOption();
            if ("Reset".equals(option)) {
                resetSession();
            } else if ("Toggle Cost Basis".equals(option)) {
                toggleCostBasisMode();
            } else if ("Toggle Bond Tracker".equals(option)) {
                toggleBondTracker();
            } else if ("Toggle Cushion Tracker".equals(option)) {
                toggleStage2Cushion();
            }
        }
    }

    // Session Metrics Getters
    public int getSessionAlchs() {
        return sessionAlchs;
    }

    public long getSessionProfit() {
        return sessionProfit;
    }

    public int getSessionMagicXp() {
        return sessionMagicXp;
    }

    public String getLastAlchedItem() {
        return lastAlchedItem;
    }

    public CostBasisMode getCostBasisMode() {
        if (activeCostBasisOverride != null) {
            return activeCostBasisOverride;
        }
        return config.costBasisMode();
    }

    public void toggleCostBasisMode() {
        CostBasisMode current = getCostBasisMode();
        CostBasisMode next;
        if (current == CostBasisMode.AUTO_DETECT) {
            next = CostBasisMode.W308_TRADE;
        } else if (current == CostBasisMode.W308_TRADE) {
            next = CostBasisMode.GRAND_EXCHANGE;
        } else {
            next = CostBasisMode.AUTO_DETECT;
        }
        activeCostBasisOverride = next;
        if (configManager != null) {
            try {
                configManager.setConfiguration("alchmate", "costBasisMode", next);
            } catch (Exception e) {
                log.debug("Could not persist cost basis config: {}", e.getMessage());
            }
        }
        sessionProfit = calculateSessionProfit();
        recalculateRates(System.currentTimeMillis());
        scanInventoryBatch(null);
        log.info("Cost basis toggled to: {}", next.getDisplayName());
    }

    public String resolveItemSource(int unnotedId) {
        CostBasisMode mode = getCostBasisMode();
        if (mode == CostBasisMode.W308_TRADE) {
            return "P2P";
        }
        if (mode == CostBasisMode.GRAND_EXCHANGE) {
            return "GE";
        }

        // AUTO_DETECT mode: compare acquisition timestamps
        Long tradeTime = lastW308TradeTimes.get(unnotedId);
        Long geTime = lastGeBuyTimes.get(unnotedId);

        if (tradeTime != null && (geTime == null || tradeTime >= geTime)) {
            return "P2P";
        }
        if (geTime != null) {
            return "GE";
        }

        if (config.manualTradeBuyPrice() > 0) {
            return "P2P";
        }
        if (lastW308TradePrices.containsKey(unnotedId) && !lastGeBuyPrices.containsKey(unnotedId)) {
            return "P2P";
        }
        return "GE";
    }

    public long getItemBuyCost(int unnotedId) {
        String source = resolveItemSource(unnotedId);
        if ("P2P".equals(source)) {
            int manual = config.manualTradeBuyPrice();
            if (manual > 0) return manual;
            Integer tradePrice = lastW308TradePrices.get(unnotedId);
            if (tradePrice != null && tradePrice > 0) return tradePrice;
            Integer geBuy = lastGeBuyPrices.get(unnotedId);
            if (geBuy != null && geBuy > 0) return geBuy;
            Integer livePrice = liveTrackerPrices.get(unnotedId);
            if (livePrice != null && livePrice > 0) return livePrice;
            long p = itemManager.getItemPrice(unnotedId);
            if (p > 0) return p;
        } else {
            Integer geBuy = lastGeBuyPrices.get(unnotedId);
            if (geBuy != null && geBuy > 0) return geBuy;
            Integer livePrice = liveTrackerPrices.get(unnotedId);
            if (livePrice != null && livePrice > 0) return livePrice;
            long p = itemManager.getItemPrice(unnotedId);
            if (p > 0) return p;
            Integer tradePrice = lastW308TradePrices.get(unnotedId);
            if (tradePrice != null && tradePrice > 0) return tradePrice;
        }

        // Safe fallback for alchable items so buy cost is NEVER assumed to be 0 gp
        try {
            ItemComposition comp = itemManager.getItemComposition(unnotedId);
            if (comp != null && comp.getHaPrice() > 1000) {
                return Math.max(1, comp.getHaPrice() - 550);
            }
        } catch (Exception ignored) {}

        return 0;
    }

    public long calculateAlchProfit() {
        long totalBuyCost = 0;
        for (Map.Entry<Integer, Integer> entry : sessionAlchedItemCounts.entrySet()) {
            int id = entry.getKey();
            int count = entry.getValue();
            totalBuyCost += getItemBuyCost(id) * count;
        }
        return (sessionTotalHighAlchGp - sessionTotalNatCost - totalBuyCost) + sessionUnknownItemProfit;
    }

    public long calculateSessionProfit() {
        return calculateAlchProfit() + sessionTradeProfit;
    }

    public long getGeInvestedCoins() {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return 0L;
        }
        try {
            GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
            if (offers == null) {
                return 0L;
            }
            long totalInvested = 0L;
            for (GrandExchangeOffer offer : offers) {
                if (offer == null) {
                    continue;
                }
                GrandExchangeOfferState state = offer.getState();
                if (state == null || state == GrandExchangeOfferState.EMPTY) {
                    continue;
                }

                int itemId = offer.getItemId();
                int totalQty = offer.getTotalQuantity();
                int qtySold = offer.getQuantitySold();
                long price = offer.getPrice();
                long spent = offer.getSpent();

                if (state == GrandExchangeOfferState.BUYING) {
                    long unspentCoins = Math.max(0L, (long) (totalQty - qtySold) * price);
                    long itemVal = 0L;
                    if (qtySold > 0 && itemId > 0) {
                        try {
                            ItemComposition comp = itemManager.getItemComposition(itemId);
                            if (comp != null && comp.getHaPrice() > 0) {
                                itemVal = (long) qtySold * (long) comp.getHaPrice();
                            } else {
                                itemVal = spent;
                            }
                        } catch (Exception ignored) {
                            itemVal = spent;
                        }
                    }
                    long refundCoins = Math.max(0L, ((long) qtySold * price) - spent);
                    totalInvested += (unspentCoins + itemVal + refundCoins);
                } else if (state == GrandExchangeOfferState.BOUGHT) {
                    long itemVal = 0L;
                    if (totalQty > 0 && itemId > 0) {
                        try {
                            ItemComposition comp = itemManager.getItemComposition(itemId);
                            if (comp != null && comp.getHaPrice() > 0) {
                                itemVal = (long) totalQty * (long) comp.getHaPrice();
                            } else {
                                itemVal = spent;
                            }
                        } catch (Exception ignored) {
                            itemVal = spent;
                        }
                    }
                    long refundCoins = Math.max(0L, ((long) totalQty * price) - spent);
                    totalInvested += (itemVal + refundCoins);
                } else if (state == GrandExchangeOfferState.CANCELLED_BUY) {
                    long unspentCoins = Math.max(0L, (long) (totalQty - qtySold) * price);
                    long itemVal = spent;
                    long refundCoins = Math.max(0L, ((long) qtySold * price) - spent);
                    totalInvested += (unspentCoins + itemVal + refundCoins);
                } else if (state == GrandExchangeOfferState.SELLING || state == GrandExchangeOfferState.SOLD || state == GrandExchangeOfferState.CANCELLED_SELL) {
                    if (spent > 0) {
                        totalInvested += spent;
                    }
                }
            }
            return totalInvested;
        } catch (Exception e) {
            log.debug("Error calculating GE invested coins: {}", e.getMessage());
            return 0L;
        }
    }

    public long getTotalWealth() {
        return getTotalCoins() + Math.max(0L, batchGrossGp) + getGeInvestedCoins();
    }

    public int getLastAlchedItemId() {
        return lastAlchedItemId;
    }

    public void toggleOverlay() {
        boolean current = config.showOverlay();
        boolean next = !current;
        if (configManager != null) {
            try {
                configManager.setConfiguration("alchmate", "showOverlay", next);
            } catch (Exception e) {
                log.debug("Could not persist overlay config: {}", e.getMessage());
            }
        }
    }

    public long getBatchGrossGp() {
        return batchGrossGp;
    }

    public long getBatchTotalProfit() {
        return batchTotalProfit;
    }

    public int getActiveW308Price() {
        int manual = config.manualTradeBuyPrice();
        if (manual > 0) {
            return manual;
        }
        if (lastW308TradePrice > 0) {
            return lastW308TradePrice;
        }
        if (lastAlchedItemId > 0 && lastW308TradePrices.containsKey(lastAlchedItemId)) {
            return lastW308TradePrices.get(lastAlchedItemId);
        }
        return 0;
    }

    public String getLastW308TradeItemName() {
        return lastW308TradeItemName;
    }

    public long getTotalCoins() {
        return Math.max(0L, (long) lastCoins) + Math.max(0L, (long) lastBankCoins);
    }

    public String getBatchItemName() {
        return batchItemName;
    }

    public int getBatchItemQty() {
        return batchItemQty;
    }

    public int getBatchDistinctTypes() {
        return batchDistinctTypes;
    }

    public long getBatchEstSeconds() {
        return batchEstSeconds;
    }

    public void scanInventoryBatch(ItemContainer inv) {
        if (client == null) {
            return;
        }
        if (inv == null) {
            clientThread.invokeLater(() -> {
                if (client != null && client.getGameState() == GameState.LOGGED_IN) {
                    ItemContainer liveInv = client.getItemContainer(InventoryID.INVENTORY);
                    if (liveInv != null) {
                        scanInventoryBatch(liveInv);
                    }
                }
            });
            return;
        }
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        detectSkillingFromInventory(inv);

        int natRunes = inv.count(ItemID.NATURE_RUNE);
        String fireSource = checkFireSource(inv);

        int natCost = (int) itemManager.getItemPrice(ItemID.NATURE_RUNE);
        if (natCost <= 0) natCost = 140;

        Map<Integer, BatchItemEntry> entryMap = new LinkedHashMap<>();

        Item[] items = inv.getItems();
        if (items != null) {
            for (Item itm : items) {
                if (itm == null || itm.getId() <= 0 || itm.getQuantity() <= 0) continue;
                int id = itm.getId();
                if (id == ItemID.COINS_995 || id == ItemID.NATURE_RUNE || id == ItemID.FIRE_RUNE) continue;

                ItemComposition comp = itemManager.getItemComposition(id);
                if (comp == null) continue;

                int unnotedId = (comp.getNote() != -1 && comp.getLinkedNoteId() > 0) ? comp.getLinkedNoteId() : id;
                ItemComposition unnotedComp = (unnotedId != id) ? itemManager.getItemComposition(unnotedId) : comp;
                if (unnotedComp == null) continue;

                String name = unnotedComp.getName();
                if (name == null) continue;
                String lower = name.toLowerCase();
                // Runes and pouches cannot be alched in OSRS
                if (lower.endsWith(" rune") || lower.endsWith(" runes") || lower.contains("rune pouch")) {
                    continue;
                }

                int haPrice = unnotedComp.getHaPrice();
                if (haPrice <= 0) continue;

                int qty = itm.getQuantity();
                boolean isNoted = (comp.getNote() != -1);
                int buyCost = (int) getItemBuyCost(unnotedId);
                int profitEa = (buyCost > 0) ? (haPrice - buyCost - natCost) : (haPrice - natCost);
                long itemProfit = (long) profitEa * (long) qty;
                long itemGross = (long) haPrice * (long) qty;

                if (entryMap.containsKey(unnotedId)) {
                    BatchItemEntry existing = entryMap.get(unnotedId);
                    existing.qty += qty;
                    existing.totalProfit += itemProfit;
                    existing.grossGp += itemGross;
                } else {
                    entryMap.put(unnotedId, new BatchItemEntry(
                        unnotedId, name, qty, isNoted, haPrice, buyCost, profitEa, itemProfit, itemGross
                    ));
                }
            }
        }

        List<BatchItemEntry> entries = new ArrayList<>(entryMap.values());
        entries.sort((a, b) -> Integer.compare(b.qty, a.qty));

        int totalQty = 0;
        long totalProfit = 0;
        long totalGross = 0;
        for (BatchItemEntry e : entries) {
            totalQty += e.qty;
            totalProfit += e.totalProfit;
            totalGross += e.grossGp;
        }

        int distinctCount = entries.size();
        double rate = (cachedAlchsPerHour > 600) ? (double) cachedAlchsPerHour : 1200.0;
        long estSecs = totalQty > 0 ? Math.round((totalQty / rate) * 3600.0) : 0;

        this.batchItemQty = totalQty;
        this.batchDistinctTypes = distinctCount;
        this.batchEstSeconds = estSecs;
        this.batchTotalProfit = totalProfit;
        this.batchGrossGp = totalGross;
        this.batchTotalXp = (long) totalQty * 65L;
        this.batchNatureRunes = natRunes;
        this.batchFireSource = fireSource;

        if (distinctCount == 0) {
            this.batchItemName = null;
            this.batchIsNoted = false;
            this.batchHaPrice = 0;
            this.batchBuyCost = 0;
            this.batchProfitEa = 0;
        } else if (distinctCount == 1) {
            BatchItemEntry single = entries.get(0);
            this.batchItemName = single.name;
            this.batchIsNoted = single.isNoted;
            this.batchHaPrice = single.haPrice;
            this.batchBuyCost = single.buyCost;
            this.batchProfitEa = single.profitEa;
        } else {
            this.batchItemName = String.format("Alchables (%d types)", distinctCount);
            this.batchIsNoted = false;
            this.batchHaPrice = totalQty > 0 ? (int) Math.round((double) totalGross / (double) totalQty) : 0;
            this.batchBuyCost = 0;
            this.batchProfitEa = totalQty > 0 ? (int) Math.round((double) totalProfit / (double) totalQty) : 0;
        }

        if (panel != null) {
            panel.updateInventoryBatch(
                entries, totalQty, totalProfit, totalGross,
                estSecs, natRunes, fireSource, cachedAlchsPerHour
            );
            if (isBondTrackerEnabled()) {
                panel.updateBondFreedom(
                    getTotalWealth(),
                    getBondPrice(),
                    getCushionAmount(),
                    isStage2Enabled(),
                    getProfitPerHour()
                );
            }
            if (detectedSkill != null) {
                int curXp = client.getSkillExperience(detectedSkill);
                int curLvl = client.getRealSkillLevel(detectedSkill);
                int nextLvlXp = getXpForLevel(Math.min(99, curLvl + 1));
                int remXp = Math.max(0, nextLvlXp - curXp);
                long skillingEstSecs = (liveSkillingXpHr > 1000) ? (long) Math.round((remXp / liveSkillingXpHr) * 3600.0) : 0;
                panel.updateSkillingProgression(
                    detectedSkill.getName(),
                    detectedActivityName,
                    curLvl,
                    Math.min(99, curLvl + 1),
                    remXp,
                    liveSkillingXpHr,
                    skillingEstSecs
                );
            }
        }
    }

    private String checkFireSource(ItemContainer inv) {
        if (client == null) return "--";
        ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);
        if (equip != null) {
            Item[] eqItems = equip.getItems();
            if (eqItems != null) {
                for (Item itm : eqItems) {
                    if (itm != null && itm.getId() > 0) {
                        ItemComposition comp = itemManager.getItemComposition(itm.getId());
                        if (comp != null) {
                            String name = comp.getName().toLowerCase();
                            if (name.contains("fire staff") || name.contains("staff of fire") ||
                                name.contains("fire battlestaff") || name.contains("mystic fire") ||
                                name.contains("lava") || name.contains("smoke") || name.contains("steam") ||
                                name.contains("tome of fire") || name.contains("bryophyta")) {
                                return "Staff [OK]";
                            }
                        }
                    }
                }
            }
        }

        if (inv != null) {
            int fireRunes = inv.count(ItemID.FIRE_RUNE);
            if (fireRunes >= 5) {
                return String.format("%,d Fire [OK]", fireRunes);
            }
        }

        return "Missing [!]";
    }

    public long getBondPrice() {
        long price = itemManager.getItemPrice(ItemID.OLD_SCHOOL_BOND);
        if (price <= 0) {
            price = 11800000L;
        }
        return price;
    }

    public boolean isBondTrackerEnabled() {
        if (bondTrackerOverride != null) {
            return bondTrackerOverride;
        }
        return config.showBondTracker();
    }

    public void toggleBondTracker() {
        boolean next = !isBondTrackerEnabled();
        bondTrackerOverride = next;
        if (configManager != null) {
            try {
                configManager.setConfiguration("alchmate", "showBondTracker", next);
            } catch (Exception e) {
                log.debug("Could not persist bond tracker config: {}", e.getMessage());
            }
        }
        log.info("Bond tracker toggled to: {}", next);
    }

    public long getCushionAmount() {
        if (syncedCushionAmount > 0) {
            return syncedCushionAmount;
        }
        return (long) config.cushionAmount();
    }

    public void setSyncedCushionAmount(long amount) {
        if (amount > 0 && this.syncedCushionAmount != amount) {
            this.syncedCushionAmount = amount;
            log.debug("Synced bond cushion updated from desktop app: {} gp", amount);
        }
    }

    public boolean isStage2Enabled() {
        if (stage2CushionOverride != null) {
            return stage2CushionOverride;
        }
        return config.showStage2Cushion();
    }

    public long getTotalFreedomGoal() {
        return getBondPrice() + (isStage2Enabled() ? getCushionAmount() : 0);
    }

    public boolean isStage1Complete() {
        return getTotalWealth() >= getBondPrice();
    }

    public boolean isFreedomAchieved() {
        return getTotalWealth() >= getTotalFreedomGoal();
    }

    public void toggleStage2Cushion() {
        boolean next = !isStage2Enabled();
        stage2CushionOverride = next;
        if (configManager != null) {
            try {
                configManager.setConfiguration("alchmate", "showStage2Cushion", next);
            } catch (Exception e) {
                log.debug("Could not persist stage 2 cushion config: {}", e.getMessage());
            }
        }
        log.info("Stage 2 cushion tracker toggled to: {}", next);
    }

    public long getSessionNetGpFlow() {
        if (sessionStartWealth <= 0) {
            return sessionProfit;
        }
        return getTotalCoins() - sessionStartWealth;
    }

    private void commitPendingTrade() {
        if (System.currentTimeMillis() - lastTradeActivityTime > 120000) {
            clearPendingTrade();
            return;
        }

        // Scenario 1: BUY TRADE (Player gave coins, got items)
        if (pendingTradeCoinsOffered > 0 && !pendingTradeItemsReceived.isEmpty()) {
            int totalCoins = pendingTradeCoinsOffered;
            int itemCount = pendingTradeItemsReceived.size();

            if (itemCount == 1) {
                Map.Entry<Integer, Integer> entry = pendingTradeItemsReceived.entrySet().iterator().next();
                int unnotedId = entry.getKey();
                int qty = entry.getValue();
                if (qty > 0) {
                    int unitCost = (int) Math.round((double) totalCoins / (double) qty);
                    lastW308TradePrices.put(unnotedId, unitCost);
                    lastW308TradeTimes.put(unnotedId, System.currentTimeMillis());
                    lastW308TradePrice = unitCost;
                    ItemComposition comp = itemManager.getItemComposition(unnotedId);
                    lastW308TradeItemName = comp != null ? comp.getName() : ("Item " + unnotedId);

                    log.info("W308/P2P Buy Trade detected: {} x {} for {} coins ({} ea)", lastW308TradeItemName, qty, totalCoins, unitCost);

                    Map<String, Object> data = new HashMap<>();
                    data.put("event", "TRADE_ACCEPTED");
                    data.put("account", getAccountName());
                    data.put("itemId", unnotedId);
                    data.put("itemName", lastW308TradeItemName);
                    data.put("quantity", qty);
                    data.put("unitPrice", unitCost);
                    data.put("totalCoins", totalCoins);
                    data.put("timestamp", System.currentTimeMillis() / 1000.0);
                    sendPayload(data);
                }
            } else {
                long totalEstValue = 0;
                for (Map.Entry<Integer, Integer> entry : pendingTradeItemsReceived.entrySet()) {
                    int unnotedId = entry.getKey();
                    int qty = entry.getValue();
                    long price = Math.max(1, itemManager.getItemPrice(unnotedId));
                    totalEstValue += price * qty;
                }

                for (Map.Entry<Integer, Integer> entry : pendingTradeItemsReceived.entrySet()) {
                    int unnotedId = entry.getKey();
                    int qty = entry.getValue();
                    long price = Math.max(1, itemManager.getItemPrice(unnotedId));
                    double proportion = totalEstValue > 0 ? ((double) (price * qty) / (double) totalEstValue) : (1.0 / itemCount);
                    int allocatedCoins = (int) Math.round(totalCoins * proportion);
                    int unitCost = qty > 0 ? (int) Math.round((double) allocatedCoins / (double) qty) : 0;

                    lastW308TradePrices.put(unnotedId, unitCost);
                    lastW308TradeTimes.put(unnotedId, System.currentTimeMillis());
                    lastW308TradePrice = unitCost;
                    ItemComposition comp = itemManager.getItemComposition(unnotedId);
                    lastW308TradeItemName = comp != null ? comp.getName() : ("Item " + unnotedId);

                    log.info("W308/P2P Multi-Buy detected: {} x {} for {} coins ({} ea)", lastW308TradeItemName, qty, allocatedCoins, unitCost);
                }
            }
        }
        // Scenario 2: SELL TRADE (Player gave items, received coins)
        else if (pendingTradeCoinsReceived > 0 && !pendingTradeItemsOffered.isEmpty()) {
            int totalCoins = pendingTradeCoinsReceived;
            int itemCount = pendingTradeItemsOffered.size();

            if (itemCount == 1) {
                Map.Entry<Integer, Integer> entry = pendingTradeItemsOffered.entrySet().iterator().next();
                int unnotedId = entry.getKey();
                int qty = entry.getValue();
                if (qty > 0) {
                    int unitSellPrice = (int) Math.round((double) totalCoins / (double) qty);
                    long buyCost = getItemBuyCost(unnotedId);
                    if (buyCost <= 0) {
                        buyCost = Math.max(1, unitSellPrice - 100);
                    }
                    long flipProfit = (unitSellPrice - buyCost) * qty;
                    sessionTradeProfit += flipProfit;
                    sessionProfit = calculateSessionProfit();

                    ItemComposition comp = itemManager.getItemComposition(unnotedId);
                    String itemName = comp != null ? comp.getName() : ("Item " + unnotedId);
                    lastAlchedItem = String.format("Sold %s (%+d gp)", itemName, flipProfit);

                    log.info("W308/P2P Sell Trade detected: Sold {} x {} @ {} gp ea (Profit: %+d gp)", itemName, qty, unitSellPrice, flipProfit);

                    Map<String, Object> data = new HashMap<>();
                    data.put("event", "TRADE_SOLD");
                    data.put("account", getAccountName());
                    data.put("itemId", unnotedId);
                    data.put("itemName", itemName);
                    data.put("quantity", qty);
                    data.put("unitPrice", unitSellPrice);
                    data.put("buyPrice", buyCost);
                    data.put("profit", flipProfit);
                    data.put("totalCoins", totalCoins);
                    data.put("timestamp", System.currentTimeMillis() / 1000.0);
                    sendPayload(data);
                }
            } else {
                long totalEstValue = 0;
                for (Map.Entry<Integer, Integer> entry : pendingTradeItemsOffered.entrySet()) {
                    int unnotedId = entry.getKey();
                    int qty = entry.getValue();
                    long price = Math.max(1, itemManager.getItemPrice(unnotedId));
                    totalEstValue += price * qty;
                }

                for (Map.Entry<Integer, Integer> entry : pendingTradeItemsOffered.entrySet()) {
                    int unnotedId = entry.getKey();
                    int qty = entry.getValue();
                    long price = Math.max(1, itemManager.getItemPrice(unnotedId));
                    double proportion = totalEstValue > 0 ? ((double) (price * qty) / (double) totalEstValue) : (1.0 / itemCount);
                    int allocatedCoins = (int) Math.round(totalCoins * proportion);
                    int unitSellPrice = qty > 0 ? (int) Math.round((double) allocatedCoins / (double) qty) : 0;
                    long buyCost = getItemBuyCost(unnotedId);
                    if (buyCost <= 0) {
                        buyCost = Math.max(1, unitSellPrice - 100);
                    }
                    long flipProfit = (unitSellPrice - buyCost) * qty;
                    sessionTradeProfit += flipProfit;

                    ItemComposition comp = itemManager.getItemComposition(unnotedId);
                    String itemName = comp != null ? comp.getName() : ("Item " + unnotedId);

                    log.info("W308/P2P Multi-Sell: Sold {} x {} @ {} gp ea (Profit: %+d gp)", itemName, qty, unitSellPrice, flipProfit);
                }
                sessionProfit = calculateSessionProfit();
            }
        }

        clearPendingTrade();
    }

    private void clearPendingTrade() {
        pendingTradeCoinsOffered = 0;
        pendingTradeCoinsReceived = 0;
        pendingTradeItemsOffered.clear();
        pendingTradeItemsReceived.clear();
        lastTradeActivityTime = 0;
    }

    public boolean isSessionActive() {
        if (sessionAlchs == 0 || lastAlchTimestamp == 0) {
            return false;
        }
        return (System.currentTimeMillis() - lastAlchTimestamp) <= 15000;
    }

    public long getActiveDurationSeconds() {
        if (sessionAlchs == 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long extra = (now - lastAlchTimestamp <= 15000) ? (now - lastAlchTimestamp) : 0;
        return (totalActiveTimeMs + extra) / 1000L;
    }

    public String getFormattedSessionTime() {
        long secs = getActiveDurationSeconds();
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    public int getAlchsPerHour() {
        long now = System.currentTimeMillis();
        if (now - lastRateUpdateTime >= 2000) {
            recalculateRates(now);
        }
        return cachedAlchsPerHour;
    }

    public long getProfitPerHour() {
        return cachedProfitPerHour;
    }

    public int getXpPerHour() {
        return cachedXpPerHour;
    }

    public void resetSession() {
        sessionAlchs = 0;
        sessionProfit = 0;
        sessionMagicXp = 0;
        sessionStartTime = 0;
        lastAlchTimestamp = 0;
        totalActiveTimeMs = 0;
        lastAlchedItem = null;
        lastAlchedItemId = -1;
        sessionAlchedItemCounts.clear();
        sessionTotalHighAlchGp = 0;
        sessionTotalNatCost = 0;
        sessionUnknownItemProfit = 0;
        sessionTradeProfit = 0;
        sessionStartWealth = getTotalCoins();
        recentCastTimestamps.clear();
        cachedAlchsPerHour = 0;
        cachedProfitPerHour = 0;
        cachedXpPerHour = 0;
        lastRateUpdateTime = 0;
        batchDistinctTypes = 0;
        detectedSkill = null;
        detectedActivityName = "";
        liveSkillingXpHr = 0.0;
        lastSkillingDropTime = 0;
        recentSkillingXpDrops.clear();
        scanInventoryBatch(null);
        log.info("Alch session stats reset");
    }

    private void sendAccountSnapshot() {
        if (client == null) {
            return;
        }
        clientThread.invokeLater(() -> {
            if (client == null || client.getGameState() != GameState.LOGGED_IN) {
                return;
            }
            try {
                ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
                if (inv != null) {
                    detectSkillingFromInventory(inv);
                    scanInventoryBatch(inv);
                } else if (panel != null && isBondTrackerEnabled()) {
                    panel.updateBondFreedom(
                        getTotalWealth(),
                        getBondPrice(),
                        getCushionAmount(),
                        isStage2Enabled(),
                        getProfitPerHour()
                    );
                }

                lastSkillXpMap.put(Skill.CRAFTING, client.getSkillExperience(Skill.CRAFTING));
                lastSkillXpMap.put(Skill.SMITHING, client.getSkillExperience(Skill.SMITHING));
                lastSkillXpMap.put(Skill.FLETCHING, client.getSkillExperience(Skill.FLETCHING));
                lastSkillXpMap.put(Skill.MAGIC, client.getSkillExperience(Skill.MAGIC));

                Map<String, Object> data = new HashMap<>();
                data.put("event", "ACCOUNT_SNAPSHOT");
                data.put("account", getAccountName());
                data.put("world", client.getWorld());
                data.put("isMembers", isMembersWorld());
                data.put("crafting", client.getRealSkillLevel(Skill.CRAFTING));
                data.put("smithing", client.getRealSkillLevel(Skill.SMITHING));
                data.put("fletching", client.getRealSkillLevel(Skill.FLETCHING));
                data.put("magic", client.getRealSkillLevel(Skill.MAGIC));
                data.put("crafting_xp", client.getSkillExperience(Skill.CRAFTING));
                data.put("smithing_xp", client.getSkillExperience(Skill.SMITHING));
                data.put("fletching_xp", client.getSkillExperience(Skill.FLETCHING));
                data.put("magic_xp", client.getSkillExperience(Skill.MAGIC));

                if (detectedSkill != null) {
                    int curXp = client.getSkillExperience(detectedSkill);
                    int curLvl = client.getRealSkillLevel(detectedSkill);
                    int nextLvlXp = getXpForLevel(Math.min(99, curLvl + 1));
                    int remXp = Math.max(0, nextLvlXp - curXp);
                    int estSecs = (liveSkillingXpHr > 1000) ? (int) Math.round((remXp / liveSkillingXpHr) * 3600.0) : 0;

                    data.put("active_skill", detectedSkill.getName());
                    data.put("detected_activity", detectedActivityName != null ? detectedActivityName : "");
                    data.put("skilling_xp_hr", (int) Math.round(liveSkillingXpHr));
                    data.put("rem_xp_next", remXp);
                    data.put("est_secs_next", estSecs);
                }

                if (inv != null) {
                    data.put("coins", inv.count(ItemID.COINS_995));
                    data.put("natureRunes", inv.count(ItemID.NATURE_RUNE));
                }
                data.put("bankCoins", lastBankCoins);
                data.put("totalWealth", getTotalWealth());
                data.put("geInvestedCoins", getGeInvestedCoins());
                data.put("batchGrossGp", batchGrossGp);
                data.put("batchItemName", batchItemName != null ? batchItemName : "");
                data.put("batchItemQty", batchItemQty);
                data.put("profitPerHour", getProfitPerHour());
                data.put("alchsPerHour", getAlchsPerHour());
                data.put("timestamp", System.currentTimeMillis() / 1000.0);

                sendPayload(data);
                syncActiveGeOffers();
                syncGrandExchangeLimitsAndTrades();
            } catch (Exception e) {
                log.debug("Error preparing account snapshot: {}", e.getMessage());
            }
        });
    }

    private void syncGrandExchangeLimitsAndTrades() {
        if (configManager == null) {
            return;
        }
        try {
            String profileKey = configManager.getRSProfileKey();
            if (profileKey == null) {
                return;
            }

            String tradeHistoryJson = configManager.getRSProfileConfiguration("grandexchange", "tradeHistory");
            List<String> buylimitKeys = configManager.getRSProfileConfigurationKeys("grandexchange", profileKey, "buylimit");
            Map<String, String> limits = new HashMap<>();
            if (buylimitKeys != null) {
                for (String k : buylimitKeys) {
                    String val = configManager.getConfiguration("grandexchange", profileKey, k);
                    if (val != null) {
                        String iid = k.substring(k.lastIndexOf('.') + 1);
                        limits.put(iid, val);
                    }
                }
            }

            Map<String, Object> data = new HashMap<>();
            data.put("event", "GE_SYNC");
            data.put("account", getAccountName());
            data.put("buylimits", limits);
            if (tradeHistoryJson != null && !tradeHistoryJson.isEmpty()) {
                Type listType = new TypeToken<List<Map<String, Object>>>(){}.getType();
                List<Map<String, Object>> trades = gson.fromJson(tradeHistoryJson, listType);
                data.put("tradeHistory", trades);
            }
            data.put("timestamp", System.currentTimeMillis() / 1000.0);
            sendPayload(data);
        } catch (Exception e) {
            log.debug("Error syncing GE limits and trades: {}", e.getMessage());
        }
    }

    private void syncActiveGeOffers() {
        try {
            GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
            if (offers == null) {
                return;
            }
            for (int slot = 0; slot < offers.length; slot++) {
                GrandExchangeOffer offer = offers[slot];
                if (offer == null) {
                    continue;
                }
                GrandExchangeOfferState state = offer.getState();
                if (state == GrandExchangeOfferState.BUYING) {
                    int itemId = offer.getItemId();
                    if (itemId <= 0) {
                        continue;
                    }
                    String itemName = itemManager.getItemComposition(itemId).getName();
                    Map<String, Object> data = new HashMap<>();
                    data.put("event", "GE_OFFER");
                    data.put("account", getAccountName());
                    data.put("slot", slot);
                    data.put("state", "BUYING");
                    data.put("itemId", itemId);
                    data.put("itemName", itemName);
                    data.put("price", offer.getPrice());
                    data.put("spent", offer.getSpent());
                    data.put("quantitySold", offer.getQuantitySold());
                    data.put("totalQuantity", offer.getTotalQuantity());
                    data.put("timestamp", System.currentTimeMillis() / 1000.0);
                    sendPayload(data);
                }
            }
        } catch (Exception e) {
            log.debug("Error syncing active GE offers: {}", e.getMessage());
        }
    }

    private void sendPayload(Map<String, Object> data) {
        if (httpClient == null) {
            return;
        }

        try {
            String json = gson.toJson(data);
            String url = "http://127.0.0.1:" + config.bridgePort() + "/api/event";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(1200))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp != null && resp.statusCode() == 200 && resp.body() != null) {
                        try {
                            Type type = new TypeToken<Map<String, Object>>(){}.getType();
                            Map<String, Object> map = gson.fromJson(resp.body(), type);
                            if (map != null) {
                                if (map.containsKey("bond_cushion")) {
                                    Object cObj = map.get("bond_cushion");
                                    if (cObj instanceof Number) {
                                        long cushion = ((Number) cObj).longValue();
                                        if (cushion > 0) {
                                            setSyncedCushionAmount(cushion);
                                        }
                                    }
                                }
                                if (Boolean.TRUE.equals(map.get("request_resync"))) {
                                    sendAccountSnapshot();
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                })
                .exceptionally(ex -> null); // Non-blocking: quietly drop if dashboard is closed
        } catch (Exception ignored) {
        }
    }

    public boolean isSkillingActive() {
        if (detectedSkill == null) return false;
        long now = System.currentTimeMillis();
        return (now - lastSkillingDropTime < 180000L);
    }

    public Skill getDetectedSkill() {
        return detectedSkill;
    }

    public String getDetectedActivityName() {
        return detectedActivityName != null ? detectedActivityName : "";
    }

    public double getLiveSkillingXpHr() {
        return liveSkillingXpHr;
    }

    public long getSkillingRemSecsNext() {
        if (detectedSkill == null || liveSkillingXpHr < 1000) return 0;
        int curXp = client.getSkillExperience(detectedSkill);
        int curLvl = client.getRealSkillLevel(detectedSkill);
        int nextLvlXp = getXpForLevel(Math.min(99, curLvl + 1));
        int remXp = Math.max(0, nextLvlXp - curXp);
        return (long) Math.round((remXp / liveSkillingXpHr) * 3600.0);
    }
}

