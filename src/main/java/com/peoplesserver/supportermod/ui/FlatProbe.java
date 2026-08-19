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
 * <p><b>Why this exists.</b> Two builds were spent disconnecting the client with texture
 * slicing before reading how HyUI's own {@code DefaultStyles} draws a flat surface: a patch
 * style with a colour and no texture at all. Variant 1 is that primitive, and it is what the
 * panel now uses; the other two exist so that if it somehow fails, the answer arrives in one
 * more attempt rather than another round of guessing.
 *
 * <p>Each variant's swatch is the ONLY styled element in its panel, so a failure can belong to
 * nothing else. A value the client cannot apply drops the player from the server, which is why
 * these run one at a time and say so up front.
 */
public final class FlatProbe {

    /** What each variant tests, shown in chat so the answer is legible without the source. */
    public static final String[] DESCRIPTIONS = {
        "1  colour only, no texture - the primitive HyUI's own DefaultStyles uses",
        "2  colour only with 8-digit alpha (#RRGGBBAA) - tests real transparency",
        "3  proven Popup.png nine-patch, tinted - the fallback if colour-only fails",
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
                case 1 -> new HyUIPatchStyle().setColor(accent);
                case 2 -> new HyUIPatchStyle().setColor(FlatTheme.alpha(accent, 0.35));
                // Popup.png with a border is exactly what the live panel draws every day, so
                // this variant adds only the tint.
                case 3 -> new HyUIPatchStyle()
                        .setTexturePath(FlatTheme.PROBE_TEXTURE)
                        .setBorder(16)
                        .setColor(accent);
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
