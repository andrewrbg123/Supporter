package com.peoplesserver.supportermod;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerSetupConnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.peoplesserver.supportermod.command.SupporterCommand;
import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.core.ReconcileJob;
import com.peoplesserver.supportermod.core.SupporterService;
import com.peoplesserver.supportermod.platform.PermissionSync;
import com.peoplesserver.supportermod.platform.PluginLog;
import com.peoplesserver.supportermod.platform.Scheduler;
import com.peoplesserver.supportermod.ui.SupporterPanel;
import com.peoplesserver.supportermod.platform.hytale.ExecutorScheduler;
import com.peoplesserver.supportermod.platform.hytale.HytaleLog;
import com.peoplesserver.supportermod.platform.hytale.HytaleMessenger;
import com.peoplesserver.supportermod.platform.hytale.HytalePlayerDirectory;
import com.peoplesserver.supportermod.platform.hytale.LuckPermsSync;
import com.peoplesserver.supportermod.platform.hytale.ReservedSlots;
import com.peoplesserver.supportermod.platform.hytale.PetSystem;
import com.peoplesserver.supportermod.platform.hytale.QuestTracker;
import com.peoplesserver.supportermod.platform.hytale.SupporterChatTag;
import com.peoplesserver.supportermod.platform.hytale.TrailSystem;
import com.peoplesserver.supportermod.storage.SupporterStorage;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

/**
 * Plugin entry point: wires the entitlement core to the server and takes it down again.
 *
 * <p>Everything here is plumbing. No entitlement rule lives in this class — {@link
 * SupporterService} owns all of it, and this class only decides when it is constructed and what
 * it is handed.
 *
 * <p><b>Startup failure is surfaced, not swallowed.</b> The database layer can fail in ways no
 * compile check catches, and the first live deploy proved it: the shaded JDBC driver never
 * registered, because {@code DriverManager} discovers drivers via the system classloader and
 * plugins load in their own. The plugin records the reason and every command reports it, rather
 * than the server booting with a silently dead supporter system that accepts grants and loses
 * them. That design is what turned a novel failure into a ten-minute fix — the log named the
 * cause and the line number on the first try.
 *
 * <p>Shutdown is symmetric with setup and runs in reverse order. The host cancels ECS systems
 * for us, but schedulers, database handles and our own registrations are ours to release.
 */
public final class SupporterPlugin extends JavaPlugin {

    private PluginLog log;
    /** Null until HyUI is probed on first use. Never resolved during setup - see hyUiPresent(). */
    private volatile Boolean hyUiAvailable;
    private SupporterConfig config;
    private SupporterStorage storage;
    private SupporterService service;
    private HytaleMessenger messenger;
    private HytalePlayerDirectory directory;
    private ExecutorScheduler scheduler;
    private Scheduler.Task reconcileTask;
    private Scheduler.Task trailTask;
    private Scheduler.Task questTask;
    private Scheduler.Task questSampleTask;
    private Scheduler.Task petTask;
    private com.peoplesserver.supportermod.platform.hytale.PetSystem petSystem;

    /** Non-null when setup failed; reported by every command so it cannot go unnoticed. */
    private volatile String startupError;

