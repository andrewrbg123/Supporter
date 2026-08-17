package com.peoplesserver.supportermod.platform.hytale;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.peoplesserver.supportermod.platform.Messenger;
import java.awt.Color;
import java.util.UUID;

/**
 * {@link Messenger} backed by {@code PlayerRef.sendMessage}.
 *
 * <p>Verified signatures:
 *
 * <pre>
 * public static Message Message.raw(String)
 * public Message Message.color(java.awt.Color)      // also color(String)
 * public static Message Message.join(Message...)
 * public void PlayerRef.sendMessage(Message)
 * public PlayerRef Universe.getPlayer(UUID)
 * </pre>
 *
 * <p>Two guards matter here.
 *
 * <p><b>{@code Universe.get()} can return null during {@code setup()}.</b> FactionMod hits this
 * and falls back to {@code AddWorldEvent}. Anything the plugin sends before the universe exists
 * must be dropped rather than throw — a message is never worth failing a boot for.
 *
 * <p><b>An offline player is a no-op, not an error.</b> The port's contract says so, and the
 * reconcile job leans on it: it messages everyone whose entitlement just lapsed without first
 * checking who is connected.
 */
public final class HytaleMessenger implements Messenger {

    private final Color tagColor;

    /**
     * @param tagColor colour applied to the {@code [Supporter]} prefix, from
     *     {@code SupporterConfig.tagColorHex()}
     */
    public HytaleMessenger(Color tagColor) {
        this.tagColor = tagColor;
    }

    @Override
    public void send(UUID uuid, String message) {
        PlayerRef player = online(uuid);
        if (player == null) {
            return;
        }
        player.sendMessage(Message.join(
                Message.raw("[Supporter] ").color(tagColor),
                Message.raw(message)));
    }

    /** Sends without the plugin prefix — for command replies, which carry their own framing. */
    public void sendPlain(UUID uuid, String message, Color color) {
        PlayerRef player = online(uuid);
        if (player == null) {
            return;
        }
        player.sendMessage(color == null
                ? Message.raw(message)
                : Message.raw(message).color(color));
    }

    private static PlayerRef online(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Universe universe = Universe.get();
        return universe == null ? null : universe.getPlayer(uuid);
    }
}
