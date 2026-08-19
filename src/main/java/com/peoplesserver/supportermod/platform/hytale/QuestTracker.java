package com.peoplesserver.supportermod.platform.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.peoplesserver.supportermod.core.Quests;
import com.peoplesserver.supportermod.core.SupporterService;
import com.peoplesserver.supportermod.platform.PluginLog;
import com.peoplesserver.supportermod.platform.Scheduler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.joml.Vector3d;

/**
 * Feeds quest progress from what players actually do: one tick a minute for playtime, distance
 * and day-counting, plus a chat listener for message quests.
 *
 * <p><b>The tick IS the minute.</b> It must be scheduled at exactly 60s, because each firing
 * credits one minute of playtime to everyone online — a different interval would make "Play
 * for 30 minutes" mean something else.
 *
 * <p>Thread discipline follows {@link TrailSystem} exactly, with one extra hop: the world
 * thread only <em>snapshots</em> positions, then hands the list back to the plugin scheduler
 * for the SQLite writes. Component reads belong on the world thread; database writes do not
 * need to be there and so are kept off it.
 *
 * <p>Distance is clamped per sample: a step longer than {@link #MAX_STEP_BLOCKS} in one minute
 * is a teleport — /home, a warp, a respawn — not travel, and does not count. Walking speed
 * cannot legitimately cover that far between samples.
 */
public final class QuestTracker {

    private static final double MAX_STEP_BLOCKS = 300;

    private final SupporterService service;
    private final Scheduler scheduler;
    private final PluginLog log;

    /** Last sampled position per player, for the distance delta. */
    private final Map<UUID, Vector3d> lastPos = new ConcurrentHashMap<>();

    private volatile long lastWarnAt;

    public QuestTracker(SupporterService service, Scheduler scheduler, PluginLog log) {
        this.service = service;
        this.scheduler = scheduler;
        this.log = log;
    }

    /** Called from the scheduler thread, once a minute. Does no world work itself. */
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
                w.execute(() -> snapshotWorld(w));
            }
        } catch (Throwable t) {
            warnOccasionally("Quest tick failed", t);
        }
    }

    /** Runs ON the world thread: reads positions, nothing else, and hands the list back. */
    private void snapshotWorld(World world) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (store == null) {
                return;
            }
            Collection<PlayerRef> players = world.getPlayerRefs();
            if (players == null || players.isEmpty()) {
                return;
            }
            List<Sample> samples = new ArrayList<>(players.size());
            for (PlayerRef p : players) {
                if (p == null) {
                    continue;
                }
                UUID uuid = p.getUuid();
                Ref<EntityStore> ref = p.getReference();
                if (uuid == null || ref == null || !ref.isValid()) {
                    continue;
                }
                TransformComponent tc =
                        store.getComponent(ref, TransformComponent.getComponentType());
                if (tc == null || tc.getPosition() == null) {
                    continue;
                }
                samples.add(new Sample(uuid, new Vector3d(tc.getPosition())));
            }
            if (!samples.isEmpty()) {
                scheduler.runOnce(() -> apply(samples), 0);
            }
        } catch (Throwable t) {
            warnOccasionally("Quest snapshot failed", t);
        }
    }

    /** Runs on the scheduler thread: all the SQLite writes. */
    private void apply(List<Sample> samples) {
        try {
            for (Sample s : samples) {
                if (!service.isSupporter(s.uuid)) {
                    lastPos.remove(s.uuid);
                    continue;
                }
                service.markQuestDay(s.uuid);
                service.recordQuestProgress(s.uuid, Quests.Unit.MINUTES, 1);
                Vector3d previous = lastPos.get(s.uuid);
                if (previous != null) {
                    double moved = previous.distance(s.pos);
                    if (moved >= 1 && moved <= MAX_STEP_BLOCKS) {
                        service.recordQuestProgress(s.uuid, Quests.Unit.BLOCKS, (int) moved);
                    }
                }
                lastPos.put(s.uuid, s.pos);
            }
            if (lastPos.size() > samples.size() * 4 + 64) {
                lastPos.keySet().retainAll(samples.stream().map(s -> s.uuid)
                        .collect(java.util.stream.Collectors.toSet()));
            }
        } catch (Throwable t) {
            warnOccasionally("Quest progress write failed", t);
        }
    }

    /**
     * Message quests. Registered alongside the chat tag, at default priority — it only reads.
     * A cancelled event is private (faction chat) and does not count.
     */
    public void onPlayerChat(PlayerChatEvent event) {
        try {
            if (event.isCancelled()) {
                return;
            }
            PlayerRef sender = event.getSender();
            UUID uuid = sender == null ? null : sender.getUuid();
            if (uuid == null) {
                return;
            }
            service.recordQuestProgress(uuid, Quests.Unit.MESSAGES, 1);
        } catch (Throwable t) {
            // A quest counter must never cost anybody a chat message.
            warnOccasionally("Quest chat count failed", t);
        }
    }

    private void warnOccasionally(String message, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < 60_000L) {
            return;
        }
        lastWarnAt = now;
        log.error(message, t);
    }

    private record Sample(UUID uuid, Vector3d pos) {}
}
