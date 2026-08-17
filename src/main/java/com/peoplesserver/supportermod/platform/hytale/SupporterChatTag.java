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
 * Renders the supporter chat line.
 *
 * <p><b>This handler never cancels the event and never sends a message itself.</b> That is the
 * whole design, and it is what makes coexistence with FactionMod safe.
 *
 * <p>The obvious implementation — cancel the event and broadcast a decorated line — is a trap
 * here. FactionMod cancels {@code PlayerChatEvent} when a player has faction chat toggled on
 * and re-broadcasts to their faction only. If SupporterMod also cancelled and broadcast
 * server-wide, a supporter in faction-chat mode would have their private faction message
 * leaked to everybody. That is a confidentiality bug, not a cosmetic one.
 *
 * <p>Instead we call {@code setFormatter}, so the server keeps ownership of delivery and simply
 * asks us how to render the line. If FactionMod has already cancelled, our formatter is never
 * invoked and nothing of ours is sent. The early {@code isCancelled()} return is belt and
 * braces on top of that — verified present on this server:
 *
 * <pre>
 * public class PlayerChatEvent implements IAsyncEvent&lt;String&gt;, ICancellable {
 *     public boolean isCancelled();
 *     public void setFormatter(Formatter);
 * }
 * public interface PlayerChatEvent.Formatter { Message format(PlayerRef sender, String content); }
 * </pre>
 *
 * <p>The line: {@code [Supporter] <title> Name: message}, with the tag and title in the
 * configured tag colour and the username in the player's chosen colour. The message body is
 * left uncoloured on purpose — colouring whole messages makes a busy chat hard to read, and the
 * perk is still plainly visible. Moving the colour to the body is a one-line change in
 * {@link #render} if that is preferred.
 */
public final class SupporterChatTag {

    private final SupporterService service;
    private final Color tagColor;

    public SupporterChatTag(SupporterService service, Color tagColor) {
        this.service = service;
        this.tagColor = tagColor;
    }

    /** Registered with {@code getEventRegistry().registerGlobal(PlayerChatEvent.class, ...)}. */
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

            // O(1) map lookup, no database. This runs for every message on the server, so it
            // has to stay that way — see SupporterService.isSupporter.
            if (uuid == null || !service.isSupporter(uuid)) {
                return;
            }

            SupporterIdentity identity = service.identity(uuid);
            event.setFormatter((who, content) -> render(who, content, identity));
        } catch (RuntimeException e) {
            // Never let a decoration failure swallow somebody's message. Leaving the formatter
            // untouched means the server sends the normal line.
            event.setFormatter(PlayerChatEvent.DEFAULT_FORMATTER);
        }
    }

    private Message render(PlayerRef sender, String content, SupporterIdentity identity) {
        List<Message> parts = new ArrayList<>(4);
        parts.add(Message.raw("[Supporter] ").color(tagColor));
        if (identity.hasTitle()) {
            parts.add(Message.raw(identity.title() + " ").color(tagColor));
        }

        Message name = Message.raw(sender.getUsername());
        Color chosen = parseColor(identity.chatColor());
        parts.add(chosen == null ? name : name.color(chosen));

        parts.add(Message.raw(": " + content));
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
