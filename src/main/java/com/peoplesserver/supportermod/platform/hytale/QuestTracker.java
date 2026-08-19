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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.joml.Vector3d;

/**
 * Feeds quest progress from what players actually do: positions sampled every ten seconds for
 * travel, a minute tick for playtime and day-counting, and a chat listener for message quests.
 *
 * <p><b>Travel is a path, not a displacement.</b> The first build sampled once a minute and
 * credited the straight-line distance between samples, and the live test showed exactly what
 * that means: a player who runs, mines and fights ends most minutes near where they started,
 * so a busy session credited 45 blocks. Positions are therefore sampled every
 * {@link #SAMPLE_SECONDS} seconds into an in-memory accumulator — eight legs a minute
 * approximates the real path — and the accumulated whole blocks are flushed to the database on
 * the minute tick. Sampling is cheap (the trail system reads the same component every 400ms);
 * it is the WRITES that stay once a minute.
 *
 * <p><b>The minute tick IS the minute.</b> It must be scheduled at exactly 60s, because each
 * firing credits one minute of playtime to everyone online.
 *
 * <p>Thread discipline follows {@link TrailSystem}, with one extra hop: world threads only
 * <em>snapshot</em> positions and hand the list back to the single scheduler thread, which
 * owns all tracker state and every SQLite write. No lock needed — one writer.
 *
 * <p>A step longer than {@link #MAX_STEP_BLOCKS} in one sample is a teleport — /home, a warp,
 * a respawn — not travel, and resets the leg instead of counting. A world change does the
 * same: distance between two worlds is meaningless.
 */
public final class QuestTracker {

    private static final int SAMPLE_SECONDS = 10;
    /** ~15 blocks/second sustained — above any legitimate movement, below any teleport. */
    private static final double MAX_STEP_BLOCKS = 150;

    private final SupporterService service;
    private final Scheduler scheduler;
    private final PluginLog log;

    /** Per-player travel state. Touched only on the scheduler thread. */
    private final Map<UUID, Travel> travel = new HashMap<>();

    private volatile long lastWarnAt;

    public QuestTracker(SupporterService service, Scheduler scheduler, PluginLog log) {
        this.service = service;
        this.scheduler = scheduler;
        this.log = log;
    }

    /** Called from the scheduler thread every {@value #SAMPLE_SECONDS}s. Accumulates only. */
    public void sampleTick() {
        forEachWorld(samples -> accumulate(samples));
    }

    /** Called from the scheduler thread once a minute: playtime, day marks, travel flush. */
    public void minuteTick() {
        forEachWorld(samples -> applyMinute(samples));
    }

    private interface SampleSink {
        void accept(List<Sample> samples);
    }

    private void forEachWorld(SampleSink sink) {
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
                w.execute(() -> snapshotWorld(w, sink));
            }
        } catch (Throwable t) {
            warnOccasionally("Quest tick failed", t);
        }
    }

    /** Runs ON the world thread: reads positions, nothing else, and hands the list back. */
    private void snapshotWorld(World world, SampleSink sink) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (store == null) {
                return;
            }
            Collection<PlayerRef> players = world.getPlayerRefs();
            if (players == null || players.isEmpty()) {
                return;
            }
            // The world is identified by hash rather than held: keeping World references in
            // tracker state would pin unloaded worlds in memory.
            int worldId = System.identityHashCode(world);
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
                samples.add(new Sample(uuid, worldId, new Vector3d(tc.getPosition())));
            }
            if (!samples.isEmpty()) {
                scheduler.runOnce(() -> runSink(sink, samples), 0);
            }
        } catch (Throwable t) {
            warnOccasionally("Quest snapshot failed", t);
        }
    }

    private void runSink(SampleSink sink, List<Sample> samples) {
        try {
            sink.accept(samples);
        } catch (Throwable t) {
            warnOccasionally("Quest progress write failed", t);
        }
    }

    /** Scheduler thread: extends each player's path by one leg. No database. */
    private void accumulate(List<Sample> samples) {
        for (Sample s : samples) {
            Travel state = travel.get(s.uuid);
            if (state == null || state.worldId != s.worldId) {
                travel.put(s.uuid, new Travel(s.worldId, s.pos));
                continue;
            }
            double leg = state.pos.distance(s.pos);
            if (leg >= 0.5 && leg <= MAX_STEP_BLOCKS) {
                state.pending += leg;
            }
            // A teleport-sized leg counts nothing; either way the new position is the
            // baseline for the next leg.
            state.pos = s.pos;
        }
    }

    /** Scheduler thread: the once-a-minute credits and the travel flush. */
    private void applyMinute(List<Sample> samples) {
        accumulate(samples); // the minute boundary is also a leg
        for (Sample s : samples) {
            if (!service.isSupporter(s.uuid)) {
                travel.remove(s.uuid);
                continue;
            }
            service.markQuestDay(s.uuid);
            service.recordQuestProgress(s.uuid, Quests.Unit.MINUTES, 1);
            Travel state = travel.get(s.uuid);
            if (state != null && state.pending >= 1) {
                int whole = (int) state.pending;
                service.recordQuestProgress(s.uuid, Quests.Unit.BLOCKS, whole);
                state.pending -= whole;
            }
        }
        if (travel.size() > samples.size() * 4 + 64) {
            Set<UUID> online = samples.stream().map(s -> s.uuid).collect(Collectors.toSet());
            travel.keySet().retainAll(online);
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

    private record Sample(UUID uuid, int worldId, Vector3d pos) {}

    /** Mutable on purpose — owned by the single scheduler thread. */
    private static final class Travel {
        int worldId;
        Vector3d pos;
        double pending;

        Travel(int worldId, Vector3d pos) {
            this.worldId = worldId;
            this.pos = pos;
        }
    }
}
