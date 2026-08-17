package com.peoplesserver.supportermod.platform.hytale;

import com.peoplesserver.supportermod.platform.PermissionSync;
import com.peoplesserver.supportermod.platform.PluginLog;
import java.util.List;
import java.util.UUID;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.data.TemporaryNodeMergeStrategy;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;

/**
 * Pushes supporter status into LuckPerms as <b>temporary</b> permission nodes.
 *
 * <p>Temporary is the whole point. A permanent node plus a removal job means two systems that
 * must agree about when somebody stopped paying, and they will eventually disagree — a missed
 * reconcile, a server down over the expiry, a crash between the two writes. A node that carries
 * its own expiry cannot drift: LuckPerms removes it whether or not this plugin is running.
 *
 * <p>Expiry is set to the end of the grace window rather than to {@code expiresAt}, so it lines
 * up exactly with {@code SupporterService.isSupporter}, which stays true through grace. A
 * supporter must not lose their homes three days before they lose their chat tag.
 *
 * <p>Renewal uses {@link TemporaryNodeMergeStrategy#REPLACE_EXISTING_IF_DURATION_LONGER}: buying
 * more time extends the node, and re-running a grant with a shorter window can never shorten
 * what somebody already has.
 *
 * <p>Everything here is best-effort and asynchronous. LuckPerms returns futures, and a failure
 * to write a permission must never fail the purchase that triggered it — the money has already
 * changed hands. Failures are logged loudly instead, because a silent one means a paying
 * customer without their perk.
 *
 * <p>Verified against LuckPerms-Hytale 5.5.52:
 * <pre>
 * LuckPermsProvider.get() -> LuckPerms
 * LuckPerms.getUserManager().modifyUser(UUID, Consumer&lt;User&gt;) -> CompletableFuture&lt;Void&gt;
 * PermissionNode.builder(String).expiry(long) -> Builder
 * User.data().add(Node, TemporaryNodeMergeStrategy)
 * </pre>
 */
public final class LuckPermsSync implements PermissionSync {

    private final List<String> nodes;
    private final PluginLog log;

    public LuckPermsSync(List<String> nodes, PluginLog log) {
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        this.log = log;
    }

    /** Logged once, so a genuinely missing LuckPerms is reported without flooding. */
    private volatile boolean warnedMissing;

    /**
     * True if LuckPerms is reachable right now.
     *
     * <p><b>Never call this at plugin startup.</b> {@code setup()} runs before other plugins are
     * enabled — the live server logged this returning false eleven seconds before LuckPerms was
     * enabled — so a startup probe caches a "no" that is wrong for the whole session. Resolve on
     * use instead; by the time a grant or a login happens, everything is up.
     */
    public static boolean available() {
        try {
            return LuckPermsProvider.get() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void grant(UUID uuid, String username, long expiresAtMs) {
        if (nodes.isEmpty() || uuid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (expiresAtMs <= now) {
            revoke(uuid, username);
            return;
        }
        modify(uuid, username, "grant", user -> {
            for (String permission : nodes) {
                Node node = PermissionNode.builder(permission)
                        .expiry(expiresAtMs / 1000L)     // LuckPerms takes epoch SECONDS
                        .build();
                user.data().add(node, TemporaryNodeMergeStrategy.REPLACE_EXISTING_IF_DURATION_LONGER);
            }
        });
    }

    @Override
    public void revoke(UUID uuid, String username) {
        if (nodes.isEmpty() || uuid == null) {
            return;
        }
        modify(uuid, username, "revoke", user ->
                // Clear by key rather than by exact node: the stored node carries an expiry we
                // no longer know, so an equality-based remove would miss it.
                user.data().clear(n -> n instanceof PermissionNode p
                        && nodes.contains(p.getPermission())));
    }

    private void modify(UUID uuid, String username, String what,
                        java.util.function.Consumer<net.luckperms.api.model.user.User> body) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            if (api == null) {
                return;
            }
            api.getUserManager().modifyUser(uuid, body)
                    .exceptionally(e -> {
                        log.error("LuckPerms " + what + " failed for "
                                + (username == null ? uuid.toString() : username)
                                + " — their permissions may not match their supporter status",
                                e);
                        return null;
                    });
        } catch (Throwable t) {
            // LuckPerms absent, or its classes not visible to this plugin's classloader. Not
            // fatal: every other perk is gated on SupporterService and is unaffected. Logged
            // once rather than on every grant, but logged loudly the first time — silently
            // skipping means a paying customer without the perk they bought.
            if (!warnedMissing) {
                warnedMissing = true;
                log.warn("LuckPerms unavailable (" + t.getClass().getSimpleName()
                        + ") — permission-gated perks such as homes will NOT be applied. "
                        + "Check LuckPerms is installed and declared in OptionalDependencies.");
            }
        }
    }
}
