package com.peoplesserver.supportermod.ui;

import au.ellie.hyui.builders.Alignment;
import au.ellie.hyui.builders.HyUIPatchStyle;
import au.ellie.hyui.builders.HyUIStyle;

/**
 * Flat-surface theme for the redesigned panel (0.23.0 spike).
 *
 * <p><b>The primitive that makes this design possible is a solid colour rectangle</b>, which
 * HyUI does not offer directly. It is assembled instead: the client ships
 * {@code ColorPickerFill@2x.png}, whose centre holds a 16x16 block of PURE OPAQUE WHITE
 * (verified pixel by pixel against the asset extract), and {@link HyUIPatchStyle} can both
 * slice a sub-region of a texture ({@code setArea*}) and tint it ({@code setColor}). Slice the
 * white block, tint it, stretch it: an arbitrary flat rectangle in any hex. Every surface,
 * hairline, card and meter in the new panel is one of these.
 *
 * <p><b>No alpha.</b> {@code setColor} takes a hex string and the design is written in
 * {@code rgba(255,255,255,.018)}-style overlays, so every translucent surface is PRE-BLENDED
 * here against the known background with {@link #over}. That is exact rather than approximate,
 * because the backgrounds are known constants — but it does mean a surface cannot sit over an
 * unpredictable backdrop and stay correct.
 *
 * <p>What the design asks for and this cannot do, recorded so nobody re-litigates it: rounded
 * corners (rectangles are rectangles), gradients (only stacked bands), glows and shadows,
 * eased transitions, and the two Google fonts — fonts are client-side, and a server asset pack
 * cannot add one. Type hierarchy survives through size, weight, case and letter spacing.
 */
public final class FlatTheme {

    /**
     * The fill texture. ColorPickerFill ships ONLY as {@code @2x} — every other texture this
     * plugin references has a base-name twin — so whether the client resolves the base name is
     * genuinely unknown, and the spike panel carries a labelled swatch strip to answer it. A
     * texture that fails to resolve renders nothing rather than erroring, so the failure mode
     * is a panel with no surfaces, not a crash.
     */
    public static final String FILL_TEXTURE = "Common/ColorPickerFill.png";

    /** Candidates the spike's diagnostic strip renders, in order. */
    public static final String[] FILL_CANDIDATES = {
        "Common/ColorPickerFill.png",
        "Common/ColorPickerFill@2x.png",
        "UI/Custom/Common/ColorPickerFill@2x.png",
        "Common/CircularProgressBarMask.png",
    };

    /** The solid-white block inside the fill texture: x16..31, y16..31. */
    private static final int FILL_X = 16;
    private static final int FILL_Y = 16;
    private static final int FILL_SIZE = 16;

    // --- palette (design tokens, pre-blended where the design used alpha) -----------------

    public static final String PANEL_BG = "#0C0E14";
    public static final String RAIL_BG = "#13151B";
    public static final String HAIRLINE = "#222429";
    public static final String CARD_BG = "#101218";
    public static final String CARD_BG_SOFT = "#0F1117";
    public static final String CHIP_BG = "#111319";
    public static final String CHIP_BORDER = "#1E2027";

    public static final String INK_PRIMARY = "#F7F8FC";
    public static final String INK_BODY = "#E8EAF0";
    public static final String INK_CHIP = "#AAACB2";
    public static final String INK_LABEL = "#7A7C82";
    public static final String INK_SECONDARY = "#6F7177";
    public static final String INK_MUTED = "#57595F";

    public static final String GREEN = "#4ADE80";
    public static final String GREEN_TEXT = "#7EE7A3";

    private final String accent;
    private final String accentBright;
    private final String accentWash;
    private final String accentNav;

    public FlatTheme(String accentHex) {
        this.accent = accentHex == null || accentHex.isBlank() ? "#F0A93B" : accentHex.trim();
        // The design's --acc-bright: the accent mixed 42% toward white.
        this.accentBright = mix(accent, "#FFFFFF", 0.42);
        // Card wash and active-nav background: the accent at .10 / .12 over the panel.
        this.accentWash = over(PANEL_BG, accent, 0.10);
        this.accentNav = over(PANEL_BG, accent, 0.12);
    }

    public String accent() {
        return accent;
    }

    public String accentBright() {
        return accentBright;
    }

    public String accentWash() {
        return accentWash;
    }

    public String accentNav() {
        return accentNav;
    }

    // --- the primitive ---------------------------------------------------------------------

    /** A solid rectangle of {@code hex}. The whole design is built out of these. */
    public HyUIPatchStyle fill(String hex) {
        return fill(hex, FILL_TEXTURE);
    }

