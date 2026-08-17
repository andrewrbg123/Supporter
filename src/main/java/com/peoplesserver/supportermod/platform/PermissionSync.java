package com.peoplesserver.supportermod.platform;

import java.util.UUID;

/**
 * Port for pushing supporter status into a permissions plugin.
 *
 * <p>Needed because one perk is not ours to deliver. Chat tags, titles, trails and tokens are
 * all gated on {@code SupporterService.isSupporter}, so they switch on and off by themselves.
 * Homes are not: EliteEssentials owns {@code /home} on this server and resolves the limit
 * through LuckPerms nodes, so somebody has to tell LuckPerms when a supporter arrives and
 * leaves.
 *
 * <p>Doing that by hand is the failure mode this exists to prevent. People notice within
 * minutes when a perk they paid for does not arrive; nobody ever reports still having a perk
 * they stopped paying for. Manual expiry is therefore the half that silently rots.
 *
 * <p>A port rather than a direct call so the entitlement core stays testable without LuckPerms
 * on the classpath, and so a server without a permissions plugin degrades to {@link #noop()}
 * instead of failing.
 */
public interface PermissionSync {

    /**
     * Grants supporter permissions until {@code expiresAtMs}.
     *
     * <p>Implementations should prefer a <b>temporary</b> grant that the permissions plugin
     * expires by itself. That way removal does not depend on this plugin running at the right
     * moment — it still lapses correctly if the server is down over the expiry.
     */
    void grant(UUID uuid, String username, long expiresAtMs);

    /** Removes supporter permissions now, for a revoke or a chargeback. */
    void revoke(UUID uuid, String username);

    /** Used when no permissions plugin is present, and by tests. */
    static PermissionSync noop() {
        return new PermissionSync() {
            @Override
            public void grant(UUID uuid, String username, long expiresAtMs) {
                // nothing to do
            }

            @Override
            public void revoke(UUID uuid, String username) {
                // nothing to do
            }
        };
    }
}
