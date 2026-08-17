package com.peoplesserver.supportermod.platform.hytale;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerSetupConnectEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.peoplesserver.supportermod.core.SupporterService;
import com.peoplesserver.supportermod.platform.PluginLog;
import java.awt.Color;
import java.util.UUID;

/**
 * Keeps the last few player slots free for supporters.
 *
 * <p>This is the perk the recon originally marked UNPROVEN and told us not to list on the store
 * page. Phase 0b found it is buildable after all: {@code PlayerSetupConnectEvent} is
 * {@code ICancellable}, fires at authentication before world entry, carries the UUID, and
 * exposes {@code setReason(Message)} so a rejection can explain itself instead of looking like
 * a crash. The earlier "no connection-stage hook" conclusion came from FactionMod only using
 * {@code PlayerReadyEvent}, which is not evidence the hook is absent.
 *
 * <p><b>Off unless configured.</b> {@code reservedSlots} defaults to 0, in which case this class
 * never cancels anything. A perk that turns players away is not something to enable by
 * accident.
 *
 * <p><b>It reserves, it does not queue.</b> Nothing here holds a connection open waiting for
 * space — a rejected player is told the server is full and asked to retry. Real queueing needs
 * somewhere to park a half-open connection, which this event does not offer.
 *
 * <p>Two things it deliberately will not do:
 *
 * <ul>
 *   <li><b>Never kick anyone.</b> Reserved slots stop a non-supporter <em>joining</em> a nearly
 *       full server; they never remove somebody already playing. Selling the right to eject
 *       another player is a different product and a worse one.
 *   <li><b>Never reject a supporter.</b> Supporters are checked before the reservation applies,
 *       so the slots are genuinely additional headroom for them rather than a lottery.
 * </ul>
 */
public final class ReservedSlots {

    private static final Color WARN = new Color(0xE0A54B);

    private final SupporterService service;
    private final int reservedSlots;
    private final PluginLog log;

    public ReservedSlots(SupporterService service, int reservedSlots, PluginLog log) {
        this.service = service;
        this.reservedSlots = Math.max(0, reservedSlots);
        this.log = log;
    }

    public boolean enabled() {
        return reservedSlots > 0;
    }

    /** Registered with {@code registerGlobal(PlayerSetupConnectEvent.class, ...)}. */
    public void onSetupConnect(PlayerSetupConnectEvent event) {
        if (!enabled() || event.isCancelled()) {
            return; // someone else already refused this connection — do not second-guess it
        }
        try {
            UUID uuid = event.getUuid();
            if (uuid != null && service.isSupporter(uuid)) {
                return; // the whole point of the perk
            }

            int max = maxPlayers();
            if (max <= 0) {
                return; // no cap configured, so nothing to reserve against
            }
            int online = onlinePlayers();
            int openToEveryone = max - reservedSlots;
            if (online < openToEveryone) {
                return; // plenty of room
            }

            event.setCancelled(true);
            event.setReason(Message.raw(
                    "The server is full. The last " + reservedSlots + " slot(s) are reserved "
                            + "for supporters — please try again shortly.").color(WARN));
            log.info("Reserved-slot refusal: " + event.getUsername()
                    + " (" + online + "/" + max + ", " + reservedSlots + " reserved)");
        } catch (RuntimeException e) {
            // A failure here must never block a legitimate connection. Letting somebody in when
            // we cannot tell is far better than locking the server over a bug in a perk.
            log.error("Reserved-slot check failed — allowing the connection", e);
        }
    }

    private static int maxPlayers() {
        try {
            HytaleServer server = HytaleServer.get();
            return server == null || server.getConfig() == null
                    ? 0 : server.getConfig().getMaxPlayers();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int onlinePlayers() {
        try {
            Universe universe = Universe.get();
            return universe == null ? 0 : universe.getPlayerCount();
        } catch (Throwable t) {
            return 0;
        }
    }
}
