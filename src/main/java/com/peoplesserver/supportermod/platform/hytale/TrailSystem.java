package com.peoplesserver.supportermod.platform.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.core.SupporterIdentity;
import com.peoplesserver.supportermod.core.SupporterService;
import com.peoplesserver.supportermod.platform.PluginLog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3d;

/**
 * Emits a particle behind each supporter who has chosen a trail.
 *
 * <p><b>This is the most performance-sensitive thing in the plugin, and the one most likely to
 * hurt the server if it is written naively.</b> It runs continuously rather than on demand, for
 * every supporter at once, and this server has already been taken down once by a plugin that
 * pushed too much per-frame work at clients. Four controls keep it honest, and none of them is
 * optional:
 *
 * <ol>
 *   <li><b>Only on movement.</b> A player who has not moved {@code trailMinMoveDistance} since
 *       their last emit produces nothing. Without this a whole server standing in spawn emits
 *       continuously forever, which is both ugly and pure waste. This is the single biggest
 *       saving, because most online players are stationary most of the time.
 *   <li><b>A viewer list, not a broadcast.</b> The particle is sent only to players within
 *       {@code trailViewDistance}, and never to anyone who has hidden trails. This is the
 *       overload the recon found:
 *       {@code spawnParticleEffect(String, Vector3d, List<Ref>, ComponentAccessor)}.
 *   <li><b>A hard cap per tick.</b> {@code maxConcurrentTrails} bounds the work regardless of
 *       how many supporters are online, so the cost has a ceiling rather than scaling with
 *       however many people bought the perk.
 *   <li><b>World-thread discipline.</b> The scheduler thread resolves the world and then hands
 *       off with {@code world.execute(...)}. Every component read and every particle spawn
 *       happens inside that callback. This is the structure FactionMod's ZoneVisualizationSystem
 *       uses, and doing entity work directly on a scheduler thread trips the world-thread
 *       assertion.
 * </ol>
 *
 * <p>Trails are addressed by config id rather than by particle name, so an admin can repoint
 * {@code ember} at a different effect — or a cheaper one — without touching stored rows.
 */
public final class TrailSystem {

    private final SupporterService service;
    private final SupporterConfig config;
    private final PluginLog log;

    /** Last position an emit happened at, per player. Drives the movement check. */
    private final Map<UUID, Vector3d> lastEmit = new ConcurrentHashMap<>();

    private volatile long lastWarnAt;

    public TrailSystem(SupporterService service, SupporterConfig config, PluginLog log) {
        this.service = service;
        this.config = config;
        this.log = log;
    }

    /** Called from the scheduler thread. Does no world work itself. */
    public void tick() {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return; // still starting up
            }
            for (World world : universe.getWorlds().values()) {
                if (world == null) {
                    continue;
                }
                final World w = world;
                w.execute(() -> tickWorld(w));
            }
        } catch (Throwable t) {
            warnOccasionally("Trail tick failed", t);
        }
    }

    /** Runs ON the world thread. Everything that touches entities belongs here. */
    private void tickWorld(World world) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (store == null) {
                return;
            }
            Collection<PlayerRef> players = world.getPlayerRefs();
            if (players == null || players.isEmpty()) {
                return;
            }

            // Snapshot once. Positions are read repeatedly below — once to decide whether each
            // supporter moved, and again to decide who is close enough to see it.
            List<Viewer> viewers = snapshot(players, store);
            if (viewers.isEmpty()) {
                return;
            }

            int budget = config.maxConcurrentTrails();
            double minMove = config.trailMinMoveDistance();
            double viewDistSq = (double) config.trailViewDistance() * config.trailViewDistance();

            for (Viewer emitter : viewers) {
                if (budget <= 0) {
                    break; // hard ceiling — the cost does not scale with sales
                }
                if (!service.isSupporter(emitter.uuid)) {
                    continue;
                }
                SupporterIdentity identity = service.identity(emitter.uuid);
                if (!identity.hasTrail()) {
                    continue;
                }
                String effect = config.trails().get(identity.trail());
                if (effect == null || effect.isBlank()) {
                    continue; // configured trail was removed from the map
                }
                if (!hasMovedEnough(emitter, minMove)) {
                    continue;
                }

                List<Ref<EntityStore>> audience = audienceFor(emitter, viewers, viewDistSq);
                if (audience.isEmpty()) {
                    // Nobody can see it. Still record the position, or the player would emit
                    // on their very next step regardless of distance travelled.
                    lastEmit.put(emitter.uuid, new Vector3d(emitter.pos));
                    continue;
                }

                ParticleUtil.spawnParticleEffect(effect, new Vector3d(emitter.pos), audience, store);
                lastEmit.put(emitter.uuid, new Vector3d(emitter.pos));
                budget--;
            }

            // Players who left keep an entry otherwise; this map would grow for the lifetime of
            // the process.
            if (lastEmit.size() > viewers.size() * 4 + 64) {
                lastEmit.keySet().retainAll(
                        viewers.stream().map(v -> v.uuid).collect(java.util.stream.Collectors.toSet()));
            }
        } catch (Throwable t) {
            warnOccasionally("Trail tickWorld failed", t);
        }
    }

    private boolean hasMovedEnough(Viewer emitter, double minMove) {
        Vector3d previous = lastEmit.get(emitter.uuid);
        if (previous == null) {
            return true; // first emit of the session
        }
        return previous.distanceSquared(emitter.pos) >= minMove * minMove;
    }

    /** Players close enough to see the trail, minus anyone who has turned trails off. */
    private List<Ref<EntityStore>> audienceFor(
            Viewer emitter, List<Viewer> all, double viewDistSq) {
        List<Ref<EntityStore>> out = new ArrayList<>();
        for (Viewer v : all) {
            if (v.ref == null) {
                continue;
            }
            if (v.hidesTrails) {
                continue; // opted out — never send them anyone's particles
            }
            if (v.pos.distanceSquared(emitter.pos) > viewDistSq) {
                continue;
            }
            out.add(v.ref);
        }
        return out;
    }

    private List<Viewer> snapshot(Collection<PlayerRef> players, Store<EntityStore> store) {
        List<Viewer> out = new ArrayList<>(players.size());
        for (PlayerRef p : players) {
            if (p == null) {
                continue;
            }
            UUID uuid = p.getUuid();
            Ref<EntityStore> ref = p.getReference();
            if (uuid == null || ref == null || !ref.isValid()) {
                continue;
            }
            TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
            if (tc == null || tc.getPosition() == null) {
                continue;
            }
            out.add(new Viewer(uuid, ref, new Vector3d(tc.getPosition()),
                    service.identity(uuid).hideTrails()));
        }
        return out;
    }

    /** Forget a player's position when they disconnect. */
    public void forget(UUID uuid) {
        if (uuid != null) {
            lastEmit.remove(uuid);
        }
    }

    private void warnOccasionally(String message, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < 60_000L) {
            return; // this runs several times a second; one failure must not flood the log
        }
        lastWarnAt = now;
        log.error(message, t);
    }

    /** One player, resolved once per world tick. */
    private record Viewer(UUID uuid, Ref<EntityStore> ref, Vector3d pos, boolean hidesTrails) {}
}
