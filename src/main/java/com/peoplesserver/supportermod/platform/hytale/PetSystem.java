package com.peoplesserver.supportermod.platform.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.peoplesserver.supportermod.core.SupporterIdentity;
import com.peoplesserver.supportermod.core.SupporterService;
import com.peoplesserver.supportermod.platform.PluginLog;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import org.joml.Vector3d;

/**
 * SPIKE (0.21.5): cosmetic pet followers — one NPC per owner that walks after them.
 *
 * <p>Everything here is assembled from mechanisms already proven live on this server by the
 * FactionMod night-raid work: {@code NPCPlugin.spawnNPC} with a plugin-shipped
 * {@code Server/NPC/Roles/} role, and the ReadPosition/Seek follow — the plugin stamps the
 * owner's position into the role's stored-position slot 0 each tick and the engine's own A*
 * does the walking. The pet roles are {@code Type:"Generic"}, which has no combat tree at all:
 * a pet cannot fight, aggro, or be commanded to — the pay-to-win line enforced by the role
 * type itself.
 *
 * <p><b>Lifecycle discipline is the FactionMod lessons, all four:</b> shutdown removal is
 * SYNCHRONOUS (a {@code world.execute} during shutdown silently never runs, which is how NPCs
 * leak into the world permanently); every removal's outcome is checked rather than assumed;
 * tracking is per-owner and dropped only when the entity is actually gone; and each world's
 * own tick despawns pets whose owner has left it, so a logout or world-change never orphans
 * one.
 *
 * <p>The spike answers what only a live look can: does a creature rig ANIMATE on a Generic
 * role, does the follow keep up with a running player, and does the pet interfere with
 * anything it shouldn't.
 */
public final class PetSystem {

    /** Beyond this the pet is respawned at the owner — sprinting outruns any follower. */
    private static final double CATCHUP_DISTANCE = 40;

    /**
     * The dead-man's switch (0.22.3). Every pet carries a {@link DespawnComponent} that the
     * tick refreshes every second; a pet the plugin loses track of — a silently failed
     * removal, a chunk unload, a kill -9 — is collected by the ENGINE within this window.
     * Added after a live duplicate: a teleport-catchup removed a bunny whose chunk had
     * unloaded, the removal silently failed exactly as the raid work documented, tracking was
     * replaced, and the old bunny survived as a stray. Rather than verifying every removal
     * path forever, the timer makes a leak self-collecting: correctness no longer depends on
     * our bookkeeping being perfect.
     */
    private static final long DESPAWN_TTL_SECONDS = 120;

    /** Every pet role this plugin spawns shares this prefix, which is what makes a sweep possible. */
    private static final String ROLE_PREFIX = "SupporterPet_";

    /** Owner uuid → live pet. */
    private final Map<UUID, Pet> pets = new ConcurrentHashMap<>();

    /**
     * Worlds already swept for strays this session, by name.
     *
     * <p><b>The restart duplicate came from here.</b> A pet that outlives the server — a crash,
     * a kill -9, a shutdown removal that silently failed, or simply an unloaded chunk — is
     * still in the world when the server comes back, but the tracking map is empty, so the
     * auto-spawn happily adds a second one and the owner ends up with two. The despawn timer
     * cannot save it either: the component does not come back with a persisted entity.
     *
     * <p>So the first tick in each world sweeps every {@code SupporterPet_} NPC it can find and
     * removes the ones nothing is tracking. At boot that is all of them, which is correct — no
     * pet legitimately exists before a player has logged in and been given one.
     */
    private final java.util.Set<String> sweptWorlds = ConcurrentHashMap.newKeySet();

    private final SupporterService service;
    /** Pet name → role name; the command layer's catalogue, shared not copied. */
    private final Map<String, String> catalogue;
    /** Server-wide live-pet cap, read from config each time so a config edit applies live. */
    private final IntSupplier cap;
    private final PluginLog log;
    private volatile long lastWarnAt;

