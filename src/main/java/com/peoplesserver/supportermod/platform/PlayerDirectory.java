package com.peoplesserver.supportermod.platform;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for player identity lookups.
 *
 * <p>The Hytale-backed implementation lands once Phase 0 answers how the server resolves a
 * username to a UUID for a player who has never logged in. If the server cannot do that
 * offline, {@link #uuidFor} returns empty and {@code SupporterService} queues the grant as
 * pending until the player next joins — which is why Tebex grants keep working either way.
 */
public interface PlayerDirectory {

    /** Resolves a username to a UUID, or empty if the server cannot resolve it offline. */
    Optional<UUID> uuidFor(String username);

    /** Last known username for a UUID, if any. */
    Optional<String> usernameFor(UUID uuid);

    /** Whether the player is currently connected. */
    boolean isOnline(UUID uuid);
}
