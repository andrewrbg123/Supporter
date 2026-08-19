package com.peoplesserver.supportermod.ui;

import au.ellie.hyui.builders.Alignment;
import au.ellie.hyui.builders.CustomButtonBuilder;
import au.ellie.hyui.builders.GroupBuilder;
import au.ellie.hyui.builders.HyUIAnchor;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.SceneBlurBuilder;
import au.ellie.hyui.builders.TextFieldBuilder;
import au.ellie.hyui.builders.UIElementBuilder;
import au.ellie.hyui.events.UIContext;
import au.ellie.hyui.types.ScrollbarStyle;
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
import com.peoplesserver.supportermod.core.SupporterService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The redesigned supporter panel: eight screens in flat colour, on a persistent left rail.
 *
 * <p><b>Drawn entirely from colour-only patch styles</b> — see {@link FlatTheme#fill}. That one
 * discovery is what made the whole design portable: surfaces, hairlines, cards, chips, meters
 * and progress bars are all rectangles of {@code #RRGGBBAA}, composited by the client, so the
 * design's {@code rgba()} values port across as written.
 *
 * <p><b>Tab switching is ours, not HyUI's.</b> {@code TabNavigationBuilder} lays its tabs out
 * horizontally and the design needs a vertical rail, so all eight screens are built up front
 * and every switch is a visibility edit — the same {@code editById} + {@code updatePage}
 * mechanism every other interaction here uses, which is proven, rather than a layout mode that
 * is not. The cost is that a page holds every screen at once; the live panel already did that.
 *
 * <p><b>Every screen scrolls</b> (0.26.0). Each one is a {@code TopScrolling} group between the
 * header and the notice line, which retires the row ceilings this plugin has fought since the
 * first panel: a tab may now be as tall as its content. Probed before use, because HyUI ships
 * the mode but never uses it — the live answer was that absolute anchors survive it intact and
 * the wheel moves the viewport, which is the only combination that would have worked here.
 */
public final class FlatPanel {

    private static final int WIDTH = 1180;
    private static final int HEIGHT = 788;
    private static final int TOP = 46;

    private static final int RAIL_W = 236;
    private static final int PAD = 40;
    private static final int CONTENT_W = WIDTH - RAIL_W;
    private static final int BODY_W = CONTENT_W - (PAD * 2);
    /** The scrolling viewport: between the header rule (110) and the notice rule (726). */
    private static final int VIEW_TOP = 126;
    private static final int VIEW_H = 600;
    /**
     * First row of tab content, measured INSIDE the viewport rather than the content column —
     * a scrolling group is the coordinate origin for everything it holds.
     */
    private static final int BODY_TOP = 18;

    private static final int CHIP_H = 34;
    private static final int CHIP_ROW = 44;
    private static final int HEAD_H = 32;

    private static final String ROOT_ID = "FlatRoot";
    private static final String TITLE_ID = "FlatTitle";
    private static final String SUBTITLE_ID = "FlatSubtitle";
    private static final String NOTICE_ID = "FlatNotice";
    private static final String WALLET_ID = "FlatWalletValue";
    private static final String CHAT_FIELD_ID = "FlatChatField";

    private static final String[] NAV = {
        "Status", "Perks", "Shop", "Quests", "Trails", "Chat", "Wardrobe", "About",
    };

    private static final String[] SUBTITLES = {
        "Your rank at a glance - what you have, what you have earned, and everything you own.",
        "Everything supporter rank gives you. All of it cosmetic or convenience.",
        "Spend tokens on trails, gear, pets and skins. Bought once, yours for good.",
        "Daily and weekly quests that pay tokens. Claim them before the reset.",
        "Pick the particles that follow you, or switch everyone else's off.",
        "Your title and colour in chat, previewed before you commit.",
        "Everything you own, ready to wear. One item per slot.",
        "What supporter rank is, what it costs, and where the money goes.",
    };

    private final SupporterPlugin plugin;
    private final FlatTheme theme;

    public FlatPanel(SupporterPlugin plugin) {
        this.plugin = plugin;
        this.theme = new FlatTheme(plugin.config().tagColorHex());
    }

    public boolean open(PlayerRef playerRef, Store<EntityStore> store, World world) {
        try {
            UUID uuid = playerRef.getUuid();
            SupporterService service = plugin.service();
            if (service == null) {
                return false;
            }

            GroupBuilder root = (GroupBuilder) GroupBuilder.group()
                    .withId(ROOT_ID)
                    .withAnchor(new HyUIAnchor().setTop(TOP).setWidth(WIDTH).setHeight(HEIGHT))
                    .withBackground(theme.fill(FlatTheme.PANEL_BG));

            root = (GroupBuilder) root.addChild(SceneBlurBuilder.sceneBlur().withId("FlatBlur"));
            root = (GroupBuilder) root.addChild(rail(service, uuid, world));
            root = (GroupBuilder) root.addChild(content(service, uuid, playerRef, world));

            PageBuilder page = (PageBuilder) PageBuilder.pageForPlayer(playerRef).addElement(root);
            page.open(store);
            return true;
        } catch (Throwable t) {
            plugin.log().warn("Flat panel failed to open: " + t);
            return false;
        }
    }

    // --- left rail ---------------------------------------------------------------------------

    private UIElementBuilder<?> rail(SupporterService service, UUID uuid, World world) {
        GroupBuilder rail = (GroupBuilder) GroupBuilder.group()
                .withId("FlatRail")
                .withAnchor(new HyUIAnchor().setTop(0).setLeft(0)
                        .setWidth(RAIL_W).setHeight(HEIGHT))
                .withBackground(theme.fill(FlatTheme.RAIL_BG));

        rail = (GroupBuilder) rail.addChild(box("FlatMark", theme.accent(), 22, 30, 8, 8));
        rail = (GroupBuilder) rail.addChild(
                text("FlatWordmark", "SUPPORTER", theme.wordmark(), 26, 40, 180, 22));

        boolean active = service.isSupporter(uuid);
        String stateColour = active ? FlatTheme.GREEN : FlatTheme.INK_MUTED;
        rail = (GroupBuilder) rail.addChild(box("FlatDot", stateColour, 23, 67, 6, 6));
        rail = (GroupBuilder) rail.addChild(text("FlatState",
                active ? "ACTIVE" : "INACTIVE", theme.eyebrow(stateColour), 60, 37, 80, 16));
        rail = (GroupBuilder) rail.addChild(text("FlatDays",
                active ? service.daysRemaining(uuid) + " DAYS LEFT" : "NOT A SUPPORTER",
                theme.small(FlatTheme.INK_SECONDARY), 60, 118, 110, 16));

        // Remaining against lifetime: renewing pushes it back up, lapsing empties it. Measured
        // against a fixed horizon it would sit pinned at full for everybody who ever looked.
        int remaining = (int) Math.max(0L, service.daysRemaining(uuid));
        int lifetime = Math.max(remaining, service.get(uuid).map(r -> r.totalDays()).orElse(0));
        rail = (GroupBuilder) rail.addChild(
                box("FlatMeterTrack", FlatTheme.METER_TRACK, 22, 94, 192, 4));
        if (lifetime > 0 && remaining > 0) {
            rail = (GroupBuilder) rail.addChild(box("FlatMeterFill", theme.accent(), 22, 94,
                    Math.max(2, Math.round(192f * remaining / lifetime)), 4));
        }
        rail = (GroupBuilder) rail.addChild(
                box("FlatBrandRule", FlatTheme.HAIRLINE, 0, 118, RAIL_W, 1));

        for (int i = 0; i < NAV.length; i++) {
            final int index = i;
            int y = 134 + (i * 36);
            boolean selected = i == 0;
            // Built-and-hidden rather than restyled: toggling one element's visibility is the
            // mechanism this plugin has proven over and over, and it keeps the click handler
            // to a list of visibility edits.
            rail = (GroupBuilder) rail.addChild(box(navBgId(i), theme.accentNav(), 12, y, 212, 32)
                    .withVisible(selected));
            rail = (GroupBuilder) rail.addChild(
                    box(navDimMarkId(i), FlatTheme.white(0.16), 22, y + 13, 5, 5));
            rail = (GroupBuilder) rail.addChild(
                    box(navMarkId(i), theme.accent(), 22, y + 13, 5, 5).withVisible(selected));
            rail = (GroupBuilder) rail.addChild(text(navLabelId(i), NAV[i],
                    theme.navLabel(selected), y + 7, 38, 170, 20));
            // The hit target sits over the whole row, transparent, so the label and marker
            // underneath keep their own styling.
            rail = (GroupBuilder) rail.addChild(hitArea("FlatNavHit" + i, 12, y, 212, 32,
                    (Void v, UIContext ctx) -> selectTab(index, world, ctx)));
        }

        rail = (GroupBuilder) rail.addChild(
                box("FlatWalletRule", FlatTheme.HAIRLINE, 0, 650, RAIL_W, 1));
        rail = (GroupBuilder) rail.addChild(text("FlatWalletLabel", "TOKENS",
                theme.eyebrow(FlatTheme.INK_MUTED), 672, 22, 120, 16));
        rail = (GroupBuilder) rail.addChild(text(WALLET_ID,
                String.valueOf(service.tokenBalance(uuid)), theme.wallet(), 692, 22, 150, 34));
        rail = (GroupBuilder) rail.addChild(text("FlatWalletNote", "to spend",
                theme.small(FlatTheme.INK_SECONDARY), 730, 22, 150, 18));
        rail = (GroupBuilder) rail.addChild(
                box("FlatRailEdge", FlatTheme.HAIRLINE, RAIL_W - 1, 0, 1, HEIGHT));
        return rail;
    }

    private static String navBgId(int i) {
        return "FlatNavBg" + i;
    }

    private static String navMarkId(int i) {
        return "FlatNavMark" + i;
    }

    private static String navDimMarkId(int i) {
        return "FlatNavDim" + i;
    }

    private static String navLabelId(int i) {
        return "FlatNavLabel" + i;
    }

    private static String tabId(int i) {
        return "FlatTab" + i;
    }

    /** Switches screens: every tab's visibility, the rail's selection, and the header. */
    private void selectTab(int index, World world, UIContext ctx) {
        world.execute(() -> {
            try {
                for (int i = 0; i < NAV.length; i++) {
                    final boolean on = i == index;
                    ctx.editById(tabId(i), GroupBuilder.class, g -> g.withVisible(on));
                    ctx.editById(navBgId(i), GroupBuilder.class, g -> g.withVisible(on));
                    ctx.editById(navMarkId(i), GroupBuilder.class, g -> g.withVisible(on));
                    ctx.editById(navLabelId(i), LabelBuilder.class,
                            l -> l.withStyle(theme.navLabel(on)));
                }
                ctx.editById(TITLE_ID, LabelBuilder.class, l -> l.withText(NAV[index]));
                ctx.editById(SUBTITLE_ID, LabelBuilder.class,
                        l -> l.withText(SUBTITLES[index]));
                ctx.updatePage(false);
            } catch (Throwable t) {
                plugin.log().warn("Flat panel tab switch failed: " + t);
            }
        });
    }

    // --- content column ----------------------------------------------------------------------

    private UIElementBuilder<?> content(SupporterService service, UUID uuid,
                                        PlayerRef playerRef, World world) {
        GroupBuilder col = (GroupBuilder) GroupBuilder.group()
                .withId("FlatContent")
                .withAnchor(new HyUIAnchor().setTop(0).setLeft(RAIL_W)
                        .setWidth(CONTENT_W).setHeight(HEIGHT));

        col = (GroupBuilder) col.addChild(text("FlatEyebrow", "/SUPPORTER",
                theme.eyebrow(FlatTheme.INK_MUTED), 30, PAD, 240, 16));
        col = (GroupBuilder) col.addChild(
                text(TITLE_ID, NAV[0], theme.pageTitle(), 50, PAD, 420, 40));
        col = (GroupBuilder) col.addChild(text(SUBTITLE_ID, SUBTITLES[0],
                right(theme.body(FlatTheme.INK_SECONDARY)),
                52, CONTENT_W - PAD - 400, 400, 48));
        col = (GroupBuilder) col.addChild(
                box("FlatHeadRule", FlatTheme.HAIRLINE, 0, 110, CONTENT_W, 1));

        col = (GroupBuilder) col.addChild(statusTab(service, uuid));
        col = (GroupBuilder) col.addChild(perksTab(service, uuid));
        col = (GroupBuilder) col.addChild(shopTab(service, uuid, world));
        col = (GroupBuilder) col.addChild(questsTab(service, uuid, world));
        col = (GroupBuilder) col.addChild(trailsTab(service, uuid, world));
        col = (GroupBuilder) col.addChild(chatTab(service, uuid, world));
        col = (GroupBuilder) col.addChild(wardrobeTab(service, uuid, playerRef, world));
        col = (GroupBuilder) col.addChild(aboutTab());

        col = (GroupBuilder) col.addChild(
                box("FlatNoticeRule", FlatTheme.HAIRLINE, PAD, 726, BODY_W, 1));
        col = (GroupBuilder) col.addChild(text(NOTICE_ID, "",
                theme.small(FlatTheme.INK_SECONDARY), 742, PAD, BODY_W, 22));
        return col;
    }

    /**
     * An empty screen: a scrolling viewport, visible only when its tab is selected.
     *
     * <p>The header and the notice line are deliberately OUTSIDE it — they are chrome, and
     * chrome that scrolled away with the content would be worse than a row ceiling.
     */
    private GroupBuilder screen(int index) {
        GroupBuilder group = (GroupBuilder) GroupBuilder.group()
                .withId(tabId(index))
                .withAnchor(new HyUIAnchor().setTop(VIEW_TOP).setLeft(0)
                        .setWidth(CONTENT_W).setHeight(VIEW_H))
                .withVisible(index == 0);
        group = group.withLayoutMode("TopScrolling");
        return group.withScrollbarStyle(ScrollbarStyle.defaultStyle());
    }

    // --- 0: status ---------------------------------------------------------------------------

    private GroupBuilder statusTab(SupporterService service, UUID uuid) {
        GroupBuilder tab = screen(0);
        int cardW = (BODY_W - 32) / 3;
        String[] labels = {"REMAINING", "ALL TIME", "BALANCE"};
        String[] values = {
            String.valueOf(Math.max(0L, service.daysRemaining(uuid))),
            String.valueOf(service.get(uuid).map(r -> r.totalDays()).orElse(0)),
            String.valueOf(service.tokenBalance(uuid)),
        };
        String[] units = {"days", "days total", "tokens"};

        for (int i = 0; i < 3; i++) {
            int x = PAD + (i * (cardW + 16));
            tab = (GroupBuilder) tab.addChild(box("FlatStat" + i,
                    i == 0 ? theme.accentWash() : FlatTheme.CARD_BG, x, BODY_TOP, cardW, 118));
            tab = (GroupBuilder) tab.addChild(
                    hairlineBox("FlatStatEdge" + i, x, BODY_TOP, cardW, 118));
            tab = (GroupBuilder) tab.addChild(text("FlatStatLabel" + i, labels[i],
                    theme.eyebrow(FlatTheme.INK_LABEL), BODY_TOP + 20, x + 20, cardW - 40, 16));
            tab = (GroupBuilder) tab.addChild(text("FlatStatValue" + i, values[i],
                    theme.numeral(i == 2 ? theme.accent() : FlatTheme.INK_PRIMARY),
                    BODY_TOP + 52, x + 20, cardW - 40, 48));
            tab = (GroupBuilder) tab.addChild(text("FlatStatUnit" + i, units[i],
                    theme.unit(), BODY_TOP + 72, x + 20, cardW - 40, 20));
        }

        // Everything below is measured from the viewport, not the content column.
        int collTop = BODY_TOP + 152;
        tab = (GroupBuilder) tab.addChild(text("FlatCollHead", "COLLECTION",
                theme.eyebrow(FlatTheme.INK_MUTED), collTop, PAD, 240, 16));

        List<String> trails = new ArrayList<>();
        List<String> gear = new ArrayList<>();
        List<String> pets = new ArrayList<>();
        List<String> skins = new ArrayList<>();
        for (String unlock : service.unlocks(uuid)) {
            if (unlock.startsWith("gear:")) {
                gear.add(unlock.substring(5));
            } else if (unlock.startsWith("pet:")) {
                pets.add(unlock.substring(4));
            } else if (unlock.startsWith("skin:")) {
                skins.add(unlock.substring(5));
            } else {
                trails.add(unlock);
            }
        }
        // Free items count as owned — a collection that only listed purchases would tell a new
        // supporter they own nothing while they wear a free cape.
        for (String id : plugin.config().trails().keySet()) {
            if (plugin.config().trailCost(id) <= 0) {
                trails.add(id);
            }
        }
        for (String name : SupporterCommand.PetSub.PETS.keySet()) {
            if (plugin.config().petCost(name) <= 0) {
                pets.add(name);
            }
        }

        String[] collLabels = {"TRAILS", "GEAR", "PETS", "SKINS"};
        List<List<String>> collItems = List.of(trails, gear, pets, skins);
        int collW = (BODY_W - 42) / 4;
        for (int i = 0; i < 4; i++) {
            int x = PAD + (i * (collW + 14));
            List<String> items = collItems.get(i);
            int cardTop = collTop + 34;
            tab = (GroupBuilder) tab.addChild(
                    box("FlatColl" + i, FlatTheme.CARD_BG_SOFT, x, cardTop, collW, 140));
            tab = (GroupBuilder) tab.addChild(
                    hairlineBox("FlatCollEdge" + i, x, cardTop, collW, 140));
            tab = (GroupBuilder) tab.addChild(text("FlatCollLabel" + i, collLabels[i],
                    theme.eyebrow(FlatTheme.INK_LABEL), cardTop + 18, x + 16, collW - 70, 16));
            tab = (GroupBuilder) tab.addChild(text("FlatCollCount" + i,
                    String.valueOf(items.size()),
                    right(theme.small(FlatTheme.INK_PRIMARY)), cardTop + 16, x + collW - 60,
                    44, 18));

            // One column: two-up chips truncated "jack-sparrow" even at the smallest shrink.
            int chipW = collW - 32;
            int shown = items.size() > 3 ? 2 : Math.min(3, items.size());
            for (int j = 0; j < shown; j++) {
                int cy = cardTop + 44 + (j * 28);
                tab = (GroupBuilder) tab.addChild(
                        box("FlatChip" + i + "_" + j, FlatTheme.CHIP_BG, x + 16, cy, chipW, 24));
                tab = (GroupBuilder) tab.addChild(
                        text("FlatChipT" + i + "_" + j, items.get(j), theme.chip(),
                                cy + 4, x + 20, chipW - 8, 18));
            }
            if (items.size() > shown) {
                tab = (GroupBuilder) tab.addChild(text("FlatChipMore" + i,
                        "+" + (items.size() - shown) + " more",
                        theme.small(FlatTheme.INK_MUTED), cardTop + 104, x + 20, chipW, 18));
            }
        }
        return tab;
    }

    // --- 1: perks ----------------------------------------------------------------------------

    private GroupBuilder perksTab(SupporterService service, UUID uuid) {
        GroupBuilder tab = screen(1);
        SupporterConfig config = plugin.config();
        String[][] perks = {
            {"Chat identity", "A tag, your own title and your own colour."},
            {"Particle trails", config.trails().size() + " to choose from, two free."},
            {"Capes", SupporterCommand.CapeSub.DESIGNS.size() + " designs, six of them free."},
            {"Headwear and shoes", SupporterCommand.HatSub.HATS.size()
                    + " hats plus trainers, worn from the Wardrobe."},
            {"Pets", SupporterCommand.PetSub.PETS.size()
                    + " cosmetic followers. They cannot fight."},
            {"Body skins", "Metallic tints and full costumes, kept across relogs."},
            {"Homes", config.supporterHomeSlots() + " instead of " + config.defaultHomeSlots()
                    + "."},
            {"Tokens and quests", config.tokensPerMonth() + " a month, plus "
                    + config.questDailyReward() + " per daily quest."},
        };
        int cardW = (BODY_W - 14) / 2;
        for (int i = 0; i < perks.length; i++) {
            int x = PAD + ((i % 2) * (cardW + 14));
            int y = BODY_TOP + ((i / 2) * 82);
            tab = (GroupBuilder) tab.addChild(box("FlatPerk" + i, FlatTheme.CARD_BG, x, y,
                    cardW, 70));
            tab = (GroupBuilder) tab.addChild(hairlineBox("FlatPerkE" + i, x, y, cardW, 70));
            tab = (GroupBuilder) tab.addChild(box("FlatPerkM" + i, theme.accent(),
                    x + 20, y + 26, 7, 7));
            tab = (GroupBuilder) tab.addChild(text("FlatPerkT" + i, perks[i][0],
                    theme.small(FlatTheme.INK_PRIMARY), y + 16, x + 36, cardW - 56, 20));
            tab = (GroupBuilder) tab.addChild(text("FlatPerkD" + i, perks[i][1],
                    theme.small(FlatTheme.INK_SECONDARY), y + 38, x + 36, cardW - 56, 22));
        }
        int footY = BODY_TOP + (((perks.length + 1) / 2) * 82) + 12;
        tab = (GroupBuilder) tab.addChild(text("FlatPerkFoot",
                "All of it is cosmetic or convenience. None of it touches combat, claims, "
                        + "power, outpost income, KOTJ or bucks.",
                theme.small(FlatTheme.INK_MUTED), footY, PAD, BODY_W, 24));
        tab = (GroupBuilder) tab.addChild(text("FlatPerkYou",
                service.isSupporter(uuid) ? "You have all of this now - thank you."
                        : "You are not a supporter yet. The About tab explains how.",
                theme.small(service.isSupporter(uuid)
                        ? FlatTheme.GREEN_TEXT : FlatTheme.INK_SECONDARY),
                footY + 26, PAD, BODY_W, 24));
        return tab;
    }

    // --- 2: shop -----------------------------------------------------------------------------

    private GroupBuilder shopTab(SupporterService service, UUID uuid, World world) {
        GroupBuilder tab = screen(2);
        SupporterConfig config = plugin.config();
        if (!service.isSupporter(uuid)) {
            return (GroupBuilder) tab.addChild(text("FlatShopLocked",
                    "The shop is for supporters. The About tab explains how to become one.",
                    theme.body(FlatTheme.INK_SECONDARY), BODY_TOP, PAD, BODY_W, 40));
        }

        int y = BODY_TOP;
        // Trails.
        tab = (GroupBuilder) tab.addChild(sectionHead("FlatShopTrailsH", "TRAILS",
                "worn from the Trails tab", y));
        y += HEAD_H;
        List<String> trailIds = new ArrayList<>(config.trails().keySet());
        trailIds.sort(Comparator.comparingInt(config::trailCost).thenComparing(id -> id));
        List<String> pricedTrails = new ArrayList<>();
        for (String id : trailIds) {
            if (config.trailCost(id) > 0 && !service.unlocks(uuid).contains(id)) {
                pricedTrails.add(id);
            }
        }
        for (String id : trailIds) {
            if (config.trailCost(id) > 0 && service.unlocks(uuid).contains(id)) {
                pricedTrails.add(id);
            }
        }
        int i = 0;
        for (String id : pricedTrails) {
            boolean owned = service.unlocks(uuid).contains(id);
            int cost = config.trailCost(id);
            tab = (GroupBuilder) tab.addChild(shopChip("FlatBuyT_" + id, id, cost, owned,
                    i, y, (Void v, UIContext ctx) -> buyTrail(service, uuid, id, world, ctx)));
            i++;
        }
        y += rows(i) * CHIP_ROW + 10;

        // Gear and pets share a section: one story, and the Wardrobe is where both get worn.
        tab = (GroupBuilder) tab.addChild(sectionHead("FlatShopGearH", "GEAR AND PETS",
                "delivered from the Wardrobe tab", y));
        y += HEAD_H;
        i = 0;
        for (boolean ownedPass : new boolean[] {false, true}) {
            for (String name : gearNames()) {
                int cost = config.gearCost(name);
                if (cost <= 0 || service.ownsGear(uuid, name, cost) != ownedPass) {
                    continue;
                }
                final int c = cost;
                tab = (GroupBuilder) tab.addChild(shopChip("FlatBuyG_" + name, name, cost,
                        ownedPass, i, y,
                        (Void v, UIContext ctx) -> buyGear(service, uuid, name, c, world, ctx)));
                i++;
            }
            for (String name : SupporterCommand.PetSub.PETS.keySet()) {
                int cost = config.petCost(name);
                if (cost <= 0 || service.ownsPet(uuid, name, cost) != ownedPass) {
                    continue;
                }
                final int c = cost;
                tab = (GroupBuilder) tab.addChild(shopChip("FlatBuyP_" + name, name, cost,
                        ownedPass, i, y,
                        (Void v, UIContext ctx) -> buyPet(service, uuid, name, c, world, ctx)));
                i++;
            }
        }
        y += rows(i) * CHIP_ROW + 10;

        // Skins.
        tab = (GroupBuilder) tab.addChild(sectionHead("FlatShopSkinsH", "SKINS",
                "tints and costumes, worn from the Wardrobe tab", y));
        y += HEAD_H;
        i = 0;
        for (boolean ownedPass : new boolean[] {false, true}) {
            for (String name : SkinChanger.allNames()) {
                int cost = skinCost(name);
                if (cost <= 0 || service.ownsSkin(uuid, name, cost) != ownedPass) {
                    continue;
                }
                final int c = cost;
                tab = (GroupBuilder) tab.addChild(shopChip("FlatBuyS_" + name, name, cost,
                        ownedPass, i, y,
                        (Void v, UIContext ctx) -> buySkin(service, uuid, name, c, world, ctx)));
                i++;
            }
        }
        return tab;
    }

    /** The label a Shop chip takes once bought — one place, so it matches how it was built. */
    private static String ownedLabel(String name) {
        return name + " · owned";
    }

    /** Shop chips are five per row: prices make the longest labels 18 characters. */
    private CustomButtonBuilder shopChip(String id, String name, int cost, boolean owned,
                                         int index, int y, BiConsumer<Void, UIContext> onClick) {
        int w = (BODY_W - 40) / 5;
        int x = PAD + ((index % 5) * (w + 10));
        int top = y + ((index / 5) * CHIP_ROW);
        String label = owned ? ownedLabel(name) : name + " · " + cost;
        return chip(id, label, x, top, w,
                owned ? FlatTheme.GREEN_TEXT : FlatTheme.INK_BODY,
                owned ? FlatTheme.OWNED_BG : FlatTheme.CHIP_BG, onClick);
    }

    private static int rows(int count) {
        return Math.max(1, (count + 4) / 5);
    }

    // --- 3: quests ---------------------------------------------------------------------------

    private GroupBuilder questsTab(SupporterService service, UUID uuid, World world) {
        GroupBuilder tab = screen(3);
        if (!service.isSupporter(uuid)) {
            return (GroupBuilder) tab.addChild(text("FlatQuestLocked",
                    "Quests are a supporter perk. The About tab explains how to become one.",
                    theme.body(FlatTheme.INK_SECONDARY), BODY_TOP, PAD, BODY_W, 40));
        }
        SupporterConfig config = plugin.config();
        List<SupporterService.QuestState> quests = service.quests(uuid);

        int y = BODY_TOP;
        tab = (GroupBuilder) tab.addChild(sectionHead("FlatQDailyH", "DAILY",
                config.questDailyReward() + " tokens each, resets 00:00 UTC", y));
        y += HEAD_H;
        int n = 0;
        for (SupporterService.QuestState q : quests) {
            if (!q.daily()) {
                continue;
            }
            tab = questRow(tab, service, uuid, q, world, y, n++);
            y += 66;
        }

        y += 10;
        tab = (GroupBuilder) tab.addChild(sectionHead("FlatQWeeklyH", "WEEKLY",
                config.questWeeklyReward() + " tokens", y));
        y += HEAD_H;
        for (SupporterService.QuestState q : quests) {
            if (q.daily()) {
                continue;
            }
            tab = questRow(tab, service, uuid, q, world, y, n++);
            y += 66;
        }

        tab = (GroupBuilder) tab.addChild(text("FlatQNote",
                "Progress ticks about once a minute while you play. Unclaimed quests expire "
                        + "at the reset.", theme.small(FlatTheme.INK_MUTED),
                y + 12, PAD, BODY_W, 24));
        return tab;
    }

    private GroupBuilder questRow(GroupBuilder tab, SupporterService service, UUID uuid,
                                  SupporterService.QuestState q, World world, int y, int n) {
        boolean done = q.complete();
        String bg = done ? FlatTheme.OWNED_BG : FlatTheme.CARD_BG;
        tab = (GroupBuilder) tab.addChild(box("FlatQ" + n, bg, PAD, y, BODY_W, 56));
        tab = (GroupBuilder) tab.addChild(hairlineBox("FlatQE" + n, PAD, y, BODY_W, 56));
        tab = (GroupBuilder) tab.addChild(box("FlatQDot" + n,
                done ? FlatTheme.GREEN : FlatTheme.white(0.16), PAD + 20, y + 25, 7, 7));
        tab = (GroupBuilder) tab.addChild(text("FlatQT" + n, q.label(),
                theme.small(done ? FlatTheme.GREEN_TEXT : FlatTheme.INK_BODY),
                y + 12, PAD + 38, 420, 20));
        tab = (GroupBuilder) tab.addChild(text("FlatQP" + n,
                q.progress() + " / " + q.target(),
                theme.small(FlatTheme.INK_SECONDARY), y + 32, PAD + 38, 420, 18));

        // A bar rather than a number alone: partial progress is the whole story of a quest.
        int barW = 300;
        int barX = PAD + BODY_W - barW - 150;
        tab = (GroupBuilder) tab.addChild(
                box("FlatQBar" + n, FlatTheme.METER_TRACK, barX, y + 26, barW, 5));
        int fill = q.target() <= 0 ? 0
                : Math.min(barW, Math.round((float) barW * q.progress() / q.target()));
        if (fill > 0) {
            tab = (GroupBuilder) tab.addChild(box("FlatQFill" + n,
                    done ? FlatTheme.GREEN : theme.accent(), barX, y + 26, fill, 5));
        }

        if (q.claimable()) {
            String claimId = "FlatQClaim" + n;
            tab = (GroupBuilder) tab.addChild(chip(claimId, "CLAIM",
                    PAD + BODY_W - 130, y + 12, 110, FlatTheme.GREEN_TEXT, FlatTheme.OWNED_BG,
                    (Void v, UIContext ctx) ->
                            claimQuest(service, uuid, q.key(), claimId, world, ctx)));
        } else {
            tab = (GroupBuilder) tab.addChild(text("FlatQState" + n,
                    q.claimed() ? "CLAIMED" : "",
                    right(theme.eyebrow(FlatTheme.GREEN_TEXT)), y + 20, PAD + BODY_W - 140, 120,
                    18));
        }
        return tab;
    }

    // --- 4: trails ---------------------------------------------------------------------------

    private GroupBuilder trailsTab(SupporterService service, UUID uuid, World world) {
        GroupBuilder tab = screen(4);
        SupporterIdentity identity = service.identity(uuid);

        tab = (GroupBuilder) tab.addChild(
                box("FlatTrBanner", theme.accentWash(), PAD, BODY_TOP, BODY_W, 84));
        tab = (GroupBuilder) tab.addChild(
                hairlineBox("FlatTrBannerE", PAD, BODY_TOP, BODY_W, 84));
        tab = (GroupBuilder) tab.addChild(text("FlatTrLabel", "WEARING",
                theme.eyebrow(FlatTheme.INK_LABEL), BODY_TOP + 18, PAD + 22, 200, 16));
        tab = (GroupBuilder) tab.addChild(text("FlatTrCurrent",
                identity.hasTrail() ? identity.trail() : "nothing",
                theme.numeralSmall(theme.accent()), BODY_TOP + 40, PAD + 22, 300, 30));

        boolean supporter = service.isSupporter(uuid);
        if (supporter) {
            tab = (GroupBuilder) tab.addChild(chip("FlatTrOff", "OFF",
                    PAD + BODY_W - 120, BODY_TOP + 26, 100, FlatTheme.INK_BODY,
                    FlatTheme.CHIP_BG,
                    (Void v, UIContext ctx) -> wearTrail(service, uuid, null, world, ctx)));
        }
        tab = (GroupBuilder) tab.addChild(chip("FlatTrToggle",
                identity.hideTrails() ? "OTHERS' TRAILS: HIDDEN" : "OTHERS' TRAILS: SHOWN",
                PAD + BODY_W - 380, BODY_TOP + 26, 250, FlatTheme.INK_BODY, FlatTheme.CHIP_BG,
                (Void v, UIContext ctx) -> toggleTrails(service, uuid, world, ctx)));

        if (!supporter) {
            return (GroupBuilder) tab.addChild(text("FlatTrLocked",
                    "Trails are a supporter perk, but hiding other people's is free.",
                    theme.body(FlatTheme.INK_SECONDARY), BODY_TOP + 100, PAD, BODY_W, 40));
        }

        int y = BODY_TOP + 100;
        tab = (GroupBuilder) tab.addChild(sectionHead("FlatTrHead", "YOUR TRAILS",
                "locked ones are in the Shop tab", y));
        y += HEAD_H;
        List<String> ids = new ArrayList<>(plugin.config().trails().keySet());
        ids.sort(Comparator.comparingInt(plugin.config()::trailCost).thenComparing(id -> id));
        int i = 0;
        for (boolean ownedPass : new boolean[] {true, false}) {
            for (String id : ids) {
                boolean wearable = plugin.config().trailCost(id) <= 0
                        || service.unlocks(uuid).contains(id);
                if (wearable != ownedPass) {
                    continue;
                }
                boolean worn = id.equals(identity.trail());
                int w = (BODY_W - 50) / 6;
                int x = PAD + ((i % 6) * (w + 10));
                int top = y + ((i / 6) * CHIP_ROW);
                tab = (GroupBuilder) tab.addChild(chip("FlatTrChip_" + id, id, x, top, w,
                        worn ? theme.accentBright()
                                : wearable ? FlatTheme.INK_BODY : FlatTheme.INK_MUTED,
                        worn ? theme.accentNav() : FlatTheme.CHIP_BG,
                        (Void v, UIContext ctx) -> wearTrail(service, uuid, id, world, ctx)));
                i++;
            }
        }
        return tab;
    }

    // --- 5: chat -----------------------------------------------------------------------------

    private GroupBuilder chatTab(SupporterService service, UUID uuid, World world) {
        GroupBuilder tab = screen(5);
        if (!service.isSupporter(uuid)) {
            return (GroupBuilder) tab.addChild(text("FlatChatLocked",
                    "Chat identity is a supporter perk. The About tab explains how.",
                    theme.body(FlatTheme.INK_SECONDARY), BODY_TOP, PAD, BODY_W, 40));
        }
        SupporterIdentity identity = service.identity(uuid);
        int leftW = 520;

        tab = (GroupBuilder) tab.addChild(text("FlatChatTitleL", "TITLE",
                theme.eyebrow(FlatTheme.INK_LABEL), BODY_TOP, PAD, 200, 16));
        TextFieldBuilder field = (TextFieldBuilder) TextFieldBuilder.textInput()
                .withId(CHAT_FIELD_ID)
                .withAnchor(new HyUIAnchor().setTop(BODY_TOP + 24).setLeft(PAD)
                        .setWidth(leftW).setHeight(38));
        field = field.withPlaceholderText("Your title...")
                .withValue(identity.title() == null ? "" : identity.title())
                // Load-bearing no-op: HyUI only stores a field's value while dispatching a
                // listener for that event, so without this the Set button reads the initial
                // value forever no matter what was typed.
                .addEventListener(CustomUIEventBindingType.ValueChanged, String.class, v -> { });
        tab = (GroupBuilder) tab.addChild(field);

        tab = (GroupBuilder) tab.addChild(chip("FlatChatSet", "SET", PAD, BODY_TOP + 72, 120,
                theme.accentBright(), theme.accentNav(),
                (Void v, UIContext ctx) -> setTitle(service, uuid, world, ctx, false)));
        tab = (GroupBuilder) tab.addChild(chip("FlatChatClear", "CLEAR", PAD + 130,
                BODY_TOP + 72, 120, FlatTheme.INK_BODY, FlatTheme.CHIP_BG,
                (Void v, UIContext ctx) -> setTitle(service, uuid, world, ctx, true)));

        tab = (GroupBuilder) tab.addChild(text("FlatChatColourL", "COLOUR",
                theme.eyebrow(FlatTheme.INK_LABEL), BODY_TOP + 130, PAD, 200, 16));
        List<String> colours = plugin.config().allowedChatColors();
        int sw = (leftW - 20) / 3;
        for (int i = 0; i < colours.size(); i++) {
            String hex = colours.get(i);
            int x = PAD + ((i % 3) * (sw + 10));
            int y = BODY_TOP + 154 + ((i / 3) * CHIP_ROW);
            boolean on = hex.equalsIgnoreCase(identity.chatColor());
            tab = (GroupBuilder) tab.addChild(box("FlatSwDot" + i, hex, x + 12, y + 12, 12, 12));
            tab = (GroupBuilder) tab.addChild(chip("FlatSw" + i, "      " + hex, x, y, sw,
                    hex, on ? theme.accentNav() : FlatTheme.CHIP_BG,
                    (Void v, UIContext ctx) -> setColor(service, uuid, hex, world, ctx)));
        }
        int afterSwatches = BODY_TOP + 154 + (((colours.size() + 2) / 3) * CHIP_ROW);
        tab = (GroupBuilder) tab.addChild(chip("FlatSwDefault", "DEFAULT", PAD, afterSwatches,
                140, FlatTheme.INK_BODY, FlatTheme.CHIP_BG,
                (Void v, UIContext ctx) -> setColor(service, uuid, null, world, ctx)));

        // Live preview — new against the live panel, and the reason the colour list is worth
        // clicking through rather than guessing at.
        int px = PAD + leftW + 40;
        int pw = BODY_W - leftW - 40;
        tab = (GroupBuilder) tab.addChild(text("FlatPrevL", "PREVIEW",
                theme.eyebrow(FlatTheme.INK_LABEL), BODY_TOP, px, 200, 16));
        tab = (GroupBuilder) tab.addChild(
                box("FlatPrev", FlatTheme.CARD_BG, px, BODY_TOP + 24, pw, 96));
        tab = (GroupBuilder) tab.addChild(
                hairlineBox("FlatPrevE", px, BODY_TOP + 24, pw, 96));
        tab = (GroupBuilder) tab.addChild(text("FlatPrevTag", chatTagPreview(identity),
                theme.small(identity.hasColor() ? identity.chatColor() : theme.accent()),
                BODY_TOP + 46, px + 18, pw - 36, 20));
        tab = (GroupBuilder) tab.addChild(text("FlatPrevMsg", "You: hey",
                theme.small(FlatTheme.INK_BODY), BODY_TOP + 72, px + 18, pw - 36, 20));
        return tab;
    }

    private String chatTagPreview(SupporterIdentity identity) {
        return identity.hasTitle() ? "[" + identity.title() + "]" : "[Supporter]";
    }

    // --- 6: wardrobe -------------------------------------------------------------------------

    private GroupBuilder wardrobeTab(SupporterService service, UUID uuid,
                                     PlayerRef playerRef, World world) {
        GroupBuilder tab = screen(6);
        if (!service.isSupporter(uuid)) {
            return (GroupBuilder) tab.addChild(text("FlatWdLocked",
                    "The wardrobe is a supporter perk. The About tab explains how.",
                    theme.body(FlatTheme.INK_SECONDARY), BODY_TOP, PAD, BODY_W, 40));
        }
        SupporterIdentity identity = service.identity(uuid);
        int y = BODY_TOP;

        // addChild is IMMUTABLE — every call must be reassigned or the child is silently
        // dropped. The first draft of this method routed section heads through a helper that
        // discarded the returned builder, and every Wardrobe heading would have vanished with
        // no error at all. Inline and reassigned, so the trap has nowhere to hide.
        tab = (GroupBuilder) tab.addChild(
                sectionHead("FlatWdHCapes", "CAPES", "one chest item at a time", y));
        y += HEAD_H;
        int i = 0;
        for (java.util.Map.Entry<String, String> e
                : SupporterCommand.CapeSub.DESIGNS.entrySet()) {
            String name = e.getKey();
            String item = e.getValue();
            if (!service.ownsGear(uuid, name, plugin.config().gearCost(name))) {
                continue;
            }
            tab = (GroupBuilder) tab.addChild(wardrobeChip("FlatWdCape_" + name, name, i, y,
                    CAPE_COLOURS.getOrDefault(name, FlatTheme.INK_BODY),
                    (Void v, UIContext ctx) -> giveWearable(service, uuid, playerRef, name,
                            "Cape (" + name + ")", item, world, ctx)));
            i++;
        }
        y += rows7(i) * CHIP_ROW + 8;

        tab = (GroupBuilder) tab.addChild(
                sectionHead("FlatWdHHats", "HEADWEAR", "one head item at a time", y));
        y += HEAD_H;
        i = 0;
        for (java.util.Map.Entry<String, String> e : SupporterCommand.HatSub.HATS.entrySet()) {
            String name = e.getKey();
            String item = e.getValue();
            if (!service.ownsGear(uuid, name, plugin.config().gearCost(name))) {
                continue;
            }
            tab = (GroupBuilder) tab.addChild(wardrobeChip("FlatWdHat_" + name, name, i, y,
                    FlatTheme.INK_BODY,
                    (Void v, UIContext ctx) -> giveWearable(service, uuid, playerRef, name,
                            "Headwear (" + name + ")", item, world, ctx)));
            i++;
        }
        y += rows7(i) * CHIP_ROW + 8;

        tab = (GroupBuilder) tab.addChild(
                sectionHead("FlatWdHFeet", "FEET", "uses the legs armour slot", y));
        y += HEAD_H;
        i = 0;
        for (java.util.Map.Entry<String, String> e
                : SupporterCommand.ShoesSub.SHOES.entrySet()) {
            String name = e.getKey();
            String item = e.getValue();
            if (!service.ownsGear(uuid, name, plugin.config().gearCost(name))) {
                continue;
            }
            tab = (GroupBuilder) tab.addChild(wardrobeChip("FlatWdShoe_" + name, name, i, y,
                    FlatTheme.INK_BODY,
                    (Void v, UIContext ctx) -> giveWearable(service, uuid, playerRef, name,
                            "Footwear (" + name + ")", item, world, ctx)));
            i++;
        }
        y += rows7(i) * CHIP_ROW + 8;

        tab = (GroupBuilder) tab.addChild(sectionHead("FlatWdHPets", "PETS",
                "one follower at a time; they cannot fight", y));
        y += HEAD_H;
        tab = (GroupBuilder) tab.addChild(wardrobeChip("FlatWdPetOff", "no pet", 0, y,
                FlatTheme.INK_BODY,
                (Void v, UIContext ctx) -> wearPet(service, uuid, playerRef, null, world, ctx)));
        i = 1;
        for (String name : SupporterCommand.PetSub.PETS.keySet()) {
            if (!service.ownsPet(uuid, name, plugin.config().petCost(name))) {
                continue;
            }
            boolean worn = name.equals(identity.pet());
            tab = (GroupBuilder) tab.addChild(wardrobeChip("FlatWdPet_" + name, name, i, y,
                    worn ? theme.accentBright() : FlatTheme.INK_BODY,
                    (Void v, UIContext ctx) ->
                            wearPet(service, uuid, playerRef, name, world, ctx)));
            i++;
        }
        y += rows7(i) * CHIP_ROW + 8;

        tab = (GroupBuilder) tab.addChild(sectionHead("FlatWdHSkins", "BODY SKINS",
                "statue mode; stays on across relogs", y));
        y += HEAD_H;
        tab = (GroupBuilder) tab.addChild(wardrobeChip("FlatWdSkinOff", "off", 0, y,
                FlatTheme.INK_BODY,
                (Void v, UIContext ctx) -> setSkin(service, uuid, playerRef, null, world, ctx)));
        i = 1;
        for (String name : SkinChanger.allNames()) {
            if (!service.ownsSkin(uuid, name, skinCost(name))) {
                continue;
            }
            boolean worn = name.equalsIgnoreCase(identity.skin());
            tab = (GroupBuilder) tab.addChild(wardrobeChip("FlatWdSkin_" + name, name, i, y,
                    worn ? theme.accentBright() : FlatTheme.INK_BODY,
                    (Void v, UIContext ctx) ->
                            setSkin(service, uuid, playerRef, name, world, ctx)));
            i++;
        }
        return tab;
    }

    /**
     * Wardrobe chips run seven per row; names here carry no price suffix.
     *
     * <p><b>Locked items are not built at all</b>, rather than built and hidden. The Wardrobe
     * has meant "what you own" since 0.19.2, and the built-and-hidden trick existed only so a
     * Shop purchase could reveal a chip without reopening the panel — which this panel does not
     * do anyway, by the deliberate choice in {@link #finish}. Keeping the hidden chips reserved
     * their grid rows, which is what left the Pets and Body Skins sections with a hole where
     * two invisible rows used to be.
     */
    private CustomButtonBuilder wardrobeChip(String id, String name, int index, int y,
                                             String textColour,
                                             BiConsumer<Void, UIContext> onClick) {
        int w = (BODY_W - 60) / 7;
        int x = PAD + ((index % 7) * (w + 10));
        int top = y + ((index / 7) * CHIP_ROW);
        return chip(id, name, x, top, w, textColour, FlatTheme.CHIP_BG, onClick);
    }

    private static int rows7(int count) {
        return Math.max(1, (count + 6) / 7);
    }

    // --- 7: about ----------------------------------------------------------------------------

    private GroupBuilder aboutTab() {
        GroupBuilder tab = screen(7);
        SupporterConfig config = plugin.config();
        tab = (GroupBuilder) tab.addChild(text("FlatAbLede",
                "The server is free to play for everyone. Supporter is for those who can spare "
                        + "it - every donation goes towards upkeep and future updates.",
                theme.body(FlatTheme.INK_BODY), BODY_TOP, PAD, 640, 60));

        tab = (GroupBuilder) tab.addChild(
                box("FlatAbCallout", FlatTheme.OWNED_BG, PAD, BODY_TOP + 76, BODY_W, 64));
        tab = (GroupBuilder) tab.addChild(
                box("FlatAbCalloutBar", FlatTheme.GREEN, PAD, BODY_TOP + 76, 2, 64));
        tab = (GroupBuilder) tab.addChild(text("FlatAbCalloutT",
                "Everything supporter rank gives you is cosmetic or convenience. It does not "
                        + "touch combat, claims, power, outpost income, KOTJ or bucks.",
                theme.small(FlatTheme.GREEN_TEXT), BODY_TOP + 92, PAD + 22, BODY_W - 44, 44));

        int y = BODY_TOP + 164;
        tab = (GroupBuilder) tab.addChild(text("FlatAbPriceL", "PRICE",
                theme.eyebrow(FlatTheme.INK_MUTED), y, PAD, 200, 16));
        y += 26;
        List<String> lines = config.priceLines();
        if (lines.isEmpty()) {
            tab = (GroupBuilder) tab.addChild(text("FlatAbNoPrice",
                    "Pricing is not set up yet - ask an admin.",
                    theme.body(FlatTheme.INK_SECONDARY), y, PAD, BODY_W, 30));
            return tab;
        }
        // Cards share the width evenly and WRAP: priceLines is free text an admin writes, so a
        // fixed 260px card silently truncated the token line to "...earns 100...". Nothing in
        // this panel may quietly eat an admin's own copy.
        int cardW = (BODY_W - ((lines.size() - 1) * 14)) / Math.max(1, lines.size());
        for (int i = 0; i < lines.size(); i++) {
            int x = PAD + (i * (cardW + 14));
            tab = (GroupBuilder) tab.addChild(box("FlatAbCard" + i,
                    i == 1 ? theme.accentWash() : FlatTheme.CARD_BG, x, y, cardW, 76));
            tab = (GroupBuilder) tab.addChild(hairlineBox("FlatAbCardE" + i, x, y, cardW, 76));
            tab = (GroupBuilder) tab.addChild(text("FlatAbCardT" + i, lines.get(i),
                    theme.body(FlatTheme.INK_PRIMARY), y + 18, x + 18, cardW - 36, 48));
        }
        y += 96;
        // No hardcoded token line here: priceLines is the admin's to write, and this panel
        // printed its own copy of the same sentence directly under theirs.
        tab = (GroupBuilder) tab.addChild(text("FlatAbWhere",
                config.storeUrl().isEmpty()
                        ? "Where: ask an admin - the store is not open yet."
                        : "Where: " + config.storeUrl(),
                theme.small(FlatTheme.INK_MUTED), y + 26, PAD, BODY_W, 24));
        return tab;
    }

    // --- actions -----------------------------------------------------------------------------

    private void buyTrail(SupporterService service, UUID uuid, String id, World world,
                          UIContext ctx) {
        String note;
        boolean bought = false;
        try {
            SupporterService.PurchaseResult result = service.purchaseTrail(uuid, id);
            bought = result == SupporterService.PurchaseResult.BOUGHT;
            note = switch (result) {
                case BOUGHT -> "Bought " + id + " - wear it from the Trails tab.";
                case ALREADY_OWNED -> "You already own " + id + ".";
                case NOT_ENOUGH_TOKENS -> "Not enough tokens for " + id + ".";
                case FREE -> id + " is free - the Trails tab has it.";
                case UNKNOWN_ITEM -> "That trail no longer exists.";
            };
        } catch (RuntimeException e) {
            plugin.log().error("Trail purchase failed for " + uuid, e);
            note = "Purchase failed - try /supporter buy " + id;
        }
        finish(service, uuid, world, ctx, note, "FlatBuyT_" + id, ownedLabel(id), bought);
    }

    private void buyGear(SupporterService service, UUID uuid, String name, int cost,
                         World world, UIContext ctx) {
        String note;
        boolean bought = false;
        try {
            SupporterService.PurchaseResult result = service.purchaseGear(uuid, name, cost);
            bought = result == SupporterService.PurchaseResult.BOUGHT;
            note = switch (result) {
                case BOUGHT -> "Unlocked " + name
                        + " - reopen the panel and the Wardrobe will have it.";
                case ALREADY_OWNED -> "You already own " + name + ".";
                case NOT_ENOUGH_TOKENS -> "Not enough tokens for " + name + ".";
                case FREE -> name + " is free - the Wardrobe tab has it.";
                case UNKNOWN_ITEM -> "That item no longer exists.";
            };
        } catch (RuntimeException e) {
            plugin.log().error("Gear purchase failed for " + uuid, e);
            note = "Purchase failed - try /supporter buy " + name;
        }
        finish(service, uuid, world, ctx, note, "FlatBuyG_" + name, ownedLabel(name), bought);
    }

    private void buyPet(SupporterService service, UUID uuid, String name, int cost,
                        World world, UIContext ctx) {
        String note;
        boolean bought = false;
        try {
            SupporterService.PurchaseResult result = service.purchasePet(uuid, name, cost);
            bought = result == SupporterService.PurchaseResult.BOUGHT;
            note = switch (result) {
                case BOUGHT -> "Unlocked " + name
                        + " - reopen the panel and the Wardrobe will have it.";
                case ALREADY_OWNED -> "You already own " + name + ".";
                case NOT_ENOUGH_TOKENS -> "Not enough tokens for " + name + ".";
                case FREE -> name + " is free - the Wardrobe tab has it.";
                case UNKNOWN_ITEM -> "That pet no longer exists.";
            };
        } catch (RuntimeException e) {
            plugin.log().error("Pet purchase failed for " + uuid, e);
            note = "Purchase failed - try /supporter buy " + name;
        }
        finish(service, uuid, world, ctx, note, "FlatBuyP_" + name, ownedLabel(name), bought);
    }

    private void buySkin(SupporterService service, UUID uuid, String name, int cost,
                         World world, UIContext ctx) {
        String note;
        boolean bought = false;
        try {
            SupporterService.PurchaseResult result = service.purchaseSkin(uuid, name, cost);
            bought = result == SupporterService.PurchaseResult.BOUGHT;
            note = switch (result) {
                case BOUGHT -> "Unlocked " + name
                        + " - reopen the panel and the Wardrobe will have it.";
                case ALREADY_OWNED -> "You already own " + name + ".";
                case NOT_ENOUGH_TOKENS -> "Not enough tokens for " + name + ".";
                case FREE -> name + " is free - the Wardrobe tab has it.";
                case UNKNOWN_ITEM -> "That skin no longer exists.";
            };
        } catch (RuntimeException e) {
            plugin.log().error("Skin purchase failed for " + uuid, e);
            note = "Purchase failed - try /supporter buy " + name;
        }
        finish(service, uuid, world, ctx, note, "FlatBuyS_" + name, ownedLabel(name), bought);
    }

    private void claimQuest(SupporterService service, UUID uuid, String key, String claimId,
                            World world, UIContext ctx) {
        String note;
        boolean claimed = false;
        try {
            SupporterService.QuestClaimResult result = service.claimQuest(uuid, key);
            claimed = result == SupporterService.QuestClaimResult.CLAIMED;
            note = switch (result) {
                case CLAIMED -> "Quest complete - tokens added to your balance!";
                case NOT_DONE -> "Not finished yet - keep going.";
                case ALREADY_CLAIMED -> "Already claimed.";
                case NOT_SUPPORTER -> "Quests are a supporter perk.";
                case UNKNOWN_QUEST -> "That quest expired at the reset - reopen the panel.";
            };
        } catch (RuntimeException e) {
            plugin.log().error("Quest claim failed for " + uuid, e);
            note = "Claim failed - try again in a moment.";
        }
        // A claimed quest's button becomes its own receipt, so the row cannot be clicked twice
        // and look like it did nothing the second time.
        finish(service, uuid, world, ctx, note, claimId, "CLAIMED", claimed);
    }

    private void wearTrail(SupporterService service, UUID uuid, String id, World world,
                           UIContext ctx) {
        String note;
        try {
            service.selectTrail(uuid, id);
            note = id == null ? "Trail off." : "Now wearing " + id + ".";
        } catch (IllegalArgumentException e) {
            note = e.getMessage();
        } catch (RuntimeException e) {
            plugin.log().error("selectTrail failed for " + uuid, e);
            note = "Could not save your trail - try /supporter trail " + id;
        }
        String message = note;
        world.execute(() -> {
            try {
                ctx.editById("FlatTrCurrent", LabelBuilder.class, l -> l.withText(
                        service.identity(uuid).hasTrail()
                                ? service.identity(uuid).trail() : "nothing"));
                ctx.editById(NOTICE_ID, LabelBuilder.class, l -> l.withText(message));
                ctx.updatePage(false);
            } catch (Throwable t) {
                plugin.log().warn("Trail refresh failed: " + t);
            }
        });
    }

    private void toggleTrails(SupporterService service, UUID uuid, World world, UIContext ctx) {
        String note;
        boolean hidden;
        try {
            hidden = !service.identity(uuid).hideTrails();
            service.setHideTrails(uuid, hidden);
            note = hidden ? "Other players' trails are now hidden for you."
                    : "Other players' trails are now shown.";
        } catch (RuntimeException e) {
            note = "Could not save: " + e.getMessage();
            hidden = service.identity(uuid).hideTrails();
        }
        String message = note;
        boolean nowHidden = hidden;
        world.execute(() -> {
            try {
                ctx.editById("FlatTrToggle", CustomButtonBuilder.class, b -> b.withText(
                        nowHidden ? "OTHERS' TRAILS: HIDDEN" : "OTHERS' TRAILS: SHOWN"));
                ctx.editById(NOTICE_ID, LabelBuilder.class, l -> l.withText(message));
                ctx.updatePage(false);
            } catch (Throwable t) {
                plugin.log().warn("Trail toggle refresh failed: " + t);
            }
        });
    }

    private void setTitle(SupporterService service, UUID uuid, World world, UIContext ctx,
                          boolean clear) {
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
                ctx.editById("FlatPrevTag", LabelBuilder.class,
                        l -> l.withText(chatTagPreview(service.identity(uuid))));
                ctx.editById(NOTICE_ID, LabelBuilder.class, l -> l.withText(message));
                ctx.updatePage(false);
            } catch (Throwable t) {
                plugin.log().warn("Chat refresh failed: " + t);
            }
        });
    }

    private void setColor(SupporterService service, UUID uuid, String hex, World world,
                          UIContext ctx) {
        String note;
        try {
            SupporterIdentity updated = service.setChatColor(uuid, hex);
            note = updated.hasColor() ? "Chat colour set to " + updated.chatColor()
                    : "Chat colour reset to the default.";
        } catch (IllegalArgumentException e) {
            note = e.getMessage();
        } catch (RuntimeException e) {
            plugin.log().error("setColor failed for " + uuid, e);
            note = "Could not save your colour - try /supporter colour";
        }
        String message = note;
        world.execute(() -> {
            try {
                SupporterIdentity now = service.identity(uuid);
                ctx.editById("FlatPrevTag", LabelBuilder.class, l -> l.withStyle(
                        theme.small(now.hasColor() ? now.chatColor() : theme.accent())));
                ctx.editById(NOTICE_ID, LabelBuilder.class, l -> l.withText(message));
                ctx.updatePage(false);
            } catch (Throwable t) {
                plugin.log().warn("Colour refresh failed: " + t);
            }
        });
    }

    private void giveWearable(SupporterService service, UUID uuid, PlayerRef playerRef,
                              String gearName, String label, String itemId, World world,
                              UIContext ctx) {
        world.execute(() -> {
            String note;
            try {
                int cost = plugin.config().gearCost(gearName);
                if (!service.isSupporter(uuid)) {
                    note = "The wardrobe is a supporter perk.";
                } else if (!service.ownsGear(uuid, gearName, cost)) {
                    note = gearName + " is locked - " + cost + " tokens in the Shop tab.";
                } else {
                    Ref<EntityStore> ref = playerRef.getReference();
                    if (ref == null || ref.getStore() == null) {
                        note = "Could not reach your inventory - try the chat command.";
                    } else {
                        ItemStackTransaction tx = Player.giveItem(
                                new ItemStack(itemId, 1), ref, ref.getStore());
                        ItemStack remainder = tx == null ? null : tx.getRemainder();
                        note = remainder != null && !remainder.isEmpty()
                                ? "No room in your inventory - clear a slot and try again."
                                : label + " delivered - check your inventory.";
                    }
                }
            } catch (Throwable t) {
                plugin.log().error("Wardrobe delivery failed for " + uuid, t);
                note = "Delivery failed - try the chat command instead.";
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

    private void wearPet(SupporterService service, UUID uuid, PlayerRef playerRef,
                         String petName, World world, UIContext ctx) {
        world.execute(() -> {
            String note;
            try {
                var pets = plugin.pets();
                Ref<EntityStore> ref = playerRef.getReference();
                Store<EntityStore> store = ref == null ? null : ref.getStore();
                if (pets == null || store == null) {
                    note = "Pets are unavailable right now.";
                } else if (petName == null) {
                    service.setPet(uuid, null);
                    pets.removeFor(uuid, store);
                    note = "Pet sent home.";
                } else {
                    int cost = plugin.config().petCost(petName);
                    if (!service.ownsPet(uuid, petName, cost)) {
                        note = petName + " is locked - " + cost + " tokens in the Shop tab.";
                    } else {
                        service.setPet(uuid, petName);
                        String role = SupporterCommand.PetSub.PETS.get(petName);
                        var transform = store.getComponent(ref,
                                com.hypixel.hytale.server.core.modules.entity.component
                                        .TransformComponent.getComponentType());
                        String failure = transform == null || transform.getPosition() == null
                                ? "it will appear in a moment"
                                : pets.spawn(store, uuid,
                                        new org.joml.Vector3d(transform.getPosition()), role);
                        note = failure == null ? "Pet out: " + petName + "!"
                                : "Saved: " + failure;
                    }
                }
            } catch (Throwable t) {
                plugin.log().error("Pet change failed for " + uuid, t);
                note = "Could not change your pet - try /supporter pet";
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

    private void setSkin(SupporterService service, UUID uuid, PlayerRef playerRef,
                         String skinName, World world, UIContext ctx) {
        boolean off = skinName == null;
        if (!off) {
            int cost = skinCost(skinName);
            if (!service.ownsSkin(uuid, skinName, cost)) {
                world.execute(() -> {
                    ctx.editById(NOTICE_ID, LabelBuilder.class, l -> l.withText(
                            skinName + " is locked - " + cost + " tokens in the Shop tab."));
                    ctx.updatePage(false);
                });
                return;
            }
        }
        try {
            service.setSkin(uuid, skinName);
        } catch (RuntimeException e) {
            plugin.log().error("setSkin failed for " + uuid, e);
        }
        world.execute(() -> {
            String note;
            try {
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref == null || ref.getStore() == null) {
                    note = "Saved - it will apply next login.";
                } else {
                    SkinChanger.Result result = off
                            ? SkinChanger.restore(ref.getStore(), ref, uuid)
                            : SkinChanger.applyByName(ref.getStore(), ref, uuid, skinName);
                    note = off ? "Skin off - you are fully restored."
                            : result.applied() ? "Skin on: " + skinName
                            : "Saved, but the visual push failed - relog to see it.";
                }
            } catch (Throwable t) {
                plugin.log().error("Skin change failed for " + uuid, t);
                note = "Saved, but the visual push failed - relog to see it.";
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

    /**
     * Rewrites everything a purchase changes: the wallet, the notice, and the chip that was
     * clicked — which flips to its owned styling in place, so a buy is visibly a buy.
     *
     * <p>Only the clicked chip is touched, and that is a property of this design rather than
     * restraint: prices here do not depend on the balance, so no other Shop chip can go stale.
     * (The old panel had to rewrite every row because each one showed how much more you needed.)
     *
     * <p><b>The Wardrobe is the deliberate exception.</b> It builds only what you own, so there
     * is no hidden chip waiting to be revealed — the alternative, keeping locked chips built and
     * hidden, is exactly what left those sections with two blank rows. So gear, pet and skin
     * purchases say plainly that the Wardrobe has the new item on the next open, rather than
     * pretending the panel is fully live.
     */
    private void finish(SupporterService service, UUID uuid, World world, UIContext ctx,
                        String note, String chipId, String newChipLabel, boolean bought) {
        world.execute(() -> {
            try {
                ctx.editById(WALLET_ID, LabelBuilder.class,
                        l -> l.withText(String.valueOf(service.tokenBalance(uuid))));
                if (bought && chipId != null) {
                    HyUIStyle ownedStyle = theme.chipLabel(FlatTheme.GREEN_TEXT);
                    ctx.editById(chipId, CustomButtonBuilder.class, b -> b
                            .withText(newChipLabel)
                            .withDefaultLabelStyle(ownedStyle)
                            .withHoveredLabelStyle(ownedStyle)
                            .withPressedLabelStyle(ownedStyle)
                            .withDefaultBackground(theme.fill(FlatTheme.OWNED_BG)));
                }
                ctx.editById(NOTICE_ID, LabelBuilder.class, l -> l.withText(note));
                ctx.updatePage(false);
            } catch (Throwable t) {
                plugin.log().warn("Purchase refresh failed: " + t);
            }
        });
    }

    // --- helpers -----------------------------------------------------------------------------

    private static final java.util.Map<String, String> CAPE_COLOURS =
            new java.util.LinkedHashMap<>();

    static {
        CAPE_COLOURS.put("amber", "#F0A93B");
        CAPE_COLOURS.put("frost", "#6CC6FF");
        CAPE_COLOURS.put("rose", "#FF7AD9");
        CAPE_COLOURS.put("jade", "#5FE0A0");
        CAPE_COLOURS.put("gold", "#FFD166");
        CAPE_COLOURS.put("ember", "#FF7A6B");
        CAPE_COLOURS.put("black", "#9AA0AD");
    }

    private int skinCost(String name) {
        return plugin.config().skinCost(name, SkinChanger.isCostume(name));
    }

    private static List<String> gearNames() {
        List<String> out = new ArrayList<>();
        out.addAll(SupporterCommand.CapeSub.DESIGNS.keySet());
        out.addAll(SupporterCommand.HatSub.HATS.keySet());
        out.addAll(SupporterCommand.ShoesSub.SHOES.keySet());
        return out;
    }

    /** A section title with a dim inline note and a rule beneath it. */
    private GroupBuilder sectionHead(String id, String title, String note, int y) {
        GroupBuilder head = (GroupBuilder) GroupBuilder.group()
                .withId(id)
                .withAnchor(new HyUIAnchor().setTop(y).setLeft(PAD)
                        .setWidth(BODY_W).setHeight(HEAD_H));
        head = (GroupBuilder) head.addChild(
                text(id + "_t", title, theme.eyebrow(FlatTheme.INK_LABEL), 0, 0, 300, 18));
        head = (GroupBuilder) head.addChild(text(id + "_n", note,
                right(theme.small(FlatTheme.INK_MUTED)), 0, BODY_W - 460, 460, 18));
        head = (GroupBuilder) head.addChild(
                box(id + "_r", FlatTheme.HAIRLINE, 0, 24, BODY_W, 1));
        return head;
    }

    private CustomButtonBuilder chip(String id, String label, int left, int top, int width,
                                     String textColour, String background,
                                     BiConsumer<Void, UIContext> onClick) {
        HyUIStyle style = theme.chipLabel(textColour);
        CustomButtonBuilder button = (CustomButtonBuilder) CustomButtonBuilder.customTextButton()
                .withId(id)
                .withAnchor(new HyUIAnchor().setTop(top).setLeft(left)
                        .setWidth(width).setHeight(CHIP_H));
        button = button.withText(label)
                .withDefaultLabelStyle(style)
                .withHoveredLabelStyle(style)
                .withPressedLabelStyle(style)
                .withDefaultBackground(theme.fill(background))
                .withHoveredBackground(theme.fill(theme.accentHover()))
                .withPressedBackground(theme.fill(theme.accentNav()));
        return button.addEventListener(CustomUIEventBindingType.Activating, onClick);
    }

    /** An invisible click target, for rows whose visuals are drawn underneath it. */
    private CustomButtonBuilder hitArea(String id, int left, int top, int width, int height,
                                        BiConsumer<Void, UIContext> onClick) {
        CustomButtonBuilder button = (CustomButtonBuilder) CustomButtonBuilder.customTextButton()
                .withId(id)
                .withAnchor(new HyUIAnchor().setTop(top).setLeft(left)
                        .setWidth(width).setHeight(height));
        button = button.withText("")
                .withDefaultBackground(theme.fill("#00000000"))
                .withHoveredBackground(theme.fill(FlatTheme.white(0.045)))
                .withPressedBackground(theme.fill(theme.accentNav()));
        return button.addEventListener(CustomUIEventBindingType.Activating, onClick);
    }

    private GroupBuilder box(String id, String colour, int left, int top, int width, int height) {
        return (GroupBuilder) GroupBuilder.group()
                .withId(id)
                .withAnchor(new HyUIAnchor().setTop(top).setLeft(left)
                        .setWidth(width).setHeight(height))
                .withBackground(theme.fill(colour));
    }

    /**
     * A 1px outline as four rectangles — there is no border property, and this is what the
     * design's hairline-everywhere style costs in element count.
     */
    private GroupBuilder hairlineBox(String id, int left, int top, int width, int height) {
        GroupBuilder frame = (GroupBuilder) GroupBuilder.group()
                .withId(id)
                .withAnchor(new HyUIAnchor().setTop(top).setLeft(left)
                        .setWidth(width).setHeight(height));
        frame = (GroupBuilder) frame.addChild(box(id + "_t", FlatTheme.HAIRLINE, 0, 0, width, 1));
        frame = (GroupBuilder) frame.addChild(
                box(id + "_b", FlatTheme.HAIRLINE, 0, height - 1, width, 1));
        frame = (GroupBuilder) frame.addChild(box(id + "_l", FlatTheme.HAIRLINE, 0, 0, 1, height));
        frame = (GroupBuilder) frame.addChild(
                box(id + "_r", FlatTheme.HAIRLINE, width - 1, 0, 1, height));
        return frame;
    }

    private static HyUIStyle right(HyUIStyle style) {
        return style.setAlignment(Alignment.End);
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