    /** As {@link #fill(String)}, with an explicit texture — used by the diagnostic strip. */
    public HyUIPatchStyle fill(String hex, String texturePath) {
        return new HyUIPatchStyle()
                .setTexturePath(texturePath)
                .setAreaX(FILL_X).setAreaY(FILL_Y)
                .setAreaWidth(FILL_SIZE).setAreaHeight(FILL_SIZE)
                // Border 0: no nine-slice, just stretch the solid block over the whole element.
                .setBorder(0)
                .setColor(hex);
    }

    // --- colour maths ----------------------------------------------------------------------

    /** {@code over(background, overlay, alpha)} — the design's rgba() overlays, pre-blended. */
    public static String over(String background, String overlay, double alpha) {
        return mix(background, overlay, alpha);
    }

    /** Blends {@code b} into {@code a} by {@code t} (0..1) and returns a hex string. */
    public static String mix(String a, String b, double t) {
        int[] ca = rgb(a);
        int[] cb = rgb(b);
        int r = (int) Math.round(ca[0] + ((cb[0] - ca[0]) * t));
        int g = (int) Math.round(ca[1] + ((cb[1] - ca[1]) * t));
        int bl = (int) Math.round(ca[2] + ((cb[2] - ca[2]) * t));
        return String.format("#%02X%02X%02X", clamp(r), clamp(g), clamp(bl));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int[] rgb(String hex) {
        String h = hex == null ? "" : hex.trim();
        if (h.startsWith("#")) {
            h = h.substring(1);
        }
        if (h.length() != 6) {
            return new int[] {255, 255, 255}; // unparseable colours render white, never throw
        }
        try {
            return new int[] {
                Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16),
            };
        } catch (NumberFormatException e) {
            return new int[] {255, 255, 255};
        }
    }

    // --- type --------------------------------------------------------------------------------
    //
    // The design's two Google fonts cannot ship, so the hierarchy is carried entirely by size,
    // weight, case and letter spacing — which is most of what its typography was doing anyway:
    // big tight numerals, and small wide-tracked uppercase labels.

    /** Wide-tracked uppercase micro-label — the design's IBM Plex Mono eyebrow. */
    public HyUIStyle eyebrow(String colour) {
        return new HyUIStyle()
                .setFontSize(12f)
                .setRenderBold(true)
                .setRenderUppercase(true)
                .setLetterSpacing(3)
                .setWrap(false)
                .setTextColor(colour);
    }

    /** The 44px stat numeral. */
    public HyUIStyle numeral(String colour) {
        return new HyUIStyle()
                .setFontSize(40f)
                .setRenderBold(true)
                .setWrap(false)
                .setTextColor(colour);
    }

    /** The unit beside a numeral, and other quiet mono-ish notes. */
    public HyUIStyle unit() {
        return new HyUIStyle()
                .setFontSize(14f)
                .setWrap(false)
                .setTextColor(INK_SECONDARY)
                .setAlignment(Alignment.End);
    }

    /** Page title in the content header. */
    public HyUIStyle pageTitle() {
        return new HyUIStyle()
                .setFontSize(28f)
                .setRenderBold(true)
                .setWrap(false)
                .setTextColor(INK_PRIMARY);
    }

    /** The wordmark in the rail. */
    public HyUIStyle wordmark() {
        return new HyUIStyle()
                .setFontSize(15f)
                .setRenderBold(true)
                .setRenderUppercase(true)
                .setLetterSpacing(4)
                .setWrap(false)
                .setTextColor("#F4F6FB");
    }

    public HyUIStyle body(String colour) {
        return new HyUIStyle().setFontSize(15f).setWrap(true).setTextColor(colour);
    }

    public HyUIStyle small(String colour) {
        return new HyUIStyle().setFontSize(13f).setWrap(false).setTextColor(colour);
    }

    /** Collection chips: small, quiet, never wrapping out of their box. */
    public HyUIStyle chip() {
        return new HyUIStyle()
                .setFontSize(13f)
                .setShrinkTextToFit(true)
                .setMinShrinkTextToFitFontSize(9f)
                .setWrap(false)
                .setTextColor(INK_CHIP)
                .setAlignment(Alignment.Center);
    }

    /** Nav item labels. */
    public HyUIStyle navLabel(boolean active) {
        return new HyUIStyle()
                .setFontSize(15f)
                .setRenderBold(active)
                .setWrap(false)
                .setTextColor(active ? accentBright : INK_SECONDARY);
    }

    /** The token value in the rail's wallet block. */
    public HyUIStyle wallet() {
        return new HyUIStyle()
                .setFontSize(26f)
                .setRenderBold(true)
                .setWrap(false)
                .setTextColor(accent);
    }
}
