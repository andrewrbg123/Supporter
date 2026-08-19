package com.peoplesserver.supportermod.ui;

import au.ellie.hyui.builders.Alignment;
import au.ellie.hyui.builders.GroupBuilder;
import au.ellie.hyui.builders.HyUIAnchor;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.SceneBlurBuilder;
import au.ellie.hyui.builders.UIElementBuilder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.peoplesserver.supportermod.SupporterPlugin;
import com.peoplesserver.supportermod.command.SupporterCommand;
import com.peoplesserver.supportermod.core.SupporterService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SPIKE (0.23.0): the redesigned panel's shell and Status screen, in flat colour.
 *
 * <p>Answers one question a workstation cannot: <b>does a flat, near-black, hairline-bordered
 * layout actually read well in game</b>, drawn with tinted slices of a solid-white texture
 * instead of the vanilla nine-patches the live panel uses? Everything here is disposable. The
 * live panel is untouched and keeps serving {@code /supporter}; this opens from an admin
 * command only.
 *
 * <p>It also carries a <b>fill-texture probe</b> at the bottom of the body: four labelled
 * swatches, each drawn with a different candidate path to the fill texture. ColorPickerFill
 * ships only as {@code @2x} and whether the client resolves the base name is unknown, so
 * rather than guessing, one live look names the winner. If the panel renders with no surfaces
 * at all, the first candidate is wrong and the strip says which to switch to; a texture that
 * fails to resolve draws nothing rather than erroring.
 *
 * <p>Layout is absolute, in the design's own pixel values: 1180x788, a 236px rail, 40px body
 * padding. Nav items are rendered but inert — this spike is about how the surfaces look, and
 * wiring eight tabs before knowing that would be wasted work.
 */
public final class FlatPanel {

    private static final int WIDTH = 1180;
    private static final int HEIGHT = 788;
    private static final int TOP = 46;

    private static final int RAIL_W = 236;
    private static final int PAD = 40;
    private static final int CONTENT_W = WIDTH - RAIL_W;
    /** Usable width inside the content column's padding. */
    private static final int BODY_W = CONTENT_W - (PAD * 2);

    private static final String ROOT_ID = "FlatRoot";

