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
import au.ellie.hyui.builders.TextFieldBuilder;
import au.ellie.hyui.builders.UIElementBuilder;

import com.hypixel.hytale.component.Ref;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.peoplesserver.supportermod.SupporterPlugin;
import com.peoplesserver.supportermod.command.SupporterCommand;
import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.core.SupporterIdentity;
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

    private static final int WIDTH = 960;
    private static final int HEIGHT = 660;
    private static final int PAD = 24;
    /**
     * Row pitch. Must exceed BUY_HEIGHT, or the Buy buttons on consecutive rows overlap - the
     * first build had 24 against a 26-tall button and they visibly touched.
     */
    private static final int ROW_HEIGHT = 38;

    /** Tab hit area. Text-only tabs were too small a target on the first live build. */
    private static final int TAB_WIDTH = 106; // eight tabs across the bar since the Quests tab
    private static final int TAB_HEIGHT = 42;
    private static final int TAB_SPACING = 8; // 8 x 106 + 7 x 8 = 904, inside the 912 content

    /** Buy button, sized to sit on the right of a shop row. */
    private static final int BUY_WIDTH = 120;
    private static final int BUY_HEIGHT = 32;

    /**
     * The Shop grids (trails, gear, skins) use wider buttons than everything else: the labels
     * carry a price suffix ("jack-sparrow · 300" is 18 characters) and the shrink-to-fit label
     * style renders those at half size inside a 120-wide button. 200 fits the longest label at
     * full size; four per row keeps a grid inside the 912 content width.
     */
    private static final int SKIN_BUY_WIDTH = 200;
    private static final int SKIN_COLS = 4;

    private static final String BALANCE_ID = "SupBalance";
    private static final String NOTICE_ID = "SupNotice";
    private static final String STATUS_TOKENS_ID = "StTokens";
    private static final String STATUS_UNLOCKS_ID = "StUnlocks";
    private static final String TRAIL_CURRENT_ID = "TrCurrent";
    private static final String TRAIL_TOGGLE_ID = "TrToggle";
    private static final String CHAT_TITLE_ID = "ChTitle";
    private static final String CHAT_FIELD_ID = "ChTitleField";
    private static final String CHAT_COLOR_ID = "ChColor";

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
                    .withAnchor(new HyUIAnchor().setTop(70).setWidth(WIDTH).setHeight(HEIGHT))
                    .withBackground(theme.panelBackground());

            root = (GroupBuilder) root.addChild(SceneBlurBuilder.sceneBlur().withId("SupBlur"));
            root = (GroupBuilder) root.addChild(header(service, uuid));
            root = (GroupBuilder) root.addChild(tabBar());
            root = (GroupBuilder) root.addChild(statusTab(service, uuid));
            root = (GroupBuilder) root.addChild(perksTab(service, uuid));
            root = (GroupBuilder) root.addChild(shopTab(service, uuid, world));
            root = (GroupBuilder) root.addChild(questsTab(service, uuid, world));
            root = (GroupBuilder) root.addChild(trailsTab(service, uuid, world));
            root = (GroupBuilder) root.addChild(chatTab(service, uuid, world));
            root = (GroupBuilder) root.addChild(wardrobeTab(service, uuid, playerRef, world));
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
                .withAnchor(new HyUIAnchor().setTop(0).setLeft(0).setWidth(WIDTH).setHeight(74))
                .withBackground(theme.headerBackground());

        header = (GroupBuilder) header.addChild(text("SupTitle", "Supporter",
                theme.title(), 14, PAD, WIDTH - (PAD * 2), 36));
        header = (GroupBuilder) header.addChild(text("SupSubtitle", subtitle(service, uuid),
                theme.dim(), 48, PAD, WIDTH - (PAD * 2), 20));
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
                .addTab("quests", "Quests", tabButton("quests"))
                .addTab("trails", "Trails", tabButton("trails"))
                .addTab("chat", "Chat", tabButton("chat"))
                .addTab("wardrobe", "Wardrobe", tabButton("wardrobe"))
                .addTab("about", "About", tabButton("about"))
                .withId(TABS_ID)
                .withAnchor(new HyUIAnchor().setTop(80).setLeft(PAD)
                        .setWidth(WIDTH - (PAD * 2)).setHeight(TAB_HEIGHT));
    }

    private CustomButtonBuilder tabButton(String tabId) {
        CustomButtonBuilder button = (CustomButtonBuilder) CustomButtonBuilder.customTextButton()
                .withId("SupTab_" + tabId)
                .withAnchor(new HyUIAnchor().setWidth(TAB_WIDTH).setHeight(TAB_HEIGHT));
        return button
                .withDefaultLabelStyle(theme.buttonLabel())
                .withHoveredLabelStyle(theme.buttonLabel())
                .withPressedLabelStyle(theme.buttonLabel())
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
                HEIGHT - 52, PAD, WIDTH - (PAD * 2), 26);
    }

    // --- tabs -----------------------------------------------------------------------------

    private TabContentBuilder content(String tabId) {
        return (TabContentBuilder) TabContentBuilder.tabContent()
                .withTabNavigationId(TABS_ID)
                .withTabId(tabId)
                .withId("SupContent_" + tabId)
                .withAnchor(new HyUIAnchor().setTop(134).setLeft(PAD)
                        .setWidth(WIDTH - (PAD * 2)).setHeight(HEIGHT - 192));
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
        // Skin and gear unlocks share the table under "skin:"/"gear:" namespaces (names collide
        // with trails), so split them out for display rather than showing the raw prefixed ids.
        List<String> trails = unlocks.stream()
                .filter(u -> !u.startsWith("skin:") && !u.startsWith("gear:")
                        && !u.startsWith("pet:")).toList();
        List<String> skins = unlocks.stream().filter(u -> u.startsWith("skin:"))
                .map(u -> u.substring(5)).toList();
        List<String> gear = unlocks.stream().filter(u -> u.startsWith("gear:"))
                .map(u -> u.substring(5)).toList();
        List<String> pets = unlocks.stream().filter(u -> u.startsWith("pet:"))
                .map(u -> u.substring(4)).toList();
        if (trails.isEmpty() && skins.isEmpty() && gear.isEmpty() && pets.isEmpty()) {
            return "Nothing bought yet - the free trails, gear and skins are yours already";
        }
        StringBuilder out = new StringBuilder();
        if (!trails.isEmpty()) {
            out.append("Trails bought: ").append(String.join(", ", trails));
        }
        if (!gear.isEmpty()) {
            if (out.length() > 0) {
                out.append("   ");
            }
            out.append("Gear bought: ").append(String.join(", ", gear));
        }
        if (!pets.isEmpty()) {
            if (out.length() > 0) {
                out.append("   ");
            }
            out.append("Pets bought: ").append(String.join(", ", pets));
        }
        if (!skins.isEmpty()) {
            if (out.length() > 0) {
                out.append("   ");
            }
            out.append("Skins bought: ").append(String.join(", ", skins));
        }
        return out.toString();
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
        tab = (TabContentBuilder) tab.addChild(line("PkCape",
                "A supporter cape - " + SupporterCommand.CapeSub.DESIGNS.size()
                        + " designs, /supporter cape", theme.body(), row++));
        tab = (TabContentBuilder) tab.addChild(line("PkHat",
                "Headwear - " + SupporterCommand.HatSub.HATS.size()
                        + " to choose from - and trainers: the Wardrobe tab",
                theme.body(), row++));
        tab = (TabContentBuilder) tab.addChild(line("PkHomes",
                "Homes: " + config.supporterHomeSlots()
                        + " instead of " + config.defaultHomeSlots(), theme.body(), row++));
        tab = (TabContentBuilder) tab.addChild(line("PkPets",
                "Pets - cosmetic followers: " + String.join(", ",
                        SupporterCommand.PetSub.PETS.keySet()), theme.body(), row++));
        tab = (TabContentBuilder) tab.addChild(line("PkQuests",
                "Daily and weekly quests that pay tokens - the Quests tab",
                theme.body(), row++));
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
        // No blank row after the balance since 0.22.1 — nine pets need two grid rows, and
        // this is where the row came from. The tab sits at exactly 13 rows, the ceiling.
        row++;

        if (!service.isSupporter(uuid)) {
            tab = (TabContentBuilder) tab.addChild(line("ShopLocked",
                    "The shop is for supporters. The About tab explains how to become one.",
                    theme.dim(), row));
            return tab;
        }

        // v0.20.0: the shop is three grids of what is FOR SALE — priced and unowned only.
        // Twelve trails stopped fitting as text rows, and 0.19.2 had already decided the
        // philosophy: the Shop is what you could buy; what you own lives on the Trails and
        // Wardrobe tabs. Every priced item gets a button, hidden once owned (the standing
        // editById rule), visible regardless of balance (the 0.19.1 rule — the grid IS the
        // listing, and clicking one you cannot afford says so in the notice line).
        tab = (TabContentBuilder) tab.addChild(line("ShopTrailsHead",
                "Trails — wear them from the Trails tab", theme.heading(), row++));
        List<String> ids = new ArrayList<>(config.trails().keySet());
        ids.sort(Comparator.comparingInt(config::trailCost).thenComparing(id -> id));
        int index = 0;
        // Six per row at the narrow width — trail names are short ("hearts · 150" is 12
        // characters, full-size at 120), and the row this reclaims is what makes space for
        // the Pets section below.
        for (String id : ids) {
            int cost = config.trailCost(id);
            if (cost <= 0) {
                continue; // free trails are not shop items; the Trails tab has them
            }
            tab = (TabContentBuilder) tab.addChild(wearButton(
                    buyId(id), id + " · " + cost, theme.buttonLabel(),
                    (index % 6) * (BUY_WIDTH + 8),
                    (row + (index / 6)) * ROW_HEIGHT - 4,
                    (Void v, au.ellie.hyui.events.UIContext ctx) ->
                            buy(service, uuid, id, world, ctx))
                    .withVisible(!service.unlocks(uuid).contains(id)));
            index++;
        }
        row += Math.max(1, (index + 5) / 6);

        // Gear and pets share one section since 0.22.2: thirteen priced pets need three grid
        // rows, and the header this merge saves is what keeps the tab at the 13-row ceiling.
        // One story anyway — the Wardrobe is where both get worn. Five per row at 176 wide:
        // "tortoise · 400" (14 characters) renders full-size, and 5 x 176 + 4 x 8 = 912,
        // flush with the content width.
        tab = (TabContentBuilder) tab.addChild(line("ShopGearHead",
                "Gear and pets — the Wardrobe tab has them", theme.heading(), row++));
        index = 0;
        for (String name : gearNames()) {
            int cost = config.gearCost(name);
            if (cost <= 0) {
                continue;
            }
            tab = (TabContentBuilder) tab.addChild(wearButton(
                    gearBuyId(name), name + " · " + cost, theme.buttonLabel(),
                    (index % 5) * (176 + 8),
                    (row + (index / 5)) * ROW_HEIGHT - 4,
                    176,
                    (Void v, au.ellie.hyui.events.UIContext ctx) ->
                            buyGear(service, uuid, name, cost, world, ctx))
                    .withVisible(!service.ownsGear(uuid, name, cost)));
            index++;
        }
        for (String petName : SupporterCommand.PetSub.PETS.keySet()) {
            int cost = config.petCost(petName);
            if (cost <= 0) {
                continue; // the free pair are not shop items; the Wardrobe has them
            }
            tab = (TabContentBuilder) tab.addChild(wearButton(
                    petBuyId(petName), petName + " · " + cost, theme.buttonLabel(),
                    (index % 5) * (176 + 8),
                    (row + (index / 5)) * ROW_HEIGHT - 4,
                    176,
                    (Void v, au.ellie.hyui.events.UIContext ctx) ->
                            buyPet(service, uuid, petName, cost, world, ctx))
                    .withVisible(!service.ownsPet(uuid, petName, cost)));
            index++;
        }
        row += Math.max(1, (index + 4) / 5);

        tab = (TabContentBuilder) tab.addChild(line("ShopSkinsHead",
                "Skins — tints and costumes; wear them from the Wardrobe tab",
                theme.heading(), row++));
        int skinIndex = 0;
        for (String skinName : SkinChanger.allNames()) {
            int cost = skinCost(skinName);
            if (cost <= 0) {
                continue;
            }
            tab = (TabContentBuilder) tab.addChild(wearButton(
                    skinBuyId(skinName), skinName + " · " + cost, theme.buttonLabel(),
                    (skinIndex % SKIN_COLS) * (SKIN_BUY_WIDTH + 8),
                    (row + (skinIndex / SKIN_COLS)) * ROW_HEIGHT - 4,
                    SKIN_BUY_WIDTH,
                    (Void v, au.ellie.hyui.events.UIContext ctx) ->
                            buySkin(service, uuid, skinName, cost, world, ctx))
                    .withVisible(skinBuyVisible(service, uuid, skinName, cost)));
            skinIndex++;
        }
        return tab;
    }

    // --- quests tab -----------------------------------------------------------------------

    /**
     * Three dailies and a weekly, with a Claim button that appears when a quest completes.
     *
     * <p>Progress rows are snapshots like everything else here — the panel does not live-tick.
     * Reopening (or claiming anything) refreshes them. A panel that outlives midnight UTC
     * offers yesterday's quests; claiming one then reports it expired rather than paying out,
     * which is the honest answer.
     */
    private UIElementBuilder<?> questsTab(SupporterService service, UUID uuid, World world) {
        SupporterConfig config = plugin.config();
        TabContentBuilder tab = content("quests");
        int row = 0;

        if (!service.isSupporter(uuid)) {
            tab = (TabContentBuilder) tab.addChild(line("QstLocked",
                    "Quests are a supporter perk. The About tab explains how to become one.",
                    theme.dim(), row));
            return tab;
        }

        List<SupporterService.QuestState> quests = service.quests(uuid);
        tab = (TabContentBuilder) tab.addChild(line("QstDailyHead",
                "Daily quests — " + config.questDailyReward()
                        + " tokens each, reset at midnight UTC",
                theme.heading(), row++));
        for (SupporterService.QuestState q : quests) {
            if (!q.daily()) {
                continue;
            }
            tab = addQuestRow(tab, service, uuid, q, world, row++);
        }
        row++;
        tab = (TabContentBuilder) tab.addChild(line("QstWeeklyHead",
                "Weekly quest — " + config.questWeeklyReward() + " tokens",
                theme.heading(), row++));
        for (SupporterService.QuestState q : quests) {
            if (q.daily()) {
                continue;
            }
            tab = addQuestRow(tab, service, uuid, q, world, row++);
        }
        row++;
        tab = (TabContentBuilder) tab.addChild(line("QstNote",
                "Progress counts while you play - it ticks about once a minute. "
                        + "Claim before the reset; unclaimed quests expire.",
                theme.dim(), row));
        return tab;
    }

    private TabContentBuilder addQuestRow(TabContentBuilder tab, SupporterService service,
                                          UUID uuid, SupporterService.QuestState q, World world,
                                          int row) {
        tab = (TabContentBuilder) tab.addChild(line(questRowId(q.key()),
                questRowText(q), questRowStyle(q), row));
        tab = (TabContentBuilder) tab.addChild(
                smallButton(questClaimId(q.key()), "Claim",
                        WIDTH - (PAD * 2) - BUY_WIDTH, row * ROW_HEIGHT - 4,
                        (Void v, au.ellie.hyui.events.UIContext ctx) ->
                                claimQuestClick(service, uuid, q.key(), world, ctx))
                        .withVisible(q.claimable()));
        return tab;
    }

    /** Quest keys contain ':' — keep element ids to the character set every other id uses. */
    private static String questRowId(String key) {
        return "QstRow_" + key.replace(':', '_');
    }

    private static String questClaimId(String key) {
        return "QstClaim_" + key.replace(':', '_');
    }

    private String questRowText(SupporterService.QuestState q) {
        String state = q.claimed() ? "  (claimed)" : q.complete() ? "  (ready to claim!)" : "";
        return q.label() + " - " + q.progress() + "/" + q.target() + state;
    }

    private HyUIStyle questRowStyle(SupporterService.QuestState q) {
        if (q.claimable()) {
            return theme.coloured(SupporterTheme.INK_GOOD, true);
        }
        return q.claimed() ? theme.coloured(SupporterTheme.INK_GOOD, false) : theme.body();
    }

    private void claimQuestClick(SupporterService service, UUID uuid, String key, World world,
                                 au.ellie.hyui.events.UIContext ctx) {
        String note;
        try {
            note = switch (service.claimQuest(uuid, key)) {
                case CLAIMED -> "Quest complete — tokens added to your balance!";
                case NOT_DONE -> "Not finished yet — keep going.";
                case ALREADY_CLAIMED -> "Already claimed.";
                case NOT_SUPPORTER -> "Quests are a supporter perk — see the About tab.";
                case UNKNOWN_QUEST ->
                        "That quest expired at the reset — reopen the panel for today's.";
            };
        } catch (RuntimeException e) {
            plugin.log().error("Quest claim failed for " + uuid + " (" + key + ")", e);
            note = "Claim failed — try again in a moment.";
        }
        String message = note;
        world.execute(() -> {
            try {
                refreshQuests(service, uuid, ctx);
                refreshAfterPurchase(service, uuid, ctx, message);
            } catch (Throwable t) {
                plugin.log().warn("Quest claim refresh failed: " + t);
            }
        });
    }

    /** Rewrites every quest row and claim button. Edits only — the caller runs updatePage. */
    private void refreshQuests(SupporterService service, UUID uuid,
                               au.ellie.hyui.events.UIContext ctx) {
        for (SupporterService.QuestState q : service.quests(uuid)) {
            ctx.editById(questRowId(q.key()), LabelBuilder.class,
                    label -> label.withText(questRowText(q)).withStyle(questRowStyle(q)));
            ctx.editById(questClaimId(q.key()), CustomButtonBuilder.class,
                    button -> button.withVisible(q.claimable()));
        }
    }

    private static String petBuyId(String petName) {
        return "PetBuy_" + petName;
    }

    private void buyPet(SupporterService service, UUID uuid, String petName, int cost,
                        World world, au.ellie.hyui.events.UIContext ctx) {
        String note;
        try {
            note = switch (service.purchasePet(uuid, petName, cost)) {
                case BOUGHT -> "Unlocked the " + petName
                        + " — bring it out from the Wardrobe tab, or /supporter pet " + petName;
                case ALREADY_OWNED -> "You already own the " + petName;
                case NOT_ENOUGH_TOKENS -> "Not enough tokens for the " + petName;
                case FREE -> "The " + petName + " is free — the Wardrobe tab has it";
                case UNKNOWN_ITEM -> "That pet no longer exists";
            };
        } catch (RuntimeException e) {
            plugin.log().error("Pet purchase failed for " + uuid + " (" + petName + ")", e);
            note = "Purchase failed — try /supporter buy " + petName;
        }
        String message = note;
        world.execute(() -> {
            try {
                refreshAfterPurchase(service, uuid, ctx, message);
            } catch (Throwable t) {
                plugin.log().warn("Pet purchase refresh failed: " + t);
            }
        });
    }

    /**
     * Every wearable design name, all three catalogues — the command maps stay the single
     * source of truth, so gear added to a command shows up here without panel changes.
     */
    private static List<String> gearNames() {
        List<String> out = new ArrayList<>();
        out.addAll(SupporterCommand.CapeSub.DESIGNS.keySet());
        out.addAll(SupporterCommand.HatSub.HATS.keySet());
        out.addAll(SupporterCommand.ShoesSub.SHOES.keySet());
        return out;
    }

    private static String gearBuyId(String name) {
        return "GearBuy_" + name;
    }

    private void buyGear(SupporterService service, UUID uuid, String name, int cost,
                         World world, au.ellie.hyui.events.UIContext ctx) {
        String note;
        try {
            note = switch (service.purchaseGear(uuid, name, cost)) {
                case BOUGHT -> "Unlocked " + name + " — the Wardrobe tab delivers it";
                case ALREADY_OWNED -> "You already own " + name;
                case NOT_ENOUGH_TOKENS -> "Not enough tokens for " + name;
                case FREE -> name + " is free — the Wardrobe tab has it";
                case UNKNOWN_ITEM -> "That item no longer exists";
            };
        } catch (RuntimeException e) {
            plugin.log().error("Gear purchase failed for " + uuid + " (" + name + ")", e);
            note = "Purchase failed — try /supporter buy " + name;
        }
        String message = note;
        world.execute(() -> {
            try {
                refreshAfterPurchase(service, uuid, ctx, message);
            } catch (Throwable t) {
                plugin.log().warn("Gear purchase refresh failed: " + t);
            }
        });
    }

    private static String skinBuyId(String skinName) {
        return "SkinBuy_" + skinName;
    }

    private int skinCost(String skinName) {
        return plugin.config().skinCost(skinName, SkinChanger.isCostume(skinName));
    }

    /**
     * Visible while UNOWNED — affordability deliberately does not hide it. The trail buttons
     * hide when unaffordable because their text rows still show the item and its price; these
     * buttons ARE the listing, so hiding them left a header over an empty shop for anyone with
     * no tokens, while the Wardrobe pointed straight at it. Clicking one you cannot afford says
     * so in the notice line instead.
     */
    private boolean skinBuyVisible(SupporterService service, UUID uuid, String name, int cost) {
        return !service.ownsSkin(uuid, name, cost);
    }

    private void buySkin(SupporterService service, UUID uuid, String skinName, int cost,
                         World world, au.ellie.hyui.events.UIContext ctx) {
        String note;
        try {
            note = switch (service.purchaseSkin(uuid, skinName, cost)) {
                case BOUGHT -> "Unlocked " + skinName
                        + " — wear it from the Wardrobe tab, or /supporter skin " + skinName;
                case ALREADY_OWNED -> "You already own " + skinName;
                case NOT_ENOUGH_TOKENS -> "Not enough tokens for " + skinName;
                case FREE -> skinName + " is free — the Wardrobe tab has it";
                case UNKNOWN_ITEM -> "That skin no longer exists";
            };
        } catch (RuntimeException e) {
            plugin.log().error("Skin purchase failed for " + uuid + " (" + skinName + ")", e);
            note = "Purchase failed — try /supporter buy " + skinName;
        }
        String message = note;
        world.execute(() -> {
            try {
                refreshAfterPurchase(service, uuid, ctx, message);
            } catch (Throwable t) {
                plugin.log().warn("Skin purchase refresh failed: " + t);
            }
        });
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
        // Shop trail buttons hide once owned — the grid lists only what is still for sale.
        for (String id : plugin.config().trails().keySet()) {
            if (plugin.config().trailCost(id) > 0) {
                ctx.editById(buyId(id), CustomButtonBuilder.class,
                        button -> button.withVisible(!service.unlocks(uuid).contains(id)));
            }
        }
        // A purchase changes trail wearability too — the Trails tab must reveal the new Wear
        // button without the panel being reopened. Same lesson as the Status tab, one tab over.
        refreshTrails(service, uuid, ctx);
        // Gear, both places at once: the Shop button disappears, the Wardrobe button appears.
        refreshGearButtons(service, uuid, ctx,
                SupporterCommand.CapeSub.DESIGNS.keySet(), "WdCape_");
        refreshGearButtons(service, uuid, ctx,
                SupporterCommand.HatSub.HATS.keySet(), "WdHat_");
        refreshGearButtons(service, uuid, ctx,
                SupporterCommand.ShoesSub.SHOES.keySet(), "WdShoe_");
        // Pets, both places at once — same split as everything else.
        for (String petName : SupporterCommand.PetSub.PETS.keySet()) {
            int petCost = plugin.config().petCost(petName);
            if (petCost > 0) {
                ctx.editById(petBuyId(petName), CustomButtonBuilder.class,
                        b -> b.withVisible(!service.ownsPet(uuid, petName, petCost)));
            }
            ctx.editById("WdPet_" + petName, CustomButtonBuilder.class,
                    b -> b.withVisible(service.ownsPet(uuid, petName, petCost)));
        }
        // A purchase changes which skin buttons show in BOTH places: the Shop button
        // disappears, the Wardrobe button appears.
        for (String skinName : SkinChanger.allNames()) {
            int cost = skinCost(skinName);
            if (cost > 0) {
                ctx.editById(skinBuyId(skinName), CustomButtonBuilder.class,
                        b -> b.withVisible(skinBuyVisible(service, uuid, skinName, cost)));
            }
            ctx.editById("WdSkin_" + skinName, CustomButtonBuilder.class,
                    b -> b.withVisible(service.ownsSkin(uuid, skinName, skinCost(skinName))));
        }
        ctx.editById(NOTICE_ID, LabelBuilder.class, label -> label.withText(note));
        ctx.updatePage(false);
    }

    private void refreshGearButtons(SupporterService service, UUID uuid,
                                    au.ellie.hyui.events.UIContext ctx,
                                    java.util.Set<String> names, String wardrobePrefix) {
        for (String name : names) {
            int cost = plugin.config().gearCost(name);
            if (cost > 0) {
                ctx.editById(gearBuyId(name), CustomButtonBuilder.class,
                        b -> b.withVisible(!service.ownsGear(uuid, name, cost)));
            }
            ctx.editById(wardrobePrefix + name, CustomButtonBuilder.class,
                    b -> b.withVisible(service.ownsGear(uuid, name,
                            plugin.config().gearCost(name))));
        }
    }

    private String balanceText(SupporterService service, UUID uuid) {
        return service.tokenBalance(uuid) + " token(s) to spend";
    }

    // --- trails tab -----------------------------------------------------------------------

    /**
     * Pick a trail by clicking instead of typing {@code /supporter trail <name>}.
     *
     * <p>Wear buttons go through {@link SupporterService#selectTrail}, the ownership-aware call —
     * the same one the chat command uses — so the panel cannot hand out a trail the shop would
     * have charged for. Buttons exist for every trail and are merely hidden while not wearable,
     * because {@code editById} can only edit an element that was built, and buying a trail in the
     * Shop tab must be able to reveal its Wear button without reopening the panel.
     *
     * <p>The hide-others toggle is deliberately available to everyone, supporter or not — same
     * rule as the chat command: a cosmetic the people looking at it cannot switch off is a
     * nuisance, not a perk.
     */
    private UIElementBuilder<?> trailsTab(SupporterService service, UUID uuid, World world) {
        TabContentBuilder tab = content("trails");
        int row = 0;

        tab = (TabContentBuilder) tab.addChild(line(TRAIL_CURRENT_ID,
                trailCurrentText(service, uuid), theme.heading(), row));
        boolean supporter = service.isSupporter(uuid);
        if (supporter) {
            tab = (TabContentBuilder) tab.addChild(
                    smallButton("SupTrailOff", "Off", WIDTH - (PAD * 2) - BUY_WIDTH,
                            row * ROW_HEIGHT - 4,
                            (Void v, au.ellie.hyui.events.UIContext ctx) ->
                                    wearTrail(service, uuid, null, world, ctx)));
        }
        row++;

        tab = (TabContentBuilder) tab.addChild(
                toggleButton(service, uuid, world, row * ROW_HEIGHT - 4));
        row += 2;

        if (!supporter) {
            tab = (TabContentBuilder) tab.addChild(line("TrLocked",
                    "Trails are a supporter perk. The About tab explains how to get them.",
                    theme.dim(), row));
            return tab;
        }

        // v0.20.0: a grid of what you can WEAR, not a listing of everything — twelve trails
        // stopped fitting as rows, and the Shop grid now carries the locked-with-price story.
        // Same split as the Wardrobe: this tab is what you own, the Shop is what you could.
        // Buttons are built for every trail and hidden while locked, so buying one in the Shop
        // reveals it here in the same click.
        tab = (TabContentBuilder) tab.addChild(line("TrOwnedHead",
                "Your trails — the locked ones are in the Shop tab", theme.heading(), row++));
        List<String> ids = new ArrayList<>(plugin.config().trails().keySet());
        ids.sort(Comparator.comparingInt(plugin.config()::trailCost).thenComparing(id -> id));
        int index = 0;
        for (String id : ids) {
            tab = (TabContentBuilder) tab.addChild(
                    wearButton(trailWearId(id), id, theme.buttonLabel(),
                            (index % 6) * (BUY_WIDTH + 8),
                            (row + (index / 6)) * ROW_HEIGHT - 4,
                            (Void v, au.ellie.hyui.events.UIContext ctx) ->
                                    wearTrail(service, uuid, id, world, ctx))
                            .withVisible(wearable(service, uuid, id)));
            index++;
        }
        return tab;
    }

    private CustomButtonBuilder toggleButton(SupporterService service, UUID uuid, World world,
                                             int top) {
        CustomButtonBuilder button = (CustomButtonBuilder) CustomButtonBuilder.customTextButton()
                .withId(TRAIL_TOGGLE_ID)
                .withAnchor(new HyUIAnchor().setTop(top).setLeft(0).setWidth(340).setHeight(BUY_HEIGHT));
        button = button.withText(toggleText(service, uuid))
                .withDefaultLabelStyle(theme.buttonLabel())
                .withHoveredLabelStyle(theme.buttonLabel())
                .withPressedLabelStyle(theme.buttonLabel())
                .withDefaultBackground(theme.tabBackground())
                .withHoveredBackground(theme.tabHovered())
                .withPressedBackground(theme.tabPressed());
        return button.addEventListener(CustomUIEventBindingType.Activating,
                (Void v, au.ellie.hyui.events.UIContext ctx) -> {
                    String note;
                    try {
                        boolean nowHidden = !service.identity(uuid).hideTrails();
                        service.setHideTrails(uuid, nowHidden);
                        note = nowHidden ? "Other players' trails are now hidden for you."
                                : "Other players' trails are now shown.";
                    } catch (RuntimeException e) {
                        note = "Could not save: " + e.getMessage();
                    }
                    String message = note;
                    world.execute(() -> {
                        try {
                            ctx.editById(TRAIL_TOGGLE_ID, CustomButtonBuilder.class,
                                    b -> b.withText(toggleText(service, uuid)));
                            ctx.editById(NOTICE_ID, LabelBuilder.class,
                                    label -> label.withText(message));
                            ctx.updatePage(false);
                        } catch (Throwable t) {
                            plugin.log().warn("Trails toggle refresh failed: " + t);
                        }
                    });
                });
    }

    private void wearTrail(SupporterService service, UUID uuid, String trailId, World world,
                           au.ellie.hyui.events.UIContext ctx) {
        String note;
        try {
            service.selectTrail(uuid, trailId);
            note = trailId == null ? "Trail off." : "Now wearing " + trailId + ".";
        } catch (IllegalArgumentException e) {
            note = e.getMessage();
        } catch (RuntimeException e) {
            plugin.log().error("selectTrail failed for " + uuid + " (" + trailId + ")", e);
            note = "Could not save your trail - try /supporter trail " + trailId;
        }
        String message = note;
        world.execute(() -> {
            try {
                refreshTrails(service, uuid, ctx);
                ctx.editById(NOTICE_ID, LabelBuilder.class, label -> label.withText(message));
                ctx.updatePage(false);
            } catch (Throwable t) {
                plugin.log().warn("Trails refresh failed: " + t);
            }
        });
    }

    /** Rewrites the trails tab: the wearing header and every wear button's visibility. */
    private void refreshTrails(SupporterService service, UUID uuid,
                               au.ellie.hyui.events.UIContext ctx) {
        ctx.editById(TRAIL_CURRENT_ID, LabelBuilder.class,
                label -> label.withText(trailCurrentText(service, uuid)));
        for (String id : plugin.config().trails().keySet()) {
            ctx.editById(trailWearId(id), CustomButtonBuilder.class,
                    button -> button.withVisible(wearable(service, uuid, id)));
        }
    }

    private boolean wearable(SupporterService service, UUID uuid, String id) {
        return plugin.config().trailCost(id) <= 0 || service.unlocks(uuid).contains(id);
    }

    private String trailCurrentText(SupporterService service, UUID uuid) {
        String worn = service.identity(uuid).trail();
        return worn == null ? "No trail on" : "Wearing: " + worn;
    }

    private static String trailWearId(String id) {
        return "TrWear_" + id;
    }

    // --- chat tab -------------------------------------------------------------------------

    /**
     * Title and chat colour by clicking instead of typing.
     *
     * <p><b>The title field carries a no-op {@code ValueChanged} listener, and it is
     * load-bearing.</b> HyUI only stores an element's value into {@code elementValues} while
     * dispatching a listener for that event — the store happens inside the listener loop in
     * {@code HyUInterface.handleElementEvents} — so without a listener the Set button would read
     * back whatever the field was built with, forever, no matter what the player typed.
     *
     * <p>Colour swatches are built from {@code allowedChatColors}, each labelled in its own
     * colour, so the panel stays correct when an admin edits the list — the same reason the
     * validation lives in the service and not here.
     */
    private UIElementBuilder<?> chatTab(SupporterService service, UUID uuid, World world) {
        TabContentBuilder tab = content("chat");
        int row = 0;

        if (!service.isSupporter(uuid)) {
            tab = (TabContentBuilder) tab.addChild(line("ChLocked",
                    "Chat titles and colours are a supporter perk. The About tab explains how "
                            + "to get them.", theme.dim(), row));
            return tab;
        }

        tab = (TabContentBuilder) tab.addChild(line(CHAT_TITLE_ID,
                titleText(service, uuid), theme.heading(), row++));

        TextFieldBuilder field = (TextFieldBuilder) TextFieldBuilder.textInput()
                .withId(CHAT_FIELD_ID)
                .withAnchor(new HyUIAnchor().setTop(row * ROW_HEIGHT - 4).setLeft(0)
                        .setWidth(420).setHeight(34));
        field = field.withPlaceholderText("Your title...")
                .withValue(service.identity(uuid).title() == null
                        ? "" : service.identity(uuid).title())
                // Load-bearing no-op: see the class comment. Without this, typed text never
                // reaches elementValues and the Set button reads the initial value forever.
                .addEventListener(CustomUIEventBindingType.ValueChanged, String.class, v -> { });
        tab = (TabContentBuilder) tab.addChild(field);

        tab = (TabContentBuilder) tab.addChild(
                smallButton("ChTitleSet", "Set title", 436, row * ROW_HEIGHT - 4,
                        (Void v, au.ellie.hyui.events.UIContext ctx) ->
                                setTitle(service, uuid, world, ctx, false)));
        tab = (TabContentBuilder) tab.addChild(
                smallButton("ChTitleClear", "Clear", 436 + BUY_WIDTH + 8, row * ROW_HEIGHT - 4,
                        (Void v, au.ellie.hyui.events.UIContext ctx) ->
                                setTitle(service, uuid, world, ctx, true)));
        row += 2;

        tab = (TabContentBuilder) tab.addChild(line(CHAT_COLOR_ID,
                colorText(service, uuid), theme.heading(), row++));

        int index = 0;
        for (String hex : plugin.config().allowedChatColors()) {
            CustomButtonBuilder swatch = (CustomButtonBuilder)
                    CustomButtonBuilder.customTextButton()
                            .withId("ChColor_" + index)
                            .withAnchor(new HyUIAnchor()
                                    .setTop((row + (index / 6)) * ROW_HEIGHT - 4)
                                    .setLeft((index % 6) * (BUY_WIDTH + 8))
                                    .setWidth(BUY_WIDTH).setHeight(BUY_HEIGHT));
            String chosen = hex;
            swatch = swatch.withText(hex)
                    .withDefaultLabelStyle(theme.swatchLabel(hex))
                    .withHoveredLabelStyle(theme.swatchLabel(hex))
                    .withPressedLabelStyle(theme.swatchLabel(hex))
                    .withDefaultBackground(theme.tabBackground())
                    .withHoveredBackground(theme.tabHovered())
                    .withPressedBackground(theme.tabPressed());
            tab = (TabContentBuilder) tab.addChild(
                    swatch.addEventListener(CustomUIEventBindingType.Activating,
                            (Void v, au.ellie.hyui.events.UIContext ctx) ->
                                    setColor(service, uuid, chosen, world, ctx)));
            index++;
        }
        row += 1 + ((index - 1) / 6) + 1;

        tab = (TabContentBuilder) tab.addChild(
                smallButton("ChColorClear", "Default", 0, row * ROW_HEIGHT - 4,
                        (Void v, au.ellie.hyui.events.UIContext ctx) ->
                                setColor(service, uuid, null, world, ctx)));
        return tab;
    }

    private void setTitle(SupporterService service, UUID uuid, World world,
                          au.ellie.hyui.events.UIContext ctx, boolean clear) {
        String note;
        try {
            String wanted = clear ? null
                    : ctx.getValue(CHAT_FIELD_ID, String.class).orElse("").trim();
            SupporterIdentity updated = service.setTitle(uuid, wanted);
            note = updated.hasTitle() ? "Title set to: " + updated.title() : "Title cleared.";
        } catch (IllegalArgumentException e) {
            note = e.getMessage();
        } catch (RuntimeException e) {
            plugin.log().error("setTitle failed for " + uuid, e);
            note = "Could not save your title - try /supporter title";
        }
        String message = note;
        world.execute(() -> {
            try {
                ctx.editById(CHAT_TITLE_ID, LabelBuilder.class,
                        label -> label.withText(titleText(service, uuid)));
                ctx.editById(NOTICE_ID, LabelBuilder.class, label -> label.withText(message));
                ctx.updatePage(false);
            } catch (Throwable t) {
                plugin.log().warn("Chat tab refresh failed: " + t);
            }
        });
    }

    private void setColor(SupporterService service, UUID uuid, String hex, World world,
                          au.ellie.hyui.events.UIContext ctx) {
        String note;
        try {
            service.setChatColor(uuid, hex);
            note = hex == null ? "Colour back to default." : "Chat colour set to " + hex + ".";
        } catch (IllegalArgumentException e) {
            note = e.getMessage();
        } catch (RuntimeException e) {
            plugin.log().error("setChatColor failed for " + uuid, e);
            note = "Could not save your colour - try /supporter colour";
        }
        String message = note;
        world.execute(() -> {
            try {
                ctx.editById(CHAT_COLOR_ID, LabelBuilder.class,
                        label -> label.withText(colorText(service, uuid)));
                ctx.editById(NOTICE_ID, LabelBuilder.class, label -> label.withText(message));
                ctx.updatePage(false);
            } catch (Throwable t) {
                plugin.log().warn("Chat tab refresh failed: " + t);
            }
        });
    }

    private String titleText(SupporterService service, UUID uuid) {
        SupporterIdentity identity = service.identity(uuid);
        return identity.hasTitle() ? "Title: " + identity.title() : "Title: none";
    }

    private String colorText(SupporterService service, UUID uuid) {
        String hex = service.identity(uuid).chatColor();
        return hex == null ? "Colour: default" : "Colour: " + hex;
    }

    private String toggleText(SupporterService service, UUID uuid) {
        return service.identity(uuid).hideTrails()
                ? "Others' trails: hidden" : "Others' trails: shown";
    }

    /** A right-aligned action button in the shop/trails row style. */
    private CustomButtonBuilder smallButton(
            String id, String text, int left, int top,
            java.util.function.BiConsumer<Void, au.ellie.hyui.events.UIContext> onClick) {
        CustomButtonBuilder button = (CustomButtonBuilder) CustomButtonBuilder.customTextButton()
                .withId(id)
                .withAnchor(new HyUIAnchor().setTop(top).setLeft(left)
                        .setWidth(BUY_WIDTH).setHeight(BUY_HEIGHT));
        button = button.withText(text)
                .withDefaultLabelStyle(theme.buttonLabel())
                .withHoveredLabelStyle(theme.buttonLabel())
                .withPressedLabelStyle(theme.buttonLabel())
                .withDefaultBackground(theme.tabBackground())
                .withHoveredBackground(theme.tabHovered())
                .withPressedBackground(theme.tabPressed());
        return button.addEventListener(CustomUIEventBindingType.Activating, onClick);
    }

    // --- wardrobe tab ---------------------------------------------------------------------

    /**
     * Cape design → base colour, mirroring CapeTextureGen's palette so each button's label wears
     * its cape's colour. Hard-coded rather than derived from allowedChatColors, because an admin
     * can edit that config list but the cape textures are fixed at build time.
     */
    private static final java.util.Map<String, String> CAPE_COLORS = new java.util.LinkedHashMap<>();

    static {
        CAPE_COLORS.put("amber", "#FFAA00");
        CAPE_COLORS.put("frost", "#55FFFF");
        CAPE_COLORS.put("rose", "#FF55FF");
        CAPE_COLORS.put("jade", "#55FF55");
        CAPE_COLORS.put("gold", "#FFFF55");
        CAPE_COLORS.put("ember", "#FF5555");
    }

    /**
     * Every wearable, one Get button each — the chat commands' item maps are the single source
     * of truth, so a wearable added to a command appears here without panel changes.
     *
     * <p><b>Delivery runs entirely on the world thread.</b> {@code Player.giveItem} reads
     * inventory components, and component access is world-thread only — the same rule as
     * {@code updatePage}, so the whole callback hands off in one hop. The entity {@code Ref} is
     * resolved fresh at click time rather than captured at build time, because a panel can
     * outlive a respawn.
     */
    private UIElementBuilder<?> wardrobeTab(SupporterService service, UUID uuid,
                                            PlayerRef playerRef, World world) {
        TabContentBuilder tab = content("wardrobe");
        int row = 0;

        if (!service.isSupporter(uuid)) {
            tab = (TabContentBuilder) tab.addChild(line("WdLocked",
                    "The wardrobe is a supporter perk. The About tab explains how to get it.",
                    theme.dim(), row));
            return tab;
        }

        // Priced gear follows the 0.19.2 skin rule: the Wardrobe shows only what you own,
        // built-and-hidden while locked so a Shop purchase reveals the button in-place. Free
        // gear is owned by everyone, so those buttons are always visible.
        tab = (TabContentBuilder) tab.addChild(line("WdCapes",
                "Capes — one chest item at a time", theme.heading(), row++));
        int index = 0;
        for (java.util.Map.Entry<String, String> cape
                : SupporterCommand.CapeSub.DESIGNS.entrySet()) {
            String hex = CAPE_COLORS.getOrDefault(cape.getKey(), SupporterTheme.INK_BODY);
            tab = (TabContentBuilder) tab.addChild(wearButton(
                    "WdCape_" + cape.getKey(), cape.getKey(), theme.swatchLabel(hex),
                    index * (BUY_WIDTH + 8), row * ROW_HEIGHT - 4,
                    (Void v, au.ellie.hyui.events.UIContext ctx) -> giveWearable(
                            service, uuid, playerRef, cape.getKey(),
                            "Cape (" + cape.getKey() + ")",
                            cape.getValue(), world, ctx))
                    .withVisible(service.ownsGear(uuid, cape.getKey(),
                            plugin.config().gearCost(cape.getKey()))));
            index++;
        }
        row += 2;

        tab = (TabContentBuilder) tab.addChild(line("WdHats",
                "Headwear — one head item at a time", theme.heading(), row++));
        index = 0;
        for (java.util.Map.Entry<String, String> hat : SupporterCommand.HatSub.HATS.entrySet()) {
            tab = (TabContentBuilder) tab.addChild(wearButton(
                    "WdHat_" + hat.getKey(), hat.getKey(), theme.buttonLabel(),
                    index * (BUY_WIDTH + 8), row * ROW_HEIGHT - 4,
                    (Void v, au.ellie.hyui.events.UIContext ctx) -> giveWearable(
                            service, uuid, playerRef, hat.getKey(),
                            "Headwear (" + hat.getKey() + ")",
                            hat.getValue(), world, ctx))
                    .withVisible(service.ownsGear(uuid, hat.getKey(),
                            plugin.config().gearCost(hat.getKey()))));
            index++;
        }
        row += 2;

        tab = (TabContentBuilder) tab.addChild(line("WdShoes",
                "Feet — uses the legs armour slot", theme.heading(), row++));
        index = 0;
        for (java.util.Map.Entry<String, String> shoe
                : SupporterCommand.ShoesSub.SHOES.entrySet()) {
            tab = (TabContentBuilder) tab.addChild(wearButton(
                    "WdShoe_" + shoe.getKey(), shoe.getKey(), theme.buttonLabel(),
                    index * (BUY_WIDTH + 8), row * ROW_HEIGHT - 4,
                    (Void v, au.ellie.hyui.events.UIContext ctx) -> giveWearable(
                            service, uuid, playerRef, shoe.getKey(),
                            "Footwear (" + shoe.getKey() + ")",
                            shoe.getValue(), world, ctx))
                    .withVisible(service.ownsGear(uuid, shoe.getKey(),
                            plugin.config().gearCost(shoe.getKey()))));
            index++;
        }
        row += 2;

        tab = (TabContentBuilder) tab.addChild(line("WdPets",
                "Pets — one follower at a time; they cannot fight", theme.heading(), row++));
        index = 0;
        // A grid since 0.22.1: nine pets plus "no pet" stopped fitting one row.
        for (String petName : SupporterCommand.PetSub.PETS.keySet()) {
            tab = (TabContentBuilder) tab.addChild(wearButton(
                    "WdPet_" + petName, petName, theme.buttonLabel(),
                    (index % 6) * (BUY_WIDTH + 8), (row + (index / 6)) * ROW_HEIGHT - 4,
                    (Void v, au.ellie.hyui.events.UIContext ctx) -> wearPet(
                            service, uuid, playerRef, petName, world, ctx))
                    .withVisible(service.ownsPet(uuid, petName,
                            plugin.config().petCost(petName))));
            index++;
        }
        tab = (TabContentBuilder) tab.addChild(wearButton(
                "WdPet_off", "no pet", theme.buttonLabel(),
                (index % 6) * (BUY_WIDTH + 8), (row + (index / 6)) * ROW_HEIGHT - 4,
                (Void v, au.ellie.hyui.events.UIContext ctx) -> wearPet(
                        service, uuid, playerRef, null, world, ctx)));
        row += 4; // sixteen buttons, three grid rows since 0.22.2

        // Tighter gap than the other sections: the rack is three rows deep and the panel
        // notice line sits below the content area.
        row--;
        tab = (TabContentBuilder) tab.addChild(line("WdSkins",
                "Body skins — statue mode; stays on across relogs", theme.heading(), row++));
        index = 0;
        // Locked skins are hidden here, not advertised - the Wardrobe is what you own, the
        // Shop is what you could. Built-and-hidden rather than absent, so buying in the Shop
        // reveals the button here without reopening the panel.
        for (String skinName : SkinChanger.allNames()) {
            tab = (TabContentBuilder) tab.addChild(wearButton(
                    "WdSkin_" + skinName, skinName, theme.buttonLabel(),
                    (index % 6) * (BUY_WIDTH + 8), (row + (index / 6)) * ROW_HEIGHT - 4,
                    (Void v, au.ellie.hyui.events.UIContext ctx) -> setSkin(
                            service, uuid, playerRef, skinName, world, ctx))
                    .withVisible(service.ownsSkin(uuid, skinName, skinCost(skinName))));
            index++;
        }
        tab = (TabContentBuilder) tab.addChild(wearButton(
                "WdSkin_off", "off", theme.buttonLabel(),
                (index % 6) * (BUY_WIDTH + 8), (row + (index / 6)) * ROW_HEIGHT - 4,
                (Void v, au.ellie.hyui.events.UIContext ctx) -> setSkin(
                        service, uuid, playerRef, null, world, ctx)));
        // The cosmetic disclaimer that used to sit here was cut when the skin rack grew to
        // three rows and pushed it into the panel notice line - the About tab carries the same
        // ground rules, and the notice line needs to stay clear for purchase feedback.
        return tab;
    }

    /** Skin buttons: persist the choice, then push the visual on the world thread. */
    private void setSkin(SupporterService service, UUID uuid, PlayerRef playerRef,
                         String skinName, World world, au.ellie.hyui.events.UIContext ctx) {
        boolean off = skinName == null;
        // Priced skins must be unlocked first — same ladder as the trails.
        if (!off) {
            int cost = skinCost(skinName);
            if (!service.ownsSkin(uuid, skinName, cost)) {
                world.execute(() -> {
                    ctx.editById(NOTICE_ID, LabelBuilder.class, l -> l.withText(
                            skinName + " is locked — " + cost + " tokens in the Shop tab."));
                    ctx.updatePage(false);
                });
                return;
            }
        }
        try {
            service.setSkin(uuid, skinName);
        } catch (RuntimeException e) {
            plugin.log().error("setSkin failed for " + uuid, e);
            world.execute(() -> {
                ctx.editById(NOTICE_ID, LabelBuilder.class,
                        l -> l.withText("Could not save your skin choice — try the chat command."));
                ctx.updatePage(false);
            });
            return;
        }
        world.execute(() -> {
            String note;
            try {
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref == null || ref.getStore() == null) {
                    note = "Saved — it will apply next login.";
                } else {
                    SkinChanger.Result result = off
                            ? SkinChanger.restore(ref.getStore(), ref, uuid)
                            : SkinChanger.applyByName(ref.getStore(), ref, uuid, skinName);
                    note = off ? "Skin off — you are fully restored."
                            : result.applied()
                                    ? "Skin on: " + skinName + " — statue mode, stays until off."
                                    : "Saved, but the visual push failed — relog to see it.";
                }
            } catch (Throwable t) {
                plugin.log().error("Skin change failed for " + uuid, t);
                note = "Saved, but the visual push failed — relog to see it.";
            }
            String message = note;
            try {
                ctx.editById(NOTICE_ID, LabelBuilder.class, l -> l.withText(message));
                ctx.updatePage(false);
            } catch (Throwable t) {
                plugin.log().warn("Skin notice refresh failed: " + t);
            }
        });
    }

    private CustomButtonBuilder wearButton(String id, String text, HyUIStyle labelStyle,
                                           int left, int top,
                                           java.util.function.BiConsumer<Void,
                                                   au.ellie.hyui.events.UIContext> onClick) {
        return wearButton(id, text, labelStyle, left, top, BUY_WIDTH, onClick);
    }

    private CustomButtonBuilder wearButton(String id, String text, HyUIStyle labelStyle,
                                           int left, int top, int width,
                                           java.util.function.BiConsumer<Void,
                                                   au.ellie.hyui.events.UIContext> onClick) {
        CustomButtonBuilder button = (CustomButtonBuilder) CustomButtonBuilder.customTextButton()
                .withId(id)
                .withAnchor(new HyUIAnchor().setTop(top).setLeft(left)
                        .setWidth(width).setHeight(BUY_HEIGHT));
        button = button.withText(text)
                .withDefaultLabelStyle(labelStyle)
                .withHoveredLabelStyle(labelStyle)
                .withPressedLabelStyle(labelStyle)
                .withDefaultBackground(theme.tabBackground())
                .withHoveredBackground(theme.tabHovered())
                .withPressedBackground(theme.tabPressed());
        return button.addEventListener(CustomUIEventBindingType.Activating, onClick);
    }

    /** Pet buttons: persist the choice, then spawn on the world thread. Null sends it home. */
    private void wearPet(SupporterService service, UUID uuid, PlayerRef playerRef,
                         String petName, World world, au.ellie.hyui.events.UIContext ctx) {
        world.execute(() -> {
            String note;
            try {
                var pets = plugin.pets();
                Ref<EntityStore> ref = playerRef.getReference();
                Store<EntityStore> store = ref == null ? null : ref.getStore();
                if (pets == null || store == null) {
                    note = "Pets unavailable — try /supporter pet instead.";
                } else if (petName == null) {
                    service.setPet(uuid, null);
                    pets.removeFor(uuid, store);
                    note = "Pet sent home.";
                } else if (!service.isSupporter(uuid)) {
                    note = "Pets are a supporter perk — see the About tab.";
                } else if (!service.ownsPet(uuid, petName, plugin.config().petCost(petName))) {
                    note = "The " + petName + " is locked — "
                            + plugin.config().petCost(petName) + " tokens in the Shop tab.";
                } else {
                    service.setPet(uuid, petName);
                    String role = SupporterCommand.PetSub.PETS.get(petName);
                    var transform = store.getComponent(ref,
                            com.hypixel.hytale.server.core.modules.entity.component
                                    .TransformComponent.getComponentType());
                    String failure = role == null
                            ? "that pet is no longer in the catalogue"
                            : transform == null || transform.getPosition() == null
                                    ? "it will appear beside you within a second or two"
                                    : pets.spawn(store, uuid,
                                            new org.joml.Vector3d(transform.getPosition()),
                                            role);
                    note = failure == null
                            ? "Pet out: " + petName + "! It follows you and stays across relogs."
                            : "Saved: " + failure;
                }
            } catch (Throwable t) {
                plugin.log().error("Pet wear failed for " + uuid + " (" + petName + ")", t);
                note = "Could not change your pet — try /supporter pet " + petName;
            }
            String message = note;
            try {
                ctx.editById(NOTICE_ID, LabelBuilder.class, l -> l.withText(message));
                ctx.updatePage(false);
            } catch (Throwable t) {
                plugin.log().warn("Pet notice refresh failed: " + t);
            }
        });
    }

    private void giveWearable(SupporterService service, UUID uuid, PlayerRef playerRef,
                              String gearName, String label, String itemId, World world,
                              au.ellie.hyui.events.UIContext ctx) {
        world.execute(() -> {
            String note;
            try {
                int cost = plugin.config().gearCost(gearName);
                if (!service.isSupporter(uuid)) {
                    note = "The wardrobe is a supporter perk — see the About tab.";
                } else if (!service.ownsGear(uuid, gearName, cost)) {
                    // Belt and braces: the button is hidden while locked, but a stale panel
                    // must not hand out an unpaid unlock.
                    note = gearName + " is locked — " + cost + " tokens in the Shop tab.";
                } else {
                    Ref<EntityStore> ref = playerRef.getReference();
                    if (ref == null || ref.getStore() == null) {
                        note = "Could not reach your inventory — try the chat command instead.";
                    } else {
                        ItemStackTransaction tx = Player.giveItem(
                                new ItemStack(itemId, 1), ref, ref.getStore());
                        ItemStack remainder = tx == null ? null : tx.getRemainder();
                        note = remainder != null && !remainder.isEmpty()
                                ? "No room in your inventory — clear a slot and try again."
                                : label + " delivered — check your inventory.";
                    }
                }
            } catch (Throwable t) {
                plugin.log().error("Wardrobe delivery failed for " + uuid
                        + " (" + itemId + ")", t);
                note = "Delivery failed — try the chat command instead.";
            }
            String message = note;
            try {
                ctx.editById(NOTICE_ID, LabelBuilder.class, l -> l.withText(message));
                ctx.updatePage(false);
            } catch (Throwable t) {
                plugin.log().warn("Wardrobe notice refresh failed: " + t);
            }
        });
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
