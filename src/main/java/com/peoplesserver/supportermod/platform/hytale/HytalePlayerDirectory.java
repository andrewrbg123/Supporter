package com.peoplesserver.supportermod.platform.hytale;

import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.peoplesserver.supportermod.platform.PlayerDirectory;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PlayerDirectory} backed by {@link Universe}.
 *
 * <p><b>{@link #uuidFor} is online-only, and that is a hard limit of the server, not a
 * shortcut.</b> Phase 0b settled it: the only offline player store is
 *
 * <pre>
 * public interface PlayerStorage {
 *     CompletableFuture&lt;Holder&lt;EntityStore&gt;&gt; load(UUID);
 *     Set&lt;UUID&gt; getPlayers() throws IOException;
 *     ...
 * }
 * </pre>
 *
 * <p>Everything is keyed by UUID and there is no username index. Resolving a name offline would
 * mean loading every player's {@code Holder} and comparing — not viable, and certainly not on a
 * join path.
 *
 * <p>So a gift purchase for somebody who has never logged in cannot resolve here, and
 * {@code SupporterService.grantByUsername} queues it in {@code pending_grants} instead. That is
 * why the pending-grant table is the main path rather than a fallback: the purchase is held
 * until the player's next login and never dropped.
 *
 * <p>{@code EXACT_IGNORE_CASE} is the right matching mode. {@code STARTS_WITH} would let a
 * Tebex grant for "Andy" land on "AndyTheSecond", which silently gives one player's purchase to
 * another — the worst failure this class can have.
 */
public final class HytalePlayerDirectory implements PlayerDirectory {

    @Override
    public Optional<UUID> uuidFor(String username) {
        PlayerRef player = byName(username);
        return player == null ? Optional.empty() : Optional.ofNullable(player.getUuid());
    }

    @Override
    public Optional<String> usernameFor(UUID uuid) {
        PlayerRef player = byUuid(uuid);
        return player == null ? Optional.empty() : Optional.ofNullable(player.getUsername());
    }

    @Override
    public boolean isOnline(UUID uuid) {
        return byUuid(uuid) != null;
    }

    private static PlayerRef byUuid(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Universe universe = Universe.get();
        return universe == null ? null : universe.getPlayer(uuid);
    }

    private static PlayerRef byName(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        return universe.getPlayerByUsername(username.trim(), NameMatching.EXACT_IGNORE_CASE);
    }
}
