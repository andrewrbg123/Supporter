package com.peoplesserver.supportermod.platform.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.peoplesserver.supportermod.core.SupporterIdentity;
import com.peoplesserver.supportermod.core.SupporterService;
import com.peoplesserver.supportermod.platform.Messenger;
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
     * The restart duplicate, and why it took three attempts to kill.
     *
     * <p>A pet that outlives the server is still standing in the world when it comes back,
     * while the tracking map starts empty — so the auto-spawn gives the owner a second one.
     * Two separate things allow it, and both are confirmed from live logs:
     *
     * <ul>
     *   <li><b>Shutdown removes nothing.</b> "Pet shutdown: removed 0 pet(s)" was logged with a
     *       pet plainly live in the world, so by the time a plugin stops, its entity refs are
     *       already dead or the stores are gone. Synchronous removal was not enough.
     *   <li><b>A boot sweep is too early.</b> Entities in UNLOADED CHUNKS do not exist in the
     *       ECS, and seconds after startup — before anyone logs in — the leftover's chunk is
     *       not loaded. The sweep dutifully found nothing.
     * </ul>
     *
     * <p>So the sweep runs where the entity is actually visible: immediately before spawning a
     * pet for a player, which is exactly when their chunks are loaded, plus a slow periodic
     * pass for anything that loads later. Sweeping cannot rely on us knowing a pet exists, and
     * it cannot run before the world does.
     */
    private static final long SWEEP_INTERVAL_MS = 60_000;

    /** World name → when it was last swept. */
    private final Map<String, Long> lastSweepAt = new ConcurrentHashMap<>();

    private final SupporterService service;
    /** Pet name → role name; the command layer's catalogue, shared not copied. */
    private final Map<String, String> catalogue;
    /** Server-wide live-pet cap, read from config each time so a config edit applies live. */
    private final IntSupplier cap;
    private final Messenger messenger;
    private final PluginLog log;
    private volatile long lastWarnAt;

    public PetSystem(SupporterService service, Map<String, String> catalogue, IntSupplier cap,
                     Messenger messenger, PluginLog log) {
        this.service = service;
        this.catalogue = catalogue;
        this.cap = cap;
        this.messenger = messenger;
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

    /**
     * Puts the owner's pet into a mode, or cycles to the next one when {@code wanted} is null.
     *
     * <p>World thread only — it reads the pet's position and may play an animation.
     *
     * @return the resulting mode, or null if the player has no live pet here
     */
    public PetMode setMode(UUID ownerUuid, PetMode wanted, Store<EntityStore> store) {
        Pet pet = pets.get(ownerUuid);
        if (pet == null || !pet.ref.isValid()) {
            return null;
        }
        PetMode mode = wanted != null ? wanted : pet.mode.next();
        pet.mode = mode;
        if (mode == PetMode.FOLLOW) {
            pet.holdAt = null;
        } else {
            // Hold exactly where it is standing now, not where the owner is — "stay" means
            // here, and a pet that trotted to the owner first would be obeying the wrong word.
            TransformComponent tc =
                    store.getComponent(pet.ref, TransformComponent.getComponentType());
            if (tc != null && tc.getPosition() != null) {
                pet.holdAt = new Vector3d(tc.getPosition());
            }
        }
        // Always set the pose, including the null that clears it: the engine dedupes a
        // repeat, so a redundant clear costs nothing, and skipping the clear on stand-up
        // would leave the slot dirty and make the next sit a no-op.
        setPose(pet, mode == PetMode.SIT ? "Laydown" : null, store);
        return mode;
    }

    /** The mode the owner's pet is in, or null if they have none out. */
    public PetMode modeOf(UUID ownerUuid) {
        Pet pet = pets.get(ownerUuid);
        return pet == null ? null : pet.mode;
    }

    /**
     * Sets or clears the pet's pose animation. Pass {@code "Laydown"} to sit, null to stand.
     *
     * <p><b>The EMOTE slot, not Movement.</b> 0.30.0 used Movement and the pose lasted a moment
     * before the pet stood back up. Movement is the slot the LOCOMOTION system owns, so the
     * motion controller overwrites anything put there the next time the pet's motion state
     * changes. Nothing else writes Emote, so the pose survives there.
     *
     * <p><b>Why there is no periodic re-assert, and why the clear is mandatory.</b> Reading
     * {@code NPCEntity.playAnimation} off the server jar settles both. It writes the animation
     * into {@code ActiveAnimationComponent}, which is a REPLICATED component — so the pose is
     * server state, not a one-off packet, and a player who walks away and comes back, or logs
     * in later, sees the pet still lying down without us resending anything. It also returns
     * early when the slot already holds the name being set. That makes a repeat call free, but
     * it also means a slot left dirty can never be re-set: without clearing on stand-up, the
     * SECOND sit of a follow → sit → follow → sit cycle would silently do nothing. Passing null
     * skips the animation-name validation and sends the stop, keeping component and client in
     * step.
     *
     * <p>A species whose model lacks {@code Laydown} logs the engine's own rate-limited
     * "Missing animation" warning and simply does not pose, which is why nothing is reported
     * from here.
     */
    private void setPose(Pet pet, String animation, Store<EntityStore> store) {
        try {
            NPCEntity npc = store.getComponent(pet.ref, NPCEntity.getComponentType());
            if (npc != null) {
                npc.playAnimation(pet.ref, AnimationSlot.Emote, animation, store);
            }
        } catch (Throwable t) {
            warnOccasionally("Pet pose animation failed", t);
        }
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

    /**
     * Right-click your own pet to cycle follow → stay → sit.
     *
     * <p>Registered on {@code PlayerInteractEvent}. Three deliberate restrictions: only
     * SECONDARY-style interactions, so hitting a pet is still hitting it and not a command;
     * only the interacting player's OWN pet, so nobody can park somebody else's companion; and
     * the event is never cancelled, because this reads an interaction rather than replacing
     * one — anything else the click would have done still happens.
     */
    public void onPlayerInteract(PlayerInteractEvent event) {
        try {
            if (event.isCancelled()) {
                return;
            }
            InteractionType type = event.getActionType();
            if (type != InteractionType.Secondary && type != InteractionType.Use) {
                return;
            }
            Ref<EntityStore> target = event.getTargetRef();
            if (target == null || !target.isValid()) {
                return;
            }
            Ref<EntityStore> playerRef = event.getPlayerRef();
            Store<EntityStore> store = playerRef == null ? null : playerRef.getStore();
            if (store == null) {
                return;
            }
            UUIDComponent uc = store.getComponent(playerRef, UUIDComponent.getComponentType());
            UUID uuid = uc == null ? null : uc.getUuid();
            if (uuid == null) {
                return;
            }
            Pet pet = pets.get(uuid);
            if (pet == null || !pet.ref.equals(target)) {
                return; // not their pet, or not a pet at all
            }
            PetMode mode = setMode(uuid, null, store);
            if (mode != null) {
                messenger.send(uuid, "Your pet is now " + mode.describe() + ".");
            }
        } catch (Throwable t) {
            // An interaction handler must never cost somebody their click.
            warnOccasionally("Pet interaction failed", t);
        }
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
            // A periodic sweep, not just one at boot. The boot-only version missed the very
            // case it was written for: it runs seconds after startup, before anybody has
            // logged in, so the leftover pet's chunk is not loaded and the ECS cannot see it.
            // Entities in unloaded chunks are invisible to a sweep — the only reliable moment
            // is once the owner is actually there, which is what the pre-spawn sweep below
            // covers, with this as the slow backstop for strays that load later.
            long now = System.currentTimeMillis();
            Long last = lastSweepAt.get(world.getName());
            if (last == null || now - last >= SWEEP_INTERVAL_MS) {
                lastSweepAt.put(world.getName(), now);
                int strays = sweepStrays(world, store);
                if (strays > 0) {
                    log.info("Pet sweep removed " + strays + " stray pet(s) in "
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

                // A pet told to stay or sit holds its own spot instead of chasing: the follow
                // target becomes where it was left, so the engine's A* keeps it there rather
                // than the plugin having to fight the role's own seek behaviour.
                if (pet.mode != PetMode.FOLLOW) {
                    if (pet.holdAt != null) {
                        stamp(store, pet.ref, pet.holdAt);
                    }
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
                    Pet replacement = new Pet(pet.role, pair.left());
                    replacement.mode = pet.mode;
                    pet = replacement;
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
            Map<UUID, String> toSpawn = new HashMap<>();
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
                toSpawn.put(player.getKey(), role);
            }

            // THE SWEEP THAT ACTUALLY CATCHES THE RESTART DUPLICATE. A player who needs a pet
            // has just arrived, which means their chunks have just loaded, which means a pet
            // left there by a previous life of the server is finally visible to the ECS —
            // this instant, and not a moment before it. Sweeping here, immediately before
            // spawning, is what stops the leftover and the newcomer standing side by side.
            if (!toSpawn.isEmpty()) {
                int strays = sweepStrays(world, store);
                if (strays > 0) {
                    log.info("Pet pre-spawn sweep removed " + strays + " leftover pet(s) in "
                            + world.getName());
                }
            }

            for (Map.Entry<UUID, String> entry : toSpawn.entrySet()) {
                if (pets.size() >= cap.getAsInt()) {
                    break; // at the server-wide cap; try again next tick
                }
                Vector3d at = playersHere.get(entry.getKey());
                if (at != null) {
                    spawn(store, entry.getKey(), at, entry.getValue());
                }
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

    /**
     * Writes the owner's position into the follow slot, and re-arms the despawn timer.
     *
     * <p>Server 0.6 moved the marked-entity state off {@code Role} and made it a component in
     * its own right, reached by its own static getter. That is the better shape — it is
     * per-entity state that used to hang off the role object — and it drops a hop, since the
     * NPC and its role no longer have to be resolved just to reach it. {@code get} is a plain
     * component lookup guarded only by an assertion, which is disabled in production, so the
     * null check below is ours to keep.
     */
    private void stamp(Store<EntityStore> store, Ref<EntityStore> ref, Vector3d ownerPos) {
        MarkedEntitySupport marks = MarkedEntitySupport.get(ref, store);
        if (marks == null) {
            return;
        }
        marks.getStoredPosition(0).set(ownerPos.x, ownerPos.y, ownerPos.z);
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
        int tracked = pets.size();
        int removed = 0;
        int unreachable = 0;
        for (Map.Entry<UUID, Pet> entry : pets.entrySet()) {
            try {
                Ref<EntityStore> ref = entry.getValue().ref;
                if (ref.isValid() && ref.getStore() != null) {
                    ref.getStore().removeEntity(ref, RemoveReason.REMOVE);
                    removed++;
                } else {
                    unreachable++;
                }
            } catch (Throwable t) {
                unreachable++;
                log.warn("Pet shutdown removal failed for " + entry.getKey() + ": " + t);
            }
        }
        pets.clear();
        // Report all three numbers, because "removed 0" was ambiguous exactly when it mattered:
        // it appeared with a pet plainly live in the world, and could not distinguish "nothing
        // was tracked" from "everything tracked was already unreachable". Any pet counted as
        // unreachable here is one the next session's sweep will have to collect.
        log.info("Pet shutdown: tracked " + tracked + ", removed " + removed
                + ", unreachable " + unreachable
                + (unreachable > 0 ? " (the next session sweeps those)" : ""));
    }

    private void warnOccasionally(String message, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < 60_000L) {
            return;
        }
        lastWarnAt = now;
        log.error(message, t);
    }

    /**
     * What a pet is doing. Session-only and deliberately so: a pet is removed when its owner
     * leaves the world, so there is nothing to restore at login and a returning player always
     * gets their companion back at heel rather than parked wherever they left it a week ago.
     */
    public enum PetMode {
        /** Walks after the owner. The default, and what every pet does when it spawns. */
        FOLLOW,
        /** Holds position where it was told to stay. */
        STAY,
        /** Holds position and lies down. */
        SIT;

        public PetMode next() {
            return switch (this) {
                case FOLLOW -> STAY;
                case STAY -> SIT;
                case SIT -> FOLLOW;
            };
        }

        public String describe() {
            return switch (this) {
                case FOLLOW -> "following you";
                case STAY -> "staying here";
                case SIT -> "sitting";
            };
        }
    }

    /**
     * Mutable rather than a record: the mode and the spot a pet was told to hold change over
     * its life, and the tick rewrites them in place every second.
     */
    private static final class Pet {
        final String role;
        Ref<EntityStore> ref;
        PetMode mode = PetMode.FOLLOW;
        /** Where a STAY or SIT pet is holding. Null while following. */
        Vector3d holdAt;

        Pet(String role, Ref<EntityStore> ref) {
            this.role = role;
            this.ref = ref;
        }
    }
}