    /** The eight screens, in the design's order. Only Status is built by the spike. */
    private static final String[] NAV = {
        "Status", "Perks", "Shop", "Quests", "Trails", "Chat", "Wardrobe", "About",
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

            // The frosted backdrop is the one effect the design asks for that HyUI does give
            // us — it stands in for the page-level glows, which a panel floating over the
            // world could never have had anyway.
            root = (GroupBuilder) root.addChild(SceneBlurBuilder.sceneBlur().withId("FlatBlur"));
            root = (GroupBuilder) root.addChild(rail(service, uuid));
            root = (GroupBuilder) root.addChild(content(service, uuid));

            PageBuilder page = (PageBuilder) PageBuilder.pageForPlayer(playerRef).addElement(root);
            page.open(store);
            return true;
        } catch (Throwable t) {
            plugin.log().warn("Flat panel failed to open: " + t);
            return false;
        }
    }

    // --- left rail ---------------------------------------------------------------------------

    private UIElementBuilder<?> rail(SupporterService service, UUID uuid) {
        GroupBuilder rail = (GroupBuilder) GroupBuilder.group()
                .withId("FlatRail")
                .withAnchor(new HyUIAnchor().setTop(0).setLeft(0)
                        .setWidth(RAIL_W).setHeight(HEIGHT))
                .withBackground(theme.fill(FlatTheme.RAIL_BG));

        // Brand block. The design's rotated diamond is a square here — there is no rotation.
        rail = (GroupBuilder) rail.addChild(box("FlatMark", theme.accent(), 22, 30, 8, 8));
        rail = (GroupBuilder) rail.addChild(
                text("FlatWordmark", "SUPPORTER", theme.wordmark(), 26, 40, 180, 22));

        boolean active = service.isSupporter(uuid);
        String stateColour = active ? FlatTheme.GREEN : FlatTheme.INK_MUTED;
        rail = (GroupBuilder) rail.addChild(box("FlatDot", stateColour, 23, 67, 6, 6));
        rail = (GroupBuilder) rail.addChild(text("FlatState",
                active ? "ACTIVE" : "INACTIVE", theme.eyebrow(stateColour), 60, 37, 70, 16));
        rail = (GroupBuilder) rail.addChild(text("FlatDays",
                active ? service.daysRemaining(uuid) + " DAYS LEFT" : "NOT A SUPPORTER",
                theme.small(FlatTheme.INK_SECONDARY), 60, 112, 120, 16));

        // Tenure meter: remaining against the longest tier we sell, clamped — a bar that can
        // overflow its track is worse than no bar.
        int remaining = (int) Math.max(0L, service.daysRemaining(uuid));
        int span = Math.max(90, remaining);
        int fillW = Math.max(2, Math.round(192f * remaining / span));
        rail = (GroupBuilder) rail.addChild(
                box("FlatMeterTrack", FlatTheme.METER_TRACK, 22, 94, 192, 4));
        if (remaining > 0) {
            rail = (GroupBuilder) rail.addChild(
                    box("FlatMeterFill", theme.accent(), 22, 94, fillW, 4));
        }
        rail = (GroupBuilder) rail.addChild(
                box("FlatBrandRule", FlatTheme.HAIRLINE, 0, 118, RAIL_W, 1));

        // Nav. Inert in the spike: the surfaces are what is being judged.
        for (int i = 0; i < NAV.length; i++) {
            int y = 134 + (i * 36);
            boolean selected = i == 0;
            if (selected) {
                rail = (GroupBuilder) rail.addChild(
                        box("FlatNavBg" + i, theme.accentNav(), 12, y, 212, 32));
            }
            rail = (GroupBuilder) rail.addChild(box("FlatNavMark" + i,
                    selected ? theme.accent() : FlatTheme.white(0.16), 22, y + 13, 5, 5));
            rail = (GroupBuilder) rail.addChild(text("FlatNav" + i, NAV[i],
                    theme.navLabel(selected), y + 7, 38, 170, 20));
        }

        // Wallet.
        rail = (GroupBuilder) rail.addChild(
                box("FlatWalletRule", FlatTheme.HAIRLINE, 0, 650, RAIL_W, 1));
        rail = (GroupBuilder) rail.addChild(text("FlatWalletLabel", "TOKENS",
                theme.eyebrow(FlatTheme.INK_MUTED), 672, 22, 120, 16));
        rail = (GroupBuilder) rail.addChild(text("FlatWalletValue",
                String.valueOf(service.tokenBalance(uuid)), theme.wallet(), 692, 22, 150, 34));
        rail = (GroupBuilder) rail.addChild(text("FlatWalletNote", "to spend",
                theme.small(FlatTheme.INK_SECONDARY), 730, 22, 150, 18));

        rail = (GroupBuilder) rail.addChild(
                box("FlatRailEdge", FlatTheme.HAIRLINE, RAIL_W - 1, 0, 1, HEIGHT));
        return rail;
    }

    // --- content column ----------------------------------------------------------------------

    private UIElementBuilder<?> content(SupporterService service, UUID uuid) {
        GroupBuilder col = (GroupBuilder) GroupBuilder.group()
                .withId("FlatContent")
                .withAnchor(new HyUIAnchor().setTop(0).setLeft(RAIL_W)
                        .setWidth(CONTENT_W).setHeight(HEIGHT));

        col = (GroupBuilder) col.addChild(text("FlatEyebrow", "/SUPPORTER",
                theme.eyebrow(FlatTheme.INK_MUTED), 30, PAD, 240, 16));
        col = (GroupBuilder) col.addChild(
                text("FlatTitle", "Status", theme.pageTitle(), 50, PAD, 420, 40));
        col = (GroupBuilder) col.addChild(text("FlatSubtitle",
                "Your rank at a glance - what you have, what you have earned, "
                        + "and everything you own.",
                right(theme.body(FlatTheme.INK_SECONDARY)), 52, CONTENT_W - PAD - 400, 400, 48));
        col = (GroupBuilder) col.addChild(
                box("FlatHeadRule", FlatTheme.HAIRLINE, 0, 110, CONTENT_W, 1));

        col = statusBody(col, service, uuid);
        return col;
    }

    /** The three stat cards and the collection grid. */
    private GroupBuilder statusBody(GroupBuilder col, SupporterService service, UUID uuid) {
        int cardW = (BODY_W - 32) / 3;
        String[] labels = {"REMAINING", "ALL TIME", "BALANCE"};
        String[] values = {
            String.valueOf(Math.max(0, service.daysRemaining(uuid))),
            String.valueOf(service.get(uuid).map(r -> r.totalDays()).orElse(0)),
            String.valueOf(service.tokenBalance(uuid)),
        };
        String[] units = {"days", "days total", "tokens"};

        for (int i = 0; i < 3; i++) {
            int x = PAD + (i * (cardW + 16));
            // The first card carries the accent wash, the others a plain surface — the design's
            // way of making "how long you have left" the headline without a second type size.
            col = (GroupBuilder) col.addChild(box("FlatStat" + i,
                    i == 0 ? theme.accentWash() : FlatTheme.CARD_BG, x, 144, cardW, 118));
            col = (GroupBuilder) col.addChild(hairlineBox("FlatStatEdge" + i, x, 144, cardW, 118));
            col = (GroupBuilder) col.addChild(text("FlatStatLabel" + i, labels[i],
                    theme.eyebrow(FlatTheme.INK_LABEL), 164, x + 20, cardW - 40, 16));
            col = (GroupBuilder) col.addChild(text("FlatStatValue" + i, values[i],
                    theme.numeral(i == 2 ? theme.accent() : FlatTheme.INK_PRIMARY),
                    196, x + 20, cardW - 40, 48));
            col = (GroupBuilder) col.addChild(text("FlatStatUnit" + i, units[i],
                    theme.unit(), 216, x + 20, cardW - 40, 20));
        }

        col = (GroupBuilder) col.addChild(text("FlatCollHead", "COLLECTION",
                theme.eyebrow(FlatTheme.INK_MUTED), 296, PAD, 240, 16));

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
        // Free items are owned by everyone and belong in a collection just as much as bought
        // ones — a Collection that only counted purchases would tell a new supporter they own
        // nothing while they wear a free cape.
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
            col = (GroupBuilder) col.addChild(
                    box("FlatColl" + i, FlatTheme.CARD_BG_SOFT, x, 330, collW, 132));
            col = (GroupBuilder) col.addChild(
                    hairlineBox("FlatCollEdge" + i, x, 330, collW, 132));
            col = (GroupBuilder) col.addChild(text("FlatCollLabel" + i, collLabels[i],
                    theme.eyebrow(FlatTheme.INK_LABEL), 348, x + 16, collW - 70, 16));
            col = (GroupBuilder) col.addChild(text("FlatCollCount" + i,
                    String.valueOf(items.size()),
                    right(theme.small(FlatTheme.INK_PRIMARY)), 346, x + collW - 60, 44, 18));

            // Four chips at most: the card is a summary, not a manifest.
            int chipW = (collW - 38) / 2;
            for (int j = 0; j < Math.min(4, items.size()); j++) {
                int cx = x + 16 + ((j % 2) * (chipW + 6));
                int cy = 376 + ((j / 2) * 28);
                col = (GroupBuilder) col.addChild(
                        box("FlatChip" + i + "_" + j, FlatTheme.CHIP_BG, cx, cy, chipW, 24));
                col = (GroupBuilder) col.addChild(
                        text("FlatChipT" + i + "_" + j, items.get(j), theme.chip(),
                                cy + 4, cx + 4, chipW - 8, 18));
            }
            if (items.size() > 4) {
                col = (GroupBuilder) col.addChild(text("FlatChipMore" + i,
                        "+" + (items.size() - 4) + " more",
                        theme.small(FlatTheme.INK_MUTED), 434, x + 16, collW - 32, 18));
            }
        }
        return col;
    }

    // --- helpers -----------------------------------------------------------------------------

    /** A solid rectangle — the whole design is built from these. */
    private GroupBuilder box(String id, String colour, int left, int top, int width, int height) {
        return (GroupBuilder) GroupBuilder.group()
                .withId(id)
                .withAnchor(new HyUIAnchor().setTop(top).setLeft(left)
                        .setWidth(width).setHeight(height))
                .withBackground(theme.fill(colour));
    }

    /**
     * A 1px outline, drawn as four rectangles.
     *
     * <p>There is no border property, and this is the honest cost of that: every card outline
     * is four more elements. Cheap individually, but it is why the design's hairline-everywhere
     * style is the expensive part of this port rather than the colours.
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
