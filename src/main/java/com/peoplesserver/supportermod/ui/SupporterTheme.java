package com.peoplesserver.supportermod.ui;

import au.ellie.hyui.builders.HyUIPatchStyle;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.Alignment;

/**
 * One place that decides what the supporter UI looks like.
 *
 * <p><b>No custom art.</b> Every texture here ships with the client — they are the same paths
 * HyUI's own {@code DefaultStyles} points at. Bundling art would mean shipping an asset pack in
 * {@code mods/}, and that directory is scanned at boot: a malformed pack logs on every start, and
 * a texture referenced by something else is easy to break. A tinted vanilla nine-patch gets a
 * themed panel for none of that risk. If a texture ever fails to resolve the panel simply renders
 * without a background rather than failing.
 *
 * <p>The accent colour is NOT hard-coded — it comes from {@code tagColorHex}, the same value the
 * chat tag uses. So the panel matches the tag by construction, and changing that one config key
 * re-themes both. That is the whole reason this class takes the colour as a parameter rather than
 * defining its own.
 */
public final class SupporterTheme {

    /** Vanilla nine-patch used for panels and popups. */
    private static final String TEX_PANEL = "Common/Popup.png";
    /** Vanilla nine-patch used for the title strip at the top of a popup. */
    private static final String TEX_HEADER = "Common/PopupTitle.png";

    /** Vanilla button nine-patches, used for the tab strip. */
    private static final String TEX_TAB = "Common/Buttons/Secondary.png";
    private static final String TEX_TAB_HOVERED = "Common/Buttons/Secondary_Hovered.png";
    private static final String TEX_TAB_PRESSED = "Common/Buttons/Secondary_Pressed.png";

    private static final int PANEL_BORDER = 16;
    private static final int BUTTON_BORDER = 12;
    private static final int HEADER_BORDER = 12;

    /** Body text: readable against the dark panel without competing with the accent. */
    public static final String INK_BODY = "#D8DCE4";
    /** Secondary text — captions, units, the quieter half of a line. */
    public static final String INK_DIM = "#98A0AE";
    /** Something the player has: owned, active, affordable. */
    public static final String INK_GOOD = "#7FD962";
    /** Something they do not: locked, expired, unaffordable. Never used for errors. */
    public static final String INK_LOCKED = "#C4795C";

    private final String accent;

    public SupporterTheme(String accentHex) {
        // Fall back rather than throw: a malformed colour must not stop a panel opening.
        this.accent = accentHex == null || accentHex.isBlank() ? "#FFAA00" : accentHex.trim();
    }

    public String accent() {
        return accent;
    }

    public HyUIPatchStyle panelBackground() {
        return new HyUIPatchStyle().setTexturePath(TEX_PANEL).setBorder(PANEL_BORDER);
    }

    /**
     * The header strip.
     *
     * <p>Deliberately NOT tinted with the accent. The first live build tinted it, and the vanilla
     * title texture has enough internal detail that an amber multiply turned it into a muddy
     * mottle. The accent belongs on the title text, where it reads cleanly against the dark
     * plate; the strip just needs to separate the header from the body.
     */
    public HyUIPatchStyle headerBackground() {
        return new HyUIPatchStyle().setTexturePath(TEX_HEADER).setBorder(HEADER_BORDER);
    }

    /** Tab buttons, so a tab is a target you can hit rather than a word you must land on. */
    public HyUIPatchStyle tabBackground() {
        return new HyUIPatchStyle().setTexturePath(TEX_TAB).setBorder(BUTTON_BORDER);
    }

    public HyUIPatchStyle tabHovered() {
        return new HyUIPatchStyle().setTexturePath(TEX_TAB_HOVERED).setBorder(BUTTON_BORDER);
    }

    public HyUIPatchStyle tabPressed() {
        return new HyUIPatchStyle().setTexturePath(TEX_TAB_PRESSED).setBorder(BUTTON_BORDER);
    }

    public HyUIStyle title() {
        return new HyUIStyle()
                .setFontSize(26f)
                .setRenderBold(true)
                .setRenderUppercase(true)
                .setLetterSpacing(2)
                .setTextColor(accent)
                .setAlignment(Alignment.Center);
    }

    public HyUIStyle heading() {
        return new HyUIStyle().setFontSize(17f).setRenderBold(true).setTextColor(accent);
    }

    public HyUIStyle body() {
        return new HyUIStyle().setFontSize(15f).setTextColor(INK_BODY).setWrap(true);
    }

    public HyUIStyle dim() {
        return new HyUIStyle().setFontSize(14f).setTextColor(INK_DIM).setWrap(true);
    }

    public HyUIStyle coloured(String hex, boolean bold) {
        return new HyUIStyle().setFontSize(15f).setRenderBold(bold).setTextColor(hex).setWrap(true);
    }

    public HyUIStyle selectedTab() {
        return new HyUIStyle().setFontSize(15f).setRenderBold(true).setTextColor(accent);
    }

    public HyUIStyle unselectedTab() {
        return new HyUIStyle().setFontSize(15f).setTextColor(INK_DIM);
    }
}