    public SupporterPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.log = new HytaleLog(getLogger());
        try {
            // NOT getDataDirectory(). That returns a path under mods/, and the asset module
            // scans mods/ for asset packs — so our database folder gets picked up as a
            // malformed pack and logs "Skipping pack at ThePeoplesServer_SupporterMod:
            // missing or invalid manifest.json" on every single boot. FactionMod uses
            // plugins/<Name>/ for exactly this reason; mods/ is for mods.
            Path dataDir = Path.of("plugins", "SupporterMod");
            Files.createDirectories(dataDir);

            this.config = SupporterConfig.load(dataDir.resolve("supporter.json"), log::info);
            this.storage = SupporterStorage.open(dataDir.resolve(config.databaseFile()));

            // v0.18.0: costume presets. Each *.json in costumes/ is a PlayerSkin in the game's
            // own format — the shape hytalecharacter.com exports — so an admin composes an
            // outfit there, drops the file here, restarts, and it is a supporter skin. Loaded
            // as raw text now and parsed lazily on first use, because the cosmetics registry
            // does not exist yet during setup. VET PARTS BEFORE ADDING: the server has no
            // entitlement data, so nothing here can stop an edition-locked part being handed
            // out except the admin checking first.
            java.nio.file.Path costumes = dataDir.resolve("costumes");
            Files.createDirectories(costumes);
            try (var listing = Files.list(costumes)) {
                if (listing.findAny().isEmpty()) {
                    Files.writeString(costumes.resolve("jack-sparrow.json"), """
                            {
                              "bodyCharacteristic": "Default.04",
                              "underwear": "Boxer.Blue",
                              "face": "Face_Sunken",
                              "ears": "Default",
                              "mouth": "Mouth_Makeup",
                              "haircut": "FeatheredHair.BrownDark",
                              "facialHair": "PirateGoatee.BrownDark",
                              "eyebrows": "Plucked.BrownDark",
                              "eyes": "Medium_Eyes.BrownDark",
                              "pants": "PinstripeTrousers.Brown",
                              "overpants": null,
                              "undertop": "LongSleeveShirt.Grey",
                              "overtop": "TrenchCoat.Black",
                              "shoes": "Wellies.Black",
                              "headAccessory": "PirateBandana.Red",
                              "faceAccessory": null,
                              "earAccessory": "EarHoops.Brass_Purple.Both",
                              "skinFeature": null,
                              "gloves": "GoldenBracelets.Gold_Red",
                              "cape": null
                            }
                            """, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            int costumeCount = 0;
            try (var listing = Files.list(costumes)) {
                for (java.nio.file.Path file : listing.filter(
                        p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    String name = file.getFileName().toString()
                            .replaceFirst("\\.json$", "").toLowerCase();
                    try {
                        com.peoplesserver.supportermod.ui.SkinChanger.registerCostume(
                                name, Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
                        costumeCount++;
                    } catch (Exception e) {
                        // One unreadable costume must not cost the others, or the boot.
                        log.warn("Costume " + file.getFileName() + " not loaded: " + e);
                    }
                }
            }
            if (costumeCount > 0) {
                log.info(costumeCount + " costume(s) loaded from " + costumes);
            }

            Color tagColor = tagColor(config);
            this.messenger = new HytaleMessenger(tagColor);
            this.directory = new HytalePlayerDirectory();
            // LuckPerms is resolved LAZILY, on first use, never here.
            //
            // setup() runs long before other plugins are enabled — the live server logged
            // "LuckPerms NOT found" at 10:22:54 and "Enabled plugin LuckPerms:LuckPerms" at
            // 10:23:05, eleven seconds later. Probing at startup therefore caches a "no" that
            // is wrong for the entire session. First use is a grant or a login, by which point
            // every plugin is up.
            //
            // Still optional: if LuckPerms genuinely is absent, each call degrades to a logged
            // no-op and every other perk keeps working, because they are gated on
            // SupporterService rather than on permissions.
            PermissionSync permissions = config.luckPermsNodes().isEmpty()
                    ? PermissionSync.noop()
                    : new LuckPermsSync(config.luckPermsNodes(), log);
            if (!config.luckPermsNodes().isEmpty()) {
                log.info("Permission sync armed for " + config.luckPermsNodes().size()
                        + " node(s) — LuckPerms is resolved on first use");
            }

            this.service = new SupporterService(
                    storage, config, Clock.systemUTC(), directory, messenger, log, permissions);

            this.scheduler = new ExecutorScheduler(getTaskRegistry());
            this.reconcileTask =
                    ReconcileJob.start(service, config, Clock.systemUTC(), scheduler, log);

            getCommandRegistry().registerCommand(new SupporterCommand(this));
            getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);

            // Phase 2. Never cancels the event, so FactionMod's faction chat is untouched, and
            // WRAPS whatever formatter is already in place rather than replacing it.
            //
            // EventPriority.LAST is load-bearing: lucko:mini-chat-formatter also sets a
            // formatter and, at NORMAL priority, ran after us and won — which is why the tag
            // did not appear on the first live test. Running last means we wrap its output
            // instead of competing with it, so the configured rank and prefix survive.
            SupporterChatTag chatTag = new SupporterChatTag(service, tagColor);
            getEventRegistry().registerGlobal(
                    EventPriority.LAST, PlayerChatEvent.class, chatTag::onPlayerChat);

            // Phase 6. Reserved slots. Off unless reservedSlots > 0 — a perk that turns
            // players away must never switch itself on by accident.
            ReservedSlots reserved = new ReservedSlots(service, config.reservedSlots(), log);
            if (reserved.enabled()) {
                getEventRegistry().registerGlobal(
                        PlayerSetupConnectEvent.class, reserved::onSetupConnect);
                log.info("Reserved slots active: last " + config.reservedSlots()
                        + " slot(s) held for supporters");
            }

            // Phase 4. Runs continuously rather than on demand, so the guards inside
            // TrailSystem are what keep it affordable — see that class.
            TrailSystem trails = new TrailSystem(service, config, log);
            long trailMs = Math.max(50L, config.trailIntervalTicks() * 50L);
            this.trailTask = scheduler.scheduleRepeating(trails::tick, trailMs, trailMs);

            // 0.22.0: pets, spike-proven the same day it shipped. The one-second tick does
            // the following, the login/world-change re-spawn from the stored identity, and
            // the orphan cleanup; the catalogue is PetSub's map, one source of truth.
            this.petSystem = new PetSystem(service,
                    com.peoplesserver.supportermod.command.SupporterCommand.PetSub.PETS,
                    config::maxConcurrentPets, log);
            this.petTask = scheduler.scheduleRepeating(petSystem::tick, 1_000L, 1_000L);

            // 0.21.0: quests. The minute tick is EXACTLY 60s — each firing credits one minute
            // of playtime, so the interval is the unit, not a tuning knob. The 10s sampler
            // (0.21.1) only accumulates travel in memory; see QuestTracker.
            QuestTracker questTracker = new QuestTracker(service, scheduler, log);
            this.questTask = scheduler.scheduleRepeating(
                    questTracker::minuteTick, 60_000L, 60_000L);
            this.questSampleTask = scheduler.scheduleRepeating(
                    questTracker::sampleTick, 10_000L, 10_000L);
            getEventRegistry().registerGlobal(
                    PlayerChatEvent.class, questTracker::onPlayerChat);

            log.info("Ready — grace " + config.graceDays() + "d, reconcile at "
                    + config.reconcileHourUtc() + ":00 UTC, trails every " + trailMs + "ms "
                    + "(cap " + config.maxConcurrentTrails() + "/tick, "
                    + config.trailViewDistance() + " block view)");
        } catch (Throwable t) {
            // Catch Throwable, not Exception: a missing native library surfaces as
            // UnsatisfiedLinkError, which is exactly the failure mode Phase 0b flagged.
            this.startupError = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            log.error("SupporterMod failed to start — supporter perks are OFF and grants will "
                    + "NOT be recorded. Fix this before taking payments.", t);
            closeQuietly();
        }
    }

    @Override
    protected void shutdown() {
        closeQuietly();
        if (log != null) {
            log.info("Stopped");
        }
    }

    /** The pet system, or null before setup finished. */
    public com.peoplesserver.supportermod.platform.hytale.PetSystem pets() {
        return petSystem;
    }

    private void closeQuietly() {
        // Pets first, and synchronously: cancel the tick, then remove the live NPCs directly.
        // A world.execute here would silently never run (the world thread is winding down),
        // which is exactly how FactionMod leaked NPCs on every restart.
        if (petTask != null) {
            try {
                petTask.cancel();
            } catch (Throwable ignored) {
                // shutting down anyway
            }
            petTask = null;
        }
        if (petSystem != null) {
            try {
                petSystem.removeAllSync();
            } catch (Throwable ignored) {
                // shutting down anyway
            }
            petSystem = null;
        }
        if (questSampleTask != null) {
            try {
                questSampleTask.cancel();
            } catch (Throwable ignored) {
                // shutting down anyway
            }
            questSampleTask = null;
        }
        if (questTask != null) {
            try {
                questTask.cancel();
            } catch (Throwable ignored) {
                // shutting down anyway
            }
            questTask = null;
        }
        if (trailTask != null) {
            try {
                trailTask.cancel();
            } catch (Throwable ignored) {
                // shutting down anyway
            }
            trailTask = null;
        }
        if (reconcileTask != null) {
            try {
                reconcileTask.cancel();
            } catch (Throwable ignored) {
                // shutting down anyway
            }
            reconcileTask = null;
        }
        if (scheduler != null) {
            try {
                scheduler.close();
            } catch (Throwable ignored) {
                // shutting down anyway
            }
            scheduler = null;
        }
        if (storage != null) {
            try {
                storage.close();
            } catch (Throwable t) {
                if (log != null) {
                    log.error("Failed to close the supporter database cleanly", t);
                }
            }
            storage = null;
        }
        service = null;
        messenger = null;
        directory = null;
    }

    // --- events ---------------------------------------------------------------------------

    /**
     * Claims queued grants and nudges about renewal.
     *
     * <p>Getting the player's identity out of this event has two traps. {@code
     * PlayerEvent.getPlayerRef()} returns a {@code Ref<EntityStore>}, not a {@code PlayerRef};
     * and {@code Player.getPlayerRef()}, which does return one, is deprecated for removal.
     * {@code Entity.getUuid()} is neither, and {@code Player} inherits it — so we take the UUID
     * from the entity and resolve the name through the directory port.
     *
     * <p>The database work runs on the calling thread. Both queries are indexed lookups against
     * a local SQLite file, which is comparable to what FactionMod already does on join. If join
     * latency ever shows up in profiling, move this onto {@link ExecutorScheduler}'s executor
     * and message the player from the callback — the service is synchronised, so that is safe.
     */
    private void onPlayerReady(PlayerReadyEvent event) {
        SupporterService current = this.service;
        if (current == null) {
            return; // startup failed; already logged
        }
        try {
            if (event.getPlayer() == null) {
                return;
            }
            UUID uuid = event.getPlayer().getUuid();
            if (uuid == null) {
                return;
            }
            String username = directory.usernameFor(uuid).orElse(null);
            if (username == null) {
                return;
            }

            SupporterService.LoginResult result = current.onLogin(uuid, username);

            if (result.claimedDays() > 0) {
                messenger.send(uuid, "Your supporter purchase has been applied — "
                        + result.claimedDays() + " day(s) added. Thank you!");
            }
            // v0.17.0/v0.18.1: re-apply the chosen skin. Two lessons live here:
            //
            // FORGET FIRST. The restore caches survive a relog inside the same server session,
            // and a fresh login makes them stale — the account rebuild is now the truth, and
            // without this line "off" could hand back the PREVIOUS session's look.
            //
            // DELAYED, not at ready. The immediate re-apply worked for tints (a model push
            // lands whenever it arrives) but not costumes: the login sequence is still settling
            // at ready and overwrote the skin swap — the player arrived as themselves. Two
            // seconds later the composition is stable. The scheduler thread only resolves the
            // world before handing the component work to world.execute — the trail system's
            // split.
            // 0.21.2: this hook no longer wipes the restore caches. Wiping them here was the
            // 0.18.1 answer to stale originals, but one connection can fire PlayerReady TWICE
            // with no account rebuild between (seen live, 37s apart) — the wipe destroyed the
            // good capture and the re-apply then captured the COSTUME as the "original".
            // SkinChanger now refreshes the original only when the live look is verifiably
            // the player's own, which covers staleness and the double-ready both.
            try {
                com.peoplesserver.supportermod.core.SupporterIdentity identity =
                        current.identity(uuid);
                if (identity.hasSkin() && current.isSupporter(uuid)) {
                    String storedSkin = identity.skin();
                    var playerEntity = event.getPlayerRef();
                    scheduler.runOnce(() -> {
                        try {
                            var universe = com.hypixel.hytale.server.core.universe.Universe.get();
                            if (universe == null || playerEntity == null) {
                                return;
                            }
                            for (var world : universe.getWorlds().values()) {
                                if (world.getEntityStore().getStore() == playerEntity.getStore()) {
                                    world.execute(() -> {
                                        var applied = com.peoplesserver.supportermod.ui.SkinChanger
                                                .applyByName(playerEntity.getStore(),
                                                        playerEntity, uuid, storedSkin);
                                        if (applied.applied()) {
                                            log.info("Re-applied skin '" + storedSkin
                                                    + "' for " + username);
                                        } else {
                                            log.warn("Stored skin '" + storedSkin
                                                    + "' not re-applied for " + username
                                                    + ": " + applied.detail());
                                        }
                                    });
                                    return;
                                }
                            }
                        } catch (Throwable t) {
                            log.warn("Skin re-apply failed for " + username + ": " + t);
                        }
                    }, 2000);
                }
            } catch (Throwable t) {
                // A cosmetic must never break a login.
                log.warn("Skin re-apply scheduling failed for " + username + ": " + t);
            }

            switch (result.nudge()) {
                case RENEWAL_SOON -> messenger.send(uuid,
                        "Your supporter rank expires in " + current.daysRemaining(uuid)
                                + " day(s). /supporter to renew.");
                case GRACE -> messenger.send(uuid,
                        "Your supporter rank has lapsed and is in its grace period. "
                                + "Renew to keep your perks.");
                case NONE -> { }
            }
        } catch (RuntimeException e) {
            // A failed login hook must never stop a player joining.
            log.error("onLogin failed", e);
        }
    }

    // --- UI -----------------------------------------------------------------------------------

    /**
     * Opens the supporter panel for a player.
     *
     * @return false if HyUI is absent or the panel could not be built, meaning the caller should
     *     fall back to chat
     */
    public boolean openPanel(PlayerRef player, Store<EntityStore> store, World world) {
        if (!hyUiPresent()) {
            return false;
        }
        // 0.25.0: the redesign is what /supporter opens. The original panel stays as the
        // FALLBACK rather than being deleted — it is proven, it is already written, and a
        // player whose panel fails to build should lose the new look, not the feature. Three
        // rungs: redesign, original panel, chat.
        try {
            if (new com.peoplesserver.supportermod.ui.FlatPanel(this)
                    .open(player, store, world)) {
                return true;
            }
            log.warn("Redesigned panel did not open; falling back to the original panel");
        } catch (Throwable t) {
            log.warn("Redesigned panel unavailable, falling back to the original: " + t);
        }
        try {
            return new SupporterPanel(this).open(player, store, world);
        } catch (Throwable t) {
            log.warn("Supporter panel unavailable: " + t);
            return false;
        }
    }

    /**
     * Opens the ORIGINAL panel, kept reachable for comparison now that {@code /supporter}
     * opens the redesign.
     */
    public boolean openLegacyPanel(PlayerRef player, Store<EntityStore> store, World world) {
        if (!hyUiPresent()) {
            return false;
        }
        try {
            return new SupporterPanel(this).open(player, store, world);
        } catch (Throwable t) {
            log.warn("Legacy panel unavailable: " + t);
            return false;
        }
    }

    /**
     * Whether HyUI's classes are loadable, resolved once on FIRST USE and never during setup.
     *
     * <p>Probing at startup is how the LuckPerms integration silently did nothing for a whole
     * session in 0.6.1: {@code setup()} runs before other plugins are enabled, so the answer
     * cached there was "no" and stayed wrong until a restart. The same trap applies to any
     * optional dependency, so this deliberately answers late.
     *
     * <p>Also why {@code Ellie:HyUI} is declared in OptionalDependencies: without it this
     * plugin's classloader cannot see HyUI's classes at all, which is the same class of failure
     * as the JDBC driver in 0.2.1.
     */
    private boolean hyUiPresent() {
        Boolean cached = hyUiAvailable;
        if (cached != null) {
            return cached;
        }
        boolean present;
        try {
            Class.forName("au.ellie.hyui.builders.PageBuilder", false, getClass().getClassLoader());
            present = true;
        } catch (Throwable t) {
            present = false;
            log.info("HyUI not present — /supporter will use chat output.");
        }
        hyUiAvailable = present;
        return present;
    }

    // --- accessors for the command tree ------------------------------------------------------

    /** Null when startup failed. */
    public SupporterService service() {
        return service;
    }

    public SupporterConfig config() {
        return config;
    }

    public PluginLog log() {
        return log;
    }

    /** Null unless setup failed, in which case it is the reason. */
    public String startupError() {
        return startupError;
    }

    private static Color tagColor(SupporterConfig config) {
        try {
            return Color.decode(config.tagColorHex());
        } catch (RuntimeException e) {
            return Color.WHITE;
        }
    }
}
