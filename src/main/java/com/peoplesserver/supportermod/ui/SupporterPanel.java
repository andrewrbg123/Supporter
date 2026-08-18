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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.peoplesserver.supportermod.SupporterPlugin;
import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.core.SupporterRecord;
import com.peoplesserver.supportermod.core.SupporterService;

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

    private static final int WIDTH = 520;
    private static final int HEIGHT = 380;
    private static final int PAD = 18;
    private static final int ROW_HEIGHT = 20;

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
    public boolean open(PlayerRef playerRef, Store<EntityStore> store) {
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
                .withAnchor(new HyUIAnchor().setTop(0).setLeft(0).setWidth(WIDTH).setHeight(52))
                .withBackground(theme.headerBackground());

        header = (GroupBuilder) header.addChild(text("SupTitle", "Supporter",
                theme.title(), 10, PAD, WIDTH - (PAD * 2), 24));
        header = (GroupBuilder) header.addChild(text("SupSubtitle", subtitle(service, uuid),
                theme.dim(), 32, PAD, WIDTH - (PAD * 2), 16));
        return header;
    }

    private String subtitle(SupporterService service, UUID uuid) {
        Optional<SupporterRecord> record = service.get(uuid);
        if (record.isEmpty()) {
            return "Cosmetic perks only - nothing here affects gameplay";
        }
        return service.status(uuid) + " - " + service.daysRemaining(uuid) + " day(s) remaining";
    }

    private UIElementBuilder<?> tabBar() {
        return TabNavigationBuilder.tabNavigation()
                .withSelectedTab("status")
                .withSelectedTabStyle(theme.selectedTab())
                .withUnselectedTabStyle(theme.unselectedTab())
                .withTabSpacing(8)
                .addTab("status", "Status")
                .addTab("perks", "Perks")
                .addTab("about", "About")
                .withId(TABS_ID)
                .withAnchor(new HyUIAnchor().setTop(60).setLeft(PAD)
                        .setWidth(WIDTH - (PAD * 2)).setHeight(26));
    }

    // --- tabs -----------------------------------------------------------------------------

    private TabContentBuilder content(String tabId) {
        return (TabContentBuilder) TabContentBuilder.tabContent()
                .withTabNavigationId(TABS_ID)
                .withTabId(tabId)
                .withId("SupContent_" + tabId)
                .withAnchor(new HyUIAnchor().setTop(96).setLeft(PAD)
                        .setWidth(WIDTH - (PAD * 2)).setHeight(HEIGHT - 130));
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
        tab = (TabContentBuilder) tab.addChild(line("StTokens",
                service.tokenBalance(uuid) + " token(s) to spend", theme.body(), row++));
        tab = (TabContentBuilder) tab.addChild(line("StUnlocks",
                service.unlocks(uuid).size() + " unlock(s) owned", theme.dim(), row));
        return tab;
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
