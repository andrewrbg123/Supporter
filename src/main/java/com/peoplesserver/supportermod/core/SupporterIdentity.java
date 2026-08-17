package com.peoplesserver.supportermod.core;

/**
 * A supporter's chosen chat identity: a custom title and a chat colour.
 *
 * <p>Both fields are nullable and null means "not set" rather than "cleared" — the two are the
 * same thing here, and collapsing them keeps every caller from having to distinguish.
 *
 * <p>Identity is deliberately separate from {@link SupporterRecord}. It is something the player
 * configured rather than part of their entitlement arithmetic, and it outlives a lapse: when a
 * rank expires the title stops rendering but is not deleted, so renewing restores exactly what
 * they had. Never delete something a player paid for.
 *
 * @param title      custom title shown before the username, or null
 * @param chatColor  hex colour for the username, e.g. {@code #55FFFF}, or null for the default
 */
public record SupporterIdentity(String title, String chatColor) {

    public static final SupporterIdentity NONE = new SupporterIdentity(null, null);

    public boolean hasTitle() {
        return title != null && !title.isBlank();
    }

    public boolean hasColor() {
        return chatColor != null && !chatColor.isBlank();
    }

    public boolean isEmpty() {
        return !hasTitle() && !hasColor();
    }

    public SupporterIdentity withTitle(String newTitle) {
        return new SupporterIdentity(newTitle, chatColor);
    }

    public SupporterIdentity withColor(String newColor) {
        return new SupporterIdentity(title, newColor);
    }
}
