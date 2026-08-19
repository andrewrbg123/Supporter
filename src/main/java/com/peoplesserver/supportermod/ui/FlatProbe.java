package com.peoplesserver.supportermod.ui;

import au.ellie.hyui.builders.GroupBuilder;
import au.ellie.hyui.builders.HyUIAnchor;
import au.ellie.hyui.builders.HyUIPatchStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.types.ScrollbarStyle;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.peoplesserver.supportermod.SupporterPlugin;

/**
 * One-variable-at-a-time probes for UI constructs this plugin has not proven yet.
 *
 * <p><b>Why this exists.</b> A value the client cannot apply does not degrade — it drops the
 * player from the server. So anything unproven is tested here first, alone, in a panel where
 * the construct under test is the only thing that could fail.
 *
 * <p>Variants 1-3 settled the flat-fill primitive (a colour-only patch style, which is what the
 * panel now uses). Variants 4-5 ask the open question about scrolling: HyUI exposes
 * {@code TopScrolling} as a layout mode and a scrollbar style, but <b>nothing inside HyUI ever
 * uses either</b> — the only reference to "Scrolling" in the whole library is the enum that
 * names it — so the semantics are the client's and unknown. Two things need answering, and the
 * second matters more than the first: does it scroll, and <b>does a layout mode rearrange
 * children</b>? Every screen in this panel is positioned absolutely, so a mode that auto-stacks
 * its children would rewrite the whole layout rather than merely adding a scrollbar.
 */
public final class FlatProbe {

    /** What each variant tests, shown in chat so the answer is legible without the source. */
    public static final String[] DESCRIPTIONS = {
        "1  colour only, no texture - the primitive HyUI's own DefaultStyles uses (PROVEN)",
        "2  colour only with 8-digit alpha (#RRGGBBAA) - real transparency (PROVEN)",
        "3  proven Popup.png nine-patch, tinted - the fallback if colour-only fails",
        "4  layoutMode TopScrolling, children overflowing the group - does it scroll, and do "
                + "absolute positions survive?",
        "5  as 4, plus HyUI's own default scrollbar style",
    };

    private FlatProbe() {
    }

    public static boolean open(SupporterPlugin plugin, PlayerRef playerRef,
                               Store<EntityStore> store, int variant) {
        try {
            FlatTheme theme = new FlatTheme(plugin.config().tagColorHex());
            GroupBuilder root = variant >= 4
                    ? scrollProbe(theme, variant)
                    : fillProbe(theme, variant);
            if (root == null) {
                return false;
            }
            PageBuilder page = (PageBuilder) PageBuilder.pageForPlayer(playerRef).addElement(root);
            page.open(store);
            return true;
        } catch (Throwable t) {
            plugin.log().warn("Probe " + variant + " failed to build: " + t);
            return false;
        }
    }

    private static GroupBuilder fillProbe(FlatTheme theme, int variant) {
        String accent = theme.accent();
        HyUIPatchStyle style = switch (variant) {
            case 1 -> new HyUIPatchStyle().setColor(accent);
            case 2 -> new HyUIPatchStyle().setColor(FlatTheme.alpha(accent, 0.35));
            case 3 -> new HyUIPatchStyle()
                    .setTexturePath(FlatTheme.PROBE_TEXTURE)
                    .setBorder(16)
                    .setColor(accent);
            default -> null;
        };
        if (style == null) {
            return null;
        }
        GroupBuilder root = (GroupBuilder) GroupBuilder.group()
                .withId("FlatProbeRoot")
                .withAnchor(new HyUIAnchor().setTop(220).setWidth(520).setHeight(220));
        root = (GroupBuilder) root.addChild(label(theme, "FlatProbeHeading",
                "FILL PROBE " + variant, 0, 520, 30, theme.eyebrow(FlatTheme.INK_BODY)));
        root = (GroupBuilder) root.addChild(label(theme, "FlatProbeNote",
                DESCRIPTIONS[variant - 1], 34, 520, 60,
                theme.body(FlatTheme.INK_SECONDARY)));
        GroupBuilder swatch = (GroupBuilder) GroupBuilder.group()
                .withId("FlatProbeSwatch")
                .withAnchor(new HyUIAnchor().setTop(104).setLeft(0)
                        .setWidth(520).setHeight(90))
                .withBackground(style);
        return (GroupBuilder) root.addChild(swatch);
    }

