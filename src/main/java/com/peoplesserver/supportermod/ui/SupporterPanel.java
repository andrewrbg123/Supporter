package com.peoplesserver.supportermod.ui;

import au.ellie.hyui.builders.CustomButtonBuilder;
import au.ellie.hyui.builders.GroupBuilder;
import au.ellie.hyui.builders.HyUIAnchor;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.SceneBlurBuilder;
import au.ellie.hyui.builders.TabContentBuilder;
import au.ellie.hyui.builders.TabNavigationBuilder;
import au.ellie.hyui.builders.UIElementBuilder;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.peoplesserver.supportermod.SupporterPlugin;
import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.core.SupporterRecord;
import com.peoplesserver.supportermod.core.SupporterService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code /supporter} panel: one window with tabs, rather than a panel per subcommand.
 *
 * <p><b>Every chat command still works.</b> This is an alternative front end, never a replacement:
 * Tebex delivers purchases from the console, which cannot open a UI, and HyUI is an optional
 * dependency that may not be present at all. Anything that goes wrong here falls back to the chat
 * output that has been shipping since Phase 1 — a player must never be refused because a cosmetic
 * panel failed to build.
 *
 * <p>Two constraints inherited from FactionMod's UI work on this same server, both learned the
 * hard way and both load-bearing:
 *
 * <ul>
 *   <li><b>{@code addChild} is immutable.</b> It returns a new builder, so discarding the return
 *       silently drops the child. Every call here reassigns.
 *   <li><b>The root id is fixed</b>, not per-tab. A changing root id stopped HyUI's delta diff
 *       reconciling between renders, and navigation quietly stopped working.
 * </ul>
 *
 * <p>Tab content is bound by id — {@link TabContentBuilder#withTabNavigationId} pointing at the
 * navigation element — rather than nested inside the tab bar. That also keeps interactive elements
 * shallow: FactionMod found buttons nested deep inside groups did not reliably receive clicks on
 * an earlier HyUI, and staying flat costs nothing.
 */
public final class SupporterPanel {

    private static final String ROOT_ID = "SupporterRoot";
    private static final String TABS_ID = "SupporterTabs";

    private static final int WIDTH = 720;
    private static final int HEIGHT = 520;
    private static final int PAD = 24;
    /**
     * Row pitch. Must exceed BUY_HEIGHT, or the Buy buttons on consecutive rows overlap - the
     * first build had 24 against a 26-tall button and they visibly touched.
     */
    private static final int ROW_HEIGHT = 30;

    /** Tab hit area. Text-only tabs were too small a target on the first live build. */
    private static final int TAB_WIDTH = 150;
    private static final int TAB_HEIGHT = 36;
    private static final int TAB_SPACING = 12;

    /** Buy button, sized to sit on the right of a shop row. */
    private static final int BUY_WIDTH = 96;
    private static final int BUY_HEIGHT = 26;

    private static final String BALANCE_ID = "SupBalance";
    private static final String NOTICE_ID = "SupNotice";
    private static final String STATUS_TOKENS_ID = "StTokens";
    private static final String STATUS_UNLOCKS_ID = "StUnlocks";

    private final SupporterPlugin plugin;
    private final SupporterTheme theme;

    public SupporterPanel(SupporterPlugin plugin) {
        this.plugin = plugin;
        this.theme = new SupporterTheme(plugin.config().tagColorHex());
    }

    /**
     * Builds and opens the panel.
     *
     * @return true if it opened; false means the caller should fall back to chat
     */
    public boolean open(PlayerRef playerRef, Store<EntityStore> store, World world) {
        try {
            UUID uuid = playerRef.getUuid();
            SupporterService service = plugin.service();
            if (service == null) {
                return false;
            }

            GroupBuilder root = (GroupBuilder) GroupBuilder.group()
                    .withId(ROOT_ID)
                    .withAnchor(new HyUIAnchor().setTop(110).setWidth(WIDTH).setHeight(HEIGHT))
                    .withBackground(theme.panelBackground());

            root = (GroupBuilder) root.addChild(SceneBlurBuilder.sceneBlur().withId("SupBlur"));
            root = (GroupBuilder) root.addChild(header(service, uuid));
            root = (GroupBuilder) root.addChild(tabBar());
            root = (GroupBuilder) root.addChild(statusTab(service, uuid));
            root = (GroupBuilder) root.addChild(perksTab(service, uuid));
            root = (GroupBuilder) root.addChild(shopTab(service, uuid, world));
            root = (GroupBuilder) root.addChild(notice());
            root = (GroupBuilder) root.addChild(aboutTab());

            PageBuilder page = (PageBuilder) PageBuilder.pageForPlayer(playerRef).addElement(root);
            page.open(store);
            return true;
        } catch (Throwable t) {
            // Throwable, not Exception: a missing HyUI arrives as NoClassDefFoundError, and that
            // has to degrade to chat exactly like any other failure rather than kill the command.
            plugin.log().warn("Supporter panel failed to open, falling back to chat: " + t);
            return false;
        }
    }

    // --- chrome ---------------------------------------------------------------------------

    private UIElementBuilder<?> header(SupporterService service, UUID uuid) {
        GroupBuilder header = (GroupBuilder) GroupBuilder.group()
                .withId("SupHeader")
                .withAnchor(new HyUIAnchor().setTop(0).setLeft(0).setWidth(WIDTH).setHeight(62))
                .withBackground(theme.headerBackground());

        header = (GroupBuilder) header.addChild(text("SupTitle", "Supporter",
                theme.title(), 12, PAD, WIDTH - (PAD * 2), 30));
        header = (GroupBuilder) header.addChild(text("SupSubtitle", subtitle(service, uuid),
                theme.dim(), 40, PAD, WIDTH - (PAD * 2), 18));
        return header;
    }

    private String subtitle(SupporterService service, UUID uuid) {
        Optional<SupporterRecord> record = service.get(uuid);
        if (record.isEmpty()) {
            return "Cosmetic perks only - nothing here affects gameplay";
        }
        return service.status(uuid) + " - " + service.daysRemaining(uuid) + " day(s) remaining";
    }

    /**
     * The tab strip.
     *
     * <p>Each tab supplies its own {@link CustomButtonBuilder} rather than relying on the default
     * text-only tab. The first live build used plain labels and they were hard to hit: the target
     * was the width of the word itself, with no visible edge telling you where to aim. A button
     * gives a {@value #TAB_WIDTH}×{@value #TAB_HEIGHT} target with hover and pressed art, so the
     * tab looks clickable and behaves like every other button in the game.
     */
    private UIElementBuilder<?> tabBar() {
        return TabNavigationBuilder.tabNavigation()
                .withSelectedTab("status")
                .withSelectedTabStyle(theme.selectedTab())
                .withUnselectedTabStyle(theme.unselectedTab())
                .withTabSpacing(TAB_SPACING)
                .addTab("status", "Status", tabButton("status"))
                .addTab("perks", "Perks", tabButton("perks"))
                .addTab("shop", "Shop", tabButton("shop"))
                .addTab("about", "About", tabButton("about"))
                .withId(TABS_ID)
                .withAnchor(new HyUIAnchor().setTop(62).setLeft(PAD)
                        .setWidth(WIDTH - (PAD * 2)).setHeight(TAB_HEIGHT));
    }

    private CustomButtonBuilder tabButton(String tabId) {
        CustomButtonBuilder button = (CustomButtonBuilder) CustomButtonBuilder.customTextButton()
                .withId("SupTab_" + tabId)
                .withAnchor(new HyUIAnchor().setWidth(TAB_WIDTH).setHeight(TAB_HEIGHT));
        return button
                .withDefaultBackground(theme.tabBackground())
                .withHoveredBackground(theme.tabHovered())
                .withPressedBackground(theme.tabPressed());
    }

    /**
     * A single line at the bottom that purchases write their result into.
     *
     * <p>It sits outside the tab content and starts empty. A purchase has to say something — a
     * button that silently succeeds is indistinguishable from a button that silently failed — and
     * chat is the wrong place for it when the player is looking at a panel.
     */
    private LabelBuilder notice() {
        return text(NOTICE_ID, "", theme.dim(),
                HEIGHT - 40, PAD, WIDTH - (PAD * 2), 22);
    }

    // --- tabs -----------------------------------------------------------------------------

    private TabContentBuilder content(String tabId) {
        return (TabContentBuilder) TabContentBuilder.tabContent()
                .withTabNavigationId(TABS_ID)
                .withTabId(tabId)
                .withId("SupContent_" + tabId)
                .withAnchor(new HyUIAnchor().setTop(118).setLeft(PAD)
                        .setWidth(WIDTH - (PAD * 2)).setHeight(HEIGHT - 150));
    }

    private UIElementBuilder<?> statusTab(SupporterService service, UUID uuid) {
        TabContentBuilder tab = content("status");
        Optional<SupporterRecord> record = service.get(uuid);
        int row = 0;

        if (record.isEmpty()) {
            tab = (TabContentBuilder) tab.addChild(line("StNone",
                    "You are not a supporter yet.", theme.body(), row++));
            tab = (TabContentBuilder) tab.addChild(line("StNone2",
                    "The About tab covers what it includes and what it costs.",
                    theme.dim(), row));
            return tab;
        }

        SupporterRecord r = record.get();
        tab = (TabContentBuilder) tab.addChild(line("StState",
                "Status: " + service.status(uuid),
                theme.coloured(service.isSupporter(uuid)
                        ? SupporterTheme.INK_GOOD : SupporterTheme.INK_LOCKED, true), row++));
        tab = (TabContentBuilder) tab.addChild(line("StDays",
                service.daysRemaining(uuid) + " day(s) remaining", theme.body(), row++));
        tab = (TabContentBuilder) tab.addChild(line("StTotal",
                r.totalDays() + " day(s) total, all time", theme.dim(), row + 1));
        row += 3;
        tab = (TabContentBuilder) tab.addChild(line(STATUS_TOKENS_ID,
                balanceText(service, uuid), theme.body(), row++));
        tab = (TabContentBuilder) tab.addChild(line(STATUS_UNLOCKS_ID,
                unlocksText(service, uuid), theme.dim(), row));
        return tab;
    }

    /**
     * Name the trails, do not count them.
     *
     * <p>"1 unlock(s) owned" was the first thing anybody asked about after seeing this panel,
     * which is a fair sign that a bare count answers nothing.
     */
    private String unlocksText(SupporterService service, UUID uuid) {
        List<String> unlocks = service.unlocks(uuid);
        return unlocks.isEmpty()
                ? "No trails bought yet - the free ones are yours already"
                : "Trails bought: " + String.join(", ", unlocks);
    }

    private UIElementBuilder<?> perksTab(SupporterService service, UUID uuid) {
        SupporterConfig config = plugin.config();
        TabContentBuilder tab = content("perks");
        int row = 0;

        tab = (TabContentBuilder) tab.addChild(line("PkChat",
                "Chat tag, your own title and colour", theme.body(), row++));
        tab = (TabContentBuilder) tab.addChild(line("PkTrails",
                "Particle trails - " + config.trails().size() + " to choose from",
                theme.body(), row++));
        tab = (TabContentBuilder) tab.addChild(line("PkHomes",
                "Homes: " + config.supporterHomeSlots()
                        + " instead of " + config.defaultHomeSlots(), theme.body(), row++));
        tab = (TabContentBuilder) tab.addChild(line("PkTokens",
                config.tokensPerMonth() + " tokens a month, spent in the shop",
                theme.body(), row));
        row += 2;
        tab = (TabContentBuilder) tab.addChild(line("PkYou",
                service.isSupporter(uuid)
                        ? "You have these now - thank you."
                        : "You do not have these yet.",
                theme.dim(), row));
        return tab;
    }

    /**
     * The shop: every trail, what it costs, and a button that buys it.
     *
     * <p>Buying happens in place. The button callback runs the purchase and then rewrites just the
     * affected labels through {@link au.ellie.hyui.events.UIContext#editById}, rather than closing
     * and reopening the panel — a page is a snapshot, so without this the numbers would sit there
     * stale until the player reopened it and it would look as though nothing happened.
     *
     * <p><b>The update is handed to the world thread.</b> {@code updatePage} reads a {@code Player}
     * component off the store, and component reads are world-thread only. The click callback
     * arrives on a network thread, so it does the SQLite work (safe from anywhere, the service is
     * synchronized) and then hands the UI half to {@code world.execute}. This is the same split
     * the trail system uses.
     */
    private UIElementBuilder<?> shopTab(SupporterService service, UUID uuid, World world) {
        SupporterConfig config = plugin.config();
        TabContentBuilder tab = content("shop");
        int row = 0;

        tab = (TabContentBuilder) tab.addChild(line(BALANCE_ID,
                balanceText(service, uuid), theme.heading(), row));
        row += 2;

        if (!service.isSupporter(uuid)) {
            tab = (TabContentBuilder) tab.addChild(line("ShopLocked",
                    "The shop is for supporters. The About tab explains how to become one.",
                    theme.dim(), row));
            return tab;
        }

        // Cheapest first, so the free ones lead and the ladder reads as a ladder. Sorted rather
        // than map order because the config is a plain JSON object and its iteration order is
        // whatever the admin's file happens to say.
        List<String> ids = new ArrayList<>(config.trails().keySet());
        ids.sort(Comparator.comparingInt(config::trailCost).thenComparing(id -> id));

        for (String id : ids) {
            tab = (TabContentBuilder) tab.addChild(
                    line(rowId(id), rowText(service, uuid, id), rowStyle(service, uuid, id), row));
            if (buyable(service, uuid, id)) {
                tab = (TabContentBuilder) tab.addChild(buyButton(service, uuid, id, world, row));
            }
            row++;
        }
        return tab;
    }

    /**
     * Whether a row gets a Buy button element at all.
     *
     * <p>Free and already-owned trails never get one, and nothing can change that while the panel
     * is open, so the element is simply never built. Everything else gets a button whose
     * <em>visibility</em> is then driven by {@link #canAfford} — the element has to exist up front
     * because {@code editById} can only edit something that was built, so a button that might
     * become affordable later must be present and hidden rather than absent.
     */
    private boolean buyable(SupporterService service, UUID uuid, String id) {
        return plugin.config().trailCost(id) > 0 && !service.unlocks(uuid).contains(id);
    }

    /** Whether that button is shown. A button you cannot use is noise — the row states why. */
    private boolean canAfford(SupporterService service, UUID uuid, String id) {
        return buyable(service, uuid, id)
                && service.tokenBalance(uuid) >= plugin.config().trailCost(id);
    }

    private CustomButtonBuilder buyButton(SupporterService service, UUID uuid, String trailId,
                                          World world, int row) {
        CustomButtonBuilder button = (CustomButtonBuilder) CustomButtonBuilder.customTextButton()
                .withId("SupBuy_" + trailId)
                .withAnchor(new HyUIAnchor()
                        .setTop(row * ROW_HEIGHT - 4)
                        .setLeft(WIDTH - (PAD * 2) - BUY_WIDTH)
                        .setWidth(BUY_WIDTH).setHeight(BUY_HEIGHT));
        button = button.withText("Buy")
                .withVisible(canAfford(service, uuid, trailId))
                .withDefaultBackground(theme.tabBackground())
                .withHoveredBackground(theme.tabHovered())
                .withPressedBackground(theme.tabPressed());
        return button.addEventListener(CustomUIEventBindingType.Activating,
                (Void v, au.ellie.hyui.events.UIContext ctx) -> buy(service, uuid, trailId, world, ctx));
    }

    private static String buyId(String trailId) {
        return "SupBuy_" + trailId;
    }

    private void buy(SupporterService service, UUID uuid, String trailId, World world,
                     au.ellie.hyui.events.UIContext ctx) {
        String message;
        try {
            message = switch (service.purchaseTrail(uuid, trailId)) {
                case BOUGHT -> "Bought " + trailId + " - /supporter trail " + trailId
                        + " to wear it";
                case ALREADY_OWNED -> "You already own " + trailId;
                case NOT_ENOUGH_TOKENS -> "Not enough tokens for " + trailId;
                case FREE -> trailId + " is free - /supporter trail " + trailId;
                case UNKNOWN_ITEM -> "That trail no longer exists";
            };
        } catch (RuntimeException e) {
            // The money half already succeeded or failed on its own terms; never let the UI half
            // turn a storage error into a silent no-op.
            plugin.log().error("Trail purchase failed for " + uuid + " (" + trailId + ")", e);
            message = "Purchase failed - try /supporter buy " + trailId;
        }

        String note = message;
        world.execute(() -> {
            try {
                refreshAfterPurchase(service, uuid, ctx, note);
            } catch (Throwable t) {
                plugin.log().warn("Shop panel refresh failed: " + t);
            }
        });
    }

    /**
     * Rewrites every part of the panel that a purchase can change — on every tab.
     *
     * <p><b>Every row, not just the one that was bought</b>, because spending changes the shortfall
     * on all the others: buying snow at 150 took gold from "need 50 more" to "need 200 more". And
     * <b>the Status tab too</b>, because it shows the same balance and the list of trails owned.
     * Both of those were learned the same way and in the same order — 0.8.2 refreshed one row and
     * left the rest lying, 0.8.3 refreshed the shop and left the Status tab reporting 200 tokens
     * while the shop correctly showed 0.
     *
     * <p>A page is a snapshot. Anything derived from state that a click changes has to be listed
     * here, or it silently keeps showing the world as it was when the panel opened.
     */
    private void refreshAfterPurchase(SupporterService service, UUID uuid,
                                      au.ellie.hyui.events.UIContext ctx, String note) {
        ctx.editById(BALANCE_ID, LabelBuilder.class,
                label -> label.withText(balanceText(service, uuid)));
        ctx.editById(STATUS_TOKENS_ID, LabelBuilder.class,
                label -> label.withText(balanceText(service, uuid)));
        ctx.editById(STATUS_UNLOCKS_ID, LabelBuilder.class,
                label -> label.withText(unlocksText(service, uuid)));
        for (String id : plugin.config().trails().keySet()) {
            ctx.editById(rowId(id), LabelBuilder.class,
                    label -> label.withText(rowText(service, uuid, id))
                            .withStyle(rowStyle(service, uuid, id)));
            ctx.editById(buyId(id), CustomButtonBuilder.class,
                    button -> button.withVisible(canAfford(service, uuid, id)));
        }
        ctx.editById(NOTICE_ID, LabelBuilder.class, label -> label.withText(note));
        ctx.updatePage(false);
    }

    private static String rowId(String trailId) {
        return "SupTrail_" + trailId;
    }

    private String balanceText(SupporterService service, UUID uuid) {
        return service.tokenBalance(uuid) + " token(s) to spend";
    }

    private String rowText(SupporterService service, UUID uuid, String trailId) {
        int cost = plugin.config().trailCost(trailId);
        if (cost <= 0) {
            return trailId + " - free";
        }
        if (service.unlocks(uuid).contains(trailId)) {
            return trailId + " - owned";
        }
        int short_ = cost - service.tokenBalance(uuid);
        return short_ > 0
                ? trailId + " - " + cost + " tokens (need " + short_ + " more)"
                : trailId + " - " + cost + " tokens";
    }

    private HyUIStyle rowStyle(SupporterService service, UUID uuid, String trailId) {
        int cost = plugin.config().trailCost(trailId);
        if (cost <= 0 || service.unlocks(uuid).contains(trailId)) {
            return theme.coloured(SupporterTheme.INK_GOOD, false);
        }
        return service.tokenBalance(uuid) >= cost
                ? theme.body()
                : theme.coloured(SupporterTheme.INK_LOCKED, false);
    }

    private UIElementBuilder<?> aboutTab() {
        SupporterConfig config = plugin.config();
        TabContentBuilder tab = content("about");
        int row = 0;

        tab = (TabContentBuilder) tab.addChild(line("AbWhat",
                "This server is paid for by the people who play on it.", theme.body(), row++));
        tab = (TabContentBuilder) tab.addChild(line("AbFair",
                "Everything supporter rank gives you is cosmetic or convenience. It does not "
                        + "touch combat, claims, power, outpost income, KOTJ or bucks.",
                theme.coloured(SupporterTheme.INK_GOOD, false), row));
        row += 3;

        if (!config.hasStorefront()) {
            tab = (TabContentBuilder) tab.addChild(line("AbPrice",
                    "Pricing is not set up yet - ask an admin.", theme.dim(), row));
            return tab;
        }

        tab = (TabContentBuilder) tab.addChild(line("AbPriceHead", "Price",
                theme.heading(), row++));
        int index = 0;
        for (String priceLine : config.priceLines()) {
            tab = (TabContentBuilder) tab.addChild(
                    line("AbPrice" + index++, priceLine, theme.body(), row++));
        }
        tab = (TabContentBuilder) tab.addChild(line("AbWhere",
                config.storeUrl().isEmpty()
                        ? "Where: ask an admin - the store is not open yet."
                        : "Where: " + config.storeUrl(),
                theme.dim(), row));
        return tab;
    }

    // --- helpers --------------------------------------------------------------------------

    private LabelBuilder line(String id, String value, HyUIStyle style, int row) {
        return text(id, value, style, row * ROW_HEIGHT, 0, WIDTH - (PAD * 2), ROW_HEIGHT * 2);
    }

    private LabelBuilder text(String id, String value, HyUIStyle style,
                              int top, int left, int width, int height) {
        LabelBuilder label = (LabelBuilder) LabelBuilder.label()
                .withId(id)
                .withAnchor(new HyUIAnchor().setTop(top).setLeft(left)
                        .setWidth(width).setHeight(height))
                .withStyle(style);
        return label.withText(value);
    }
}
