package com.peoplesserver.supportermod.platform;

import java.util.UUID;

/**
 * Port for sending a message to a single player.
 *
 * <p>Deliberately a plain string: Phase 0 question A5 (the full {@code Message} API) has not
 * been answered, so the core refuses to depend on a formatting model it cannot verify.
 * Colour and formatting land in the Hytale adapter, not here.
 */
public interface Messenger {

    /** Sends a message to the player if they are online; a no-op otherwise. */
    void send(UUID uuid, String message);

    /**
     * Sends to everyone currently online.
     *
     * <p>The server has no broadcast API — Phase 0b confirmed every broadcast in FactionMod and
     * HyUI is a manual loop over connected players — so this exists to keep that loop in the
     * adapter rather than in the entitlement core.
     */
    void broadcast(String message);
}