    /**
     * A 260px-tall viewport holding 12 rows of absolutely-positioned content that runs to
     * 480px. Every row is numbered and evenly spaced, so the render answers both questions at
     * once: if the rows are where they were put, absolute anchors survive the layout mode, and
     * if the wheel moves them, it scrolls.
     */
    private static GroupBuilder scrollProbe(FlatTheme theme, int variant) {
        GroupBuilder root = (GroupBuilder) GroupBuilder.group()
                .withId("FlatScrollRoot")
                .withAnchor(new HyUIAnchor().setTop(150).setWidth(560).setHeight(420))
                .withBackground(new HyUIPatchStyle().setColor(FlatTheme.PANEL_BG));

        root = (GroupBuilder) root.addChild(label(theme, "FlatScrollHead",
                "SCROLL PROBE " + variant, 14, 560, 26, theme.eyebrow(FlatTheme.INK_BODY)));
        root = (GroupBuilder) root.addChild(label(theme, "FlatScrollNote",
                "Rows 1-12 sit 40px apart in a 260px viewport. Scroll the wheel over them.",
                42, 560, 40, theme.body(FlatTheme.INK_SECONDARY)));

        GroupBuilder viewport = (GroupBuilder) GroupBuilder.group()
                .withId("FlatScrollView")
                .withAnchor(new HyUIAnchor().setTop(92).setLeft(20)
                        .setWidth(520).setHeight(260))
                .withBackground(new HyUIPatchStyle().setColor(FlatTheme.white(0.03)))
                .withLayoutMode("TopScrolling");
        if (variant == 5) {
            viewport = viewport.withScrollbarStyle(ScrollbarStyle.defaultStyle());
        }
        for (int i = 0; i < 12; i++) {
            int y = i * 40;
            GroupBuilder row = (GroupBuilder) GroupBuilder.group()
                    .withId("FlatScrollRow" + i)
                    .withAnchor(new HyUIAnchor().setTop(y).setLeft(10)
                            .setWidth(480).setHeight(32))
                    .withBackground(new HyUIPatchStyle().setColor(
                            i % 2 == 0 ? FlatTheme.white(0.05) : FlatTheme.white(0.02)));
            viewport = (GroupBuilder) viewport.addChild(row);
            viewport = (GroupBuilder) viewport.addChild(label(theme, "FlatScrollT" + i,
                    "row " + (i + 1) + " - top " + y, y + 7, 480, 20,
                    theme.small(FlatTheme.INK_BODY)).withAnchor(
                            new HyUIAnchor().setTop(y + 7).setLeft(22)
                                    .setWidth(480).setHeight(20)));
        }
        root = (GroupBuilder) root.addChild(viewport);
        root = (GroupBuilder) root.addChild(label(theme, "FlatScrollFoot",
                "Rows evenly spaced = absolute anchors survive. Bunched or stacked = the "
                        + "layout mode rearranges children, and the panel cannot use it.",
                364, 560, 44, theme.small(FlatTheme.INK_MUTED)));
        return root;
    }

    private static LabelBuilder label(FlatTheme theme, String id, String text, int top,
                                      int width, int height,
                                      au.ellie.hyui.builders.HyUIStyle style) {
        LabelBuilder l = (LabelBuilder) LabelBuilder.label()
                .withId(id)
                .withAnchor(new HyUIAnchor().setTop(top).setLeft(20)
                        .setWidth(width - 40).setHeight(height))
                .withStyle(style);
        return l.withText(text);
    }
}
