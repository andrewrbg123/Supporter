package com.peoplesserver.supportermod.platform.hytale;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.peoplesserver.supportermod.core.SupporterIdentity;
import com.peoplesserver.supportermod.core.SupporterService;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Prepends the supporter tag to the chat line.
 *
 * <p>Two rules govern this class, and both came from things that went wrong.
 *
 * <p><b>1. It never cancels the event and never sends a message itself.</b> The obvious
 * implementation — cancel, decorate, re-broadcast — is a confidentiality bug here. FactionMod
 * cancels {@code PlayerChatEvent} when a player has faction chat toggled on and re-broadcasts
 * to their faction only. A plugin that also cancelled and broadcast server-wide would leak
 * private faction messages to everybody. Setting a formatter leaves delivery with the server,
 * so if FactionMod has cancelled, ours is never invoked.
 *
 * <p><b>2. It WRAPS the existing formatter rather than replacing it.</b> The first version
 * replaced it, and on the live server nothing appeared: {@code lucko:mini-chat-formatter} is
 * also installed, also sets a formatter, and ran after us. Its configured format is
 *
 * <pre>{@code <meta:jobtitle> <prefix><username><suffix>: <message>}</pre>
 *
 * <p>which is where the {@code [Street Dealer]} rank in chat comes from, resolved through
 * LuckPerms. Winning that fight by registering later would have meant discarding the rank,
 * prefix and suffix — reimplementing another plugin's job in order to add six characters.
 *
 * <p>So we register at {@link com.hypixel.hytale.event.EventPriority#LAST}, take whatever
 * formatter is in place by then, and delegate to it. The rendered line becomes
 * {@code [Supporter] <title> } followed by the chat plugin's own output, whatever that is
 * configured to be. If mini-chat-formatter is removed later, the delegate is simply the
 * server's {@code DEFAULT_FORMATTER} and this still works.
 *
 * <p>One consequence worth knowing: mini-chat-formatter's own LAST handler checks whether the
 * formatter is still its own, and logs a single warning if not. It does not re-apply, so our
 * wrapper survives; the warning is cosmetic and appears at most once per boot.
 *
 * <p>The player's chosen colour is applied to the tag rather than to their username, because
 * the username now belongs to the delegate. That is a better fit anyway: it shows even for a
 * supporter who has not set a title.
 */
public final class SupporterChatTag {

    private final SupporterService service;
    private final Color defaultTagColor;

    public SupporterChatTag(SupporterService service, Color defaultTagColor) {
        this.service = service;
        this.defaultTagColor = defaultTagColor;
    }

    /**
     * Registered with
     * {@code getEventRegistry().registerGlobal(EventPriority.LAST, PlayerChatEvent.class, ...)}.
     */
    public void onPlayerChat(PlayerChatEvent event) {
        try {
            if (event.isCancelled()) {
                return; // somebody else owns this message — most likely faction chat
            }
            PlayerRef sender = event.getSender();
            if (sender == null) {
                return;
            }
            UUID uuid = sender.getUuid();

            // O(1) map lookup, no database. This runs for every message on the server.
            if (uuid == null || !service.isSupporter(uuid)) {
                return;
            }

            final PlayerChatEvent.Formatter delegate = resolveDelegate(event.getFormatter());
            final SupporterIdentity identity = service.identity(uuid);

            // Typed as Wrapper, not Formatter, so resolveDelegate can recognise it later.
            Wrapper wrapped = (who, content) ->
                    Message.join(prefix(identity), delegate.format(who, content));
            event.setFormatter(wrapped);
        } catch (RuntimeException e) {
            // A decoration failure must never swallow somebody's message. Leaving the formatter
            // as it is means the line still goes out, just undecorated.
        }
    }

    /**
     * The formatter to delegate to.
     *
     * <p>Null-safe, and guards against wrapping ourselves: if this handler somehow ran twice for
     * one event, delegating to our own wrapper would nest the tag or recurse.
     */
    private PlayerChatEvent.Formatter resolveDelegate(PlayerChatEvent.Formatter current) {
        if (current == null || current instanceof Wrapper) {
            return PlayerChatEvent.DEFAULT_FORMATTER;
        }
        return current;
    }

    /** Marker so {@link #resolveDelegate} can recognise our own formatter. */
    private interface Wrapper extends PlayerChatEvent.Formatter {}

    private Message prefix(SupporterIdentity identity) {
        Color color = parseColor(identity.chatColor());
        if (color == null) {
            color = defaultTagColor;
        }
        List<Message> parts = new ArrayList<>(2);
        parts.add(Message.raw("[Supporter] ").color(color));
        if (identity.hasTitle()) {
            parts.add(Message.raw(identity.title() + " ").color(color));
        }
        return Message.join(parts.toArray(new Message[0]));
    }

    /** Null on anything unparseable — the colour is cosmetic and must never break a message. */
    private static Color parseColor(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        try {
            return Color.decode(hex.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
