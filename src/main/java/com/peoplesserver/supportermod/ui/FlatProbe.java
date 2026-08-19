package com.peoplesserver.supportermod.ui;

import au.ellie.hyui.builders.GroupBuilder;
import au.ellie.hyui.builders.HyUIAnchor;
import au.ellie.hyui.builders.HyUIPatchStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.peoplesserver.supportermod.SupporterPlugin;

/**
 * One-variable-at-a-time probe for the flat-fill primitive (0.23.1).
 *
 * <p><b>Why this exists.</b> 0.23.0 changed three things at once — a new texture path, area
 * slicing, and {@code setColor} — and the client responded by disconnecting: a property value
 * it cannot apply is fatal, not cosmetic. With a disconnect as the cost of each wrong guess,
 * guessing is exactly the wrong method. Each variant here changes ONE thing from a construct
 * already proven live, so whichever one drops the connection names the culprit precisely.
 *
 * <p>Run them in order and stop at the first that works — variant 1 is the full primitive the
 * redesign wants, and the rest exist only to bisect if it fails.
 */
public final class FlatProbe {

    /** What each variant tests, shown in chat so the answer is legible without the source. */
    public static final String[] DESCRIPTIONS = {
        "1  full primitive: CircularProgressBarMask + 8x8 area + border 0 + tint",
        "2  same, WITHOUT the tint (isolates setColor)",
        "3  same, WITHOUT the area slice (isolates setArea)",
        "4  proven Popup.png nine-patch + tint only (isolates setColor on a known-good patch)",
        "5  full primitive on the backup texture, WIPIcon.png",
    };

    private FlatProbe() {
    }

    /**
     * Opens a small panel whose only styled element is the variant under test.
     *
     * @param variant 1-5, as described in {@link #DESCRIPTIONS}
     */
    public static boolean open(SupporterPlugin plugin, PlayerRef playerRef,
                               Store<EntityStore> store, int variant) {
        try {
            FlatTheme theme = new FlatTheme(plugin.config().tagColorHex());
            String accent = theme.accent();
            HyUIPatchStyle style = switch (variant) {
                case 1 -> theme.slice(FlatTheme.FILL_TEXTURE,
                        FlatTheme.FILL_X, FlatTheme.FILL_Y, accent);
                case 2 -> theme.slice(FlatTheme.FILL_TEXTURE,
                        FlatTheme.FILL_X, FlatTheme.FILL_Y, null);
                case 3 -> new HyUIPatchStyle()
                        .setTexturePath(FlatTheme.FILL_TEXTURE)
                        .setBorder(0)
                        .setColor(accent);
                // Popup.png with a border is exactly what the live panel draws every day, so
                // this variant adds only the tint.
                case 4 -> new HyUIPatchStyle()
                        .setTexturePath("Common/Popup.png")
                        .setBorder(16)
                        .setColor(accent);
                case 5 -> theme.slice(FlatTheme.FILL_TEXTURE_ALT,
                        FlatTheme.FILL_ALT_X, FlatTheme.FILL_ALT_Y, accent);
                default -> null;
            };
            if (style == null) {
                return false;
            }

            // The root carries NO background: if the variant is bad, the failure belongs to
            // the swatch and nothing else, which keeps the result unambiguous.
            GroupBuilder root = (GroupBuilder) GroupBuilder.group()
                    .withId("FlatProbeRoot")
                    .withAnchor(new HyUIAnchor().setTop(220).setWidth(520).setHeight(220));

            LabelBuilder heading = (LabelBuilder) LabelBuilder.label()
                    .withId("FlatProbeHeading")
                    .withAnchor(new HyUIAnchor().setTop(0).setLeft(0)
                            .setWidth(520).setHeight(30))
                    .withStyle(theme.eyebrow(FlatTheme.INK_BODY));
            root = (GroupBuilder) root.addChild(heading.withText("FILL PROBE " + variant));

            LabelBuilder note = (LabelBuilder) LabelBuilder.label()
                    .withId("FlatProbeNote")
                    .withAnchor(new HyUIAnchor().setTop(34).setLeft(0)
                            .setWidth(520).setHeight(60))
                    .withStyle(theme.body(FlatTheme.INK_SECONDARY));
            root = (GroupBuilder) root.addChild(note.withText(DESCRIPTIONS[variant - 1]));

            // The element under test: a plain block. If it appears, the construct is sound.
            GroupBuilder swatch = (GroupBuilder) GroupBuilder.group()
                    .withId("FlatProbeSwatch")
                    .withAnchor(new HyUIAnchor().setTop(104).setLeft(0)
                            .setWidth(520).setHeight(90))
                    .withBackground(style);
            root = (GroupBuilder) root.addChild(swatch);

            PageBuilder page = (PageBuilder) PageBuilder.pageForPlayer(playerRef).addElement(root);
            page.open(store);
            return true;
        } catch (Throwable t) {
            plugin.log().warn("Fill probe " + variant + " failed to build: " + t);
            return false;
        }
    }
}