    public PetSystem(SupporterService service, Map<String, String> catalogue, IntSupplier cap,
                     PluginLog log) {
        this.service = service;
        this.catalogue = catalogue;
        this.cap = cap;
        this.log = log;
    }

    /**
     * Spawns (or replaces) the owner's pet. Must run on the owner's world thread — commands
     * already do.
     *
     * @return null on success, otherwise a message for the player
     */
    public String spawn(Store<EntityStore> store, UUID ownerUuid, Vector3d ownerPos,
                        String roleName) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return "NPC plugin unavailable";
        }
        if (!npcPlugin.hasRoleName(roleName)) {
            return "pet role " + roleName + " is not loaded — check the boot log";
        }
        if (!pets.containsKey(ownerUuid) && pets.size() >= cap.getAsInt()) {
            return "the pet stable is full right now — your choice is saved and your pet "
                    + "will appear when room frees up";
        }
        removeFor(ownerUuid, store);
        var pair = npcPlugin.spawnNPC(store, roleName, null,
                new Vector3d(ownerPos).add(1.5, 0, 1.5), new Rotation3f(0, 0, 0));
        if (pair == null || pair.left() == null) {
            // hasRoleName is true even for a role that failed builder validation — this is
            // where that failure surfaces.
            return "spawn failed — the role may not have validated; see the boot log";
        }
        Ref<EntityStore> ref = pair.left();
        store.putComponent(ref, DespawnComponent.getComponentType(),
                new DespawnComponent(java.time.Instant.now()
                        .plusSeconds(DESPAWN_TTL_SECONDS)));
        pets.put(ownerUuid, new Pet(roleName, ref));
        stamp(store, ref, ownerPos);
        return null;
    }

    /** Removes the owner's pet if it lives in this store. Owner's world thread. */
    public boolean removeFor(UUID ownerUuid, Store<EntityStore> store) {
        Pet pet = pets.get(ownerUuid);
        if (pet == null) {
            return false;
        }
        if (pet.ref.isValid() && pet.ref.getStore() == store) {
            store.removeEntity(pet.ref, RemoveReason.REMOVE);
            if (pet.ref.isValid()) {
                // Removal can silently fail (unloaded chunk, mid-tick state). The live
                // duplicate of 2026-08-19 was exactly this; now the timer collects it.
                log.warn("Pet removal did not take for " + ownerUuid
                        + " — the despawn timer will collect it");
            }
        }
        pets.remove(ownerUuid);
        return true;
    }

    public boolean hasPet(UUID ownerUuid) {
        return pets.containsKey(ownerUuid);
    }

    /** Called from the scheduler thread. Does no world work itself. */
    public void tick() {
        // No early-out on an empty map: the auto-spawn pass is what brings a stored pet back
        // at login, after a world change, or after something killed it.
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            for (World world : universe.getWorlds().values()) {
                if (world == null) {
                    continue;
                }
                final World w = world;
                w.execute(() -> tickWorld(w));
            }
        } catch (Throwable t) {
            warnOccasionally("Pet tick failed", t);
        }
    }

    /** Runs ON the world thread: follow stamps, catch-ups, and orphan cleanup for this world. */
    private void tickWorld(World world) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (store == null) {
                return;
            }
            // Before anything else, and once per world: clear pets left over from a previous
            // life of the server. Doing it here rather than at setup is deliberate — worlds do
            // not exist yet when the plugin starts, and this tick is the first moment one is
            // reliably loaded and on its own thread.
            if (sweptWorlds.add(world.getName())) {
                int strays = sweepStrays(world, store);
                if (strays > 0) {
                    log.info("Pet boot sweep removed " + strays + " leftover pet(s) in "
                            + world.getName());
                }
            }
            Map<UUID, Vector3d> playersHere = new HashMap<>();
            Collection<PlayerRef> players = world.getPlayerRefs();
            if (players != null) {
                for (PlayerRef p : players) {
                    if (p == null || p.getUuid() == null) {
                        continue;
                    }
                    Ref<EntityStore> ref = p.getReference();
                    if (ref == null || !ref.isValid()) {
                        continue;
                    }
                    TransformComponent tc =
                            store.getComponent(ref, TransformComponent.getComponentType());
                    if (tc != null && tc.getPosition() != null) {
                        playersHere.put(p.getUuid(), new Vector3d(tc.getPosition()));
                    }
                }
            }

            for (Map.Entry<UUID, Pet> entry : pets.entrySet()) {
                Pet pet = entry.getValue();
                if (pet.ref.getStore() != store) {
                    continue; // another world's pet; its own world's tick owns it
                }
                if (!pet.ref.isValid()) {
                    // Gone without us removing it — killed, unloaded, or cleaned up by the
                    // engine. Drop tracking; the spike does not auto-respawn.
                    pets.remove(entry.getKey());
                    log.info("Pet for " + entry.getKey() + " vanished (" + pet.role + ")");
                    continue;
                }
                Vector3d ownerPos = playersHere.get(entry.getKey());
                if (ownerPos == null) {
                    // The owner is not in this world any more (logout or world change): a pet
                    // with no owner present is exactly the leak the raid work warned about.
                    store.removeEntity(pet.ref, RemoveReason.REMOVE);
                    if (pet.ref.isValid()) {
                        log.warn("Pet orphan removal did not take for " + entry.getKey()
                                + " — the despawn timer will collect it");
                    }
                    pets.remove(entry.getKey());
                    continue;
                }

                TransformComponent petTc =
                        store.getComponent(pet.ref, TransformComponent.getComponentType());
                Vector3d petPos = petTc == null || petTc.getPosition() == null
                        ? null : new Vector3d(petTc.getPosition());
                if (petPos != null && petPos.distance(ownerPos) > CATCHUP_DISTANCE) {
                    // Too far behind to ever catch up; bring it to heel by respawn.
                    store.removeEntity(pet.ref, RemoveReason.REMOVE);
                    if (pet.ref.isValid()) {
                        log.warn("Pet catch-up removal did not take for " + entry.getKey()
                                + " — the despawn timer will collect it");
                    }
                    var npcPlugin = NPCPlugin.get();
                    var pair = npcPlugin == null ? null : npcPlugin.spawnNPC(store, pet.role,
                            null, new Vector3d(ownerPos).add(1.5, 0, 1.5),
                            new Rotation3f(0, 0, 0));
                    if (pair == null || pair.left() == null) {
                        pets.remove(entry.getKey());
                        continue;
                    }
                    pet = new Pet(pet.role, pair.left());
                    pets.put(entry.getKey(), pet);
                }
                stamp(store, pet.ref, ownerPos);
            }

            // Auto-spawn pass: anyone here whose identity names a pet but has none live gets
            // it back — this IS the login re-spawn, the world-change re-spawn, and the
            // killed-pet recovery, all one mechanism. Deliberately does NOT re-check
            // ownership: a stored choice keeps working, the same grandfathering rule as the
            // skins. Entitlement IS checked — a lapsed supporter's pet stays stored but stops
            // spawning, like every perk.
            for (Map.Entry<UUID, Vector3d> player : playersHere.entrySet()) {
                if (pets.containsKey(player.getKey())) {
                    continue;
                }
                SupporterIdentity identity = service.identity(player.getKey());
                if (!identity.hasPet() || !service.isSupporter(player.getKey())) {
                    continue;
                }
                String role = catalogue.get(identity.pet());
                if (role == null) {
                    continue; // name left the catalogue; ignored, not an error
                }
                if (pets.size() >= cap.getAsInt()) {
                    break; // at the server-wide cap; try again next tick
                }
                spawn(store, player.getKey(), player.getValue(), role);
            }
        } catch (Throwable t) {
            warnOccasionally("Pet tickWorld failed", t);
        }
    }

    /**
     * Removes every {@code SupporterPet_} NPC in this world that the plugin is not tracking.
     *
     * <p>This is the backstop the UUID bookkeeping could never be: it does not care how a pet
     * came to exist or whether we ever knew about it — it walks the world's entities, finds
     * anything wearing one of our roles, and removes what nothing owns. That covers pets left
     * by a previous server life, by a failed removal, or by a bug not yet written.
     *
     * <p>Tracked pets are skipped by reference, so this is safe to run at any moment: a live
     * pet belonging to an online player is never touched.
     *
     * <p>World thread only — it reads components and removes entities.
     *
     * @return how many were removed
     */
    public int sweepStrays(World world, Store<EntityStore> store) {
        java.util.List<Ref<EntityStore>> doomed = new java.util.ArrayList<>();
        java.util.Set<Ref<EntityStore>> tracked = new java.util.HashSet<>();
        for (Pet pet : pets.values()) {
            tracked.add(pet.ref);
        }
        try {
            store.forEachChunk((chunk, commands) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                    if (npc == null) {
                        continue;
                    }
                    String role = npc.getRoleName();
                    if (role == null || !role.startsWith(ROLE_PREFIX)) {
                        continue;
                    }
                    Ref<EntityStore> ref = chunk.getReferenceTo(i);
                    if (ref != null && !tracked.contains(ref)) {
                        doomed.add(ref);
                    }
                }
            });
        } catch (Throwable t) {
            warnOccasionally("Pet sweep scan failed in " + world.getName(), t);
            return 0;
        }
        // Removal happens after the scan rather than inside it: mutating the store while
        // walking its own chunks is how you get a concurrent-modification fault in an ECS.
        int removed = 0;
        for (Ref<EntityStore> ref : doomed) {
            try {
                if (ref.isValid()) {
                    store.removeEntity(ref, RemoveReason.REMOVE);
                    removed++;
                }
            } catch (Throwable t) {
                warnOccasionally("Pet sweep removal failed", t);
            }
        }
        return removed;
    }

    /** Writes the owner's position into the follow slot, and re-arms the despawn timer. */
    private void stamp(Store<EntityStore> store, Ref<EntityStore> ref, Vector3d ownerPos) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return;
        }
        npc.getRole().getMarkedEntitySupport().getStoredPosition(0)
                .set(ownerPos.x, ownerPos.y, ownerPos.z);
        java.time.Instant deadline =
                java.time.Instant.now().plusSeconds(DESPAWN_TTL_SECONDS);
        DespawnComponent despawn =
                store.getComponent(ref, DespawnComponent.getComponentType());
        if (despawn != null) {
            despawn.setDespawn(deadline);
        } else {
            store.putComponent(ref, DespawnComponent.getComponentType(),
                    new DespawnComponent(deadline));
        }
    }

    /**
     * Shutdown: remove every pet SYNCHRONOUSLY. A {@code world.execute} here would silently
     * never run — the world thread is already winding down — and every restart would then
     * abandon the live pets in the world permanently. Called after the tick task is cancelled,
     * which is what makes the direct store access safe.
     */
    public void removeAllSync() {
        int removed = 0;
        for (Map.Entry<UUID, Pet> entry : pets.entrySet()) {
            try {
                Ref<EntityStore> ref = entry.getValue().ref;
                if (ref.isValid() && ref.getStore() != null) {
                    ref.getStore().removeEntity(ref, RemoveReason.REMOVE);
                    removed++;
                }
            } catch (Throwable t) {
                log.warn("Pet shutdown removal failed for " + entry.getKey() + ": " + t);
            }
        }
        pets.clear();
        // This line's ABSENCE from a shutdown log is the leak detector.
        log.info("Pet shutdown: removed " + removed + " pet(s)");
    }

    private void warnOccasionally(String message, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < 60_000L) {
            return;
        }
        lastWarnAt = now;
        log.error(message, t);
    }

    private record Pet(String role, Ref<EntityStore> ref) {}
}
