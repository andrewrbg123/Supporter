package com.peoplesserver.supportermod.core;

/**
 * A player's cosmetic choices: chat title, chat colour, particle trail, and whether they want
 * other people's trails hidden.
 *
 * <p>Nullable fields mean "not set"; null and "cleared" are the same thing here, and collapsing
 * them saves every caller from distinguishing.
 *
 * <p>Deliberately separate from {@link SupporterRecord}. These are things the player configured
 * rather than part of their entitlement arithmetic, and they outlive a lapse: when a rank
 * expires the title and trail stop rendering but are not deleted, so renewing restores exactly
 * what they had. Never delete something a player paid for.
 *
 * <p>{@code hideTrails} is the odd one out — it is not a perk and not gated on entitlement, so
 * any player may set it. It lives here because it is a per-player cosmetic preference stored on
 * the same row, and a second table for one boolean would buy nothing.
 *
 * @param title      custom title shown before the username, or null
 * @param chatColor  hex colour for the supporter tag, e.g. {@code #55FFFF}, or null
 * @param trail      trail id from {@code SupporterConfig.trails()}, or null for none
 * @param hideTrails true if this player does not want to see anyone else's trail
 */
public record SupporterIdentity(String title, String chatColor, String trail, boolean hideTrails) {

    public static final SupporterIdentity NONE = new SupporterIdentity(null, null, null, false);

    public boolean hasTitle() {
        return title != null && !title.isBlank();
    }

    public boolean hasColor() {
        return chatColor != null && !chatColor.isBlank();
    }

    public boolean hasTrail() {
        return trail != null && !trail.isBlank();
    }

    public boolean isEmpty() {
        return !hasTitle() && !hasColor() && !hasTrail() && !hideTrails;
    }

    public SupporterIdentity withTitle(String newTitle) {
        return new SupporterIdentity(newTitle, chatColor, trail, hideTrails);
    }

    public SupporterIdentity withColor(String newColor) {
        return new SupporterIdentity(title, newColor, trail, hideTrails);
    }

    public SupporterIdentity withTrail(String newTrail) {
        return new SupporterIdentity(title, chatColor, newTrail, hideTrails);
    }

    public SupporterIdentity withHideTrails(boolean hide) {
        return new SupporterIdentity(title, chatColor, trail, hide);
    }
}
