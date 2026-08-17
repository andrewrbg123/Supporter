package com.peoplesserver.supportermod;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.peoplesserver.supportermod.command.SupporterCommand;
import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.core.ReconcileJob;
import com.peoplesserver.supportermod.core.SupporterService;
import com.peoplesserver.supportermod.platform.PermissionSync;
import com.peoplesserver.supportermod.platform.PluginLog;
import com.peoplesserver.supportermod.platform.Scheduler;
import com.peoplesserver.supportermod.platform.hytale.ExecutorScheduler;
import com.peoplesserver.supportermod.platform.hytale.HytaleLog;
import com.peoplesserver.supportermod.platform.hytale.HytaleMessenger;
import com.peoplesserver.supportermod.platform.hytale.HytalePlayerDirectory;
import com.peoplesserver.supportermod.platform.hytale.LuckPermsSync;
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
    private SupporterConfig config;
    private SupporterStorage storage;
    private SupporterService service;
    private HytaleMessenger messenger;
    private HytalePlayerDirectory directory;
    private ExecutorScheduler scheduler;
    private Scheduler.Task reconcileTask;
    private Scheduler.Task trailTask;

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

            this.config = SupporterConfig.load(dataDir.resolve("supporter.json"));
            this.storage = SupporterStorage.open(dataDir.resolve(config.databaseFile()));

            Color tagColor = tagColor(config);
            this.messenger = new HytaleMessenger(tagColor);
            this.directory = new HytalePlayerDirectory();
            // LuckPerms is optional: absent, this degrades to a no-op and every other perk
            // still works, because they are gated on SupporterService rather than permissions.
            PermissionSync permissions = LuckPermsSync.available()
                    ? new LuckPermsSync(config.luckPermsNodes(), log)
                    : PermissionSync.noop();
            if (!config.luckPermsNodes().isEmpty()) {
                log.info(LuckPermsSync.available()
                        ? "LuckPerms found — syncing " + config.luckPermsNodes().size() + " node(s)"
                        : "LuckPerms NOT found — permission-gated perks (homes) will not apply");
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

            // Phase 4. Runs continuously rather than on demand, so the guards inside
            // TrailSystem are what keep it affordable — see that class.
            TrailSystem trails = new TrailSystem(service, config, log);
            long trailMs = Math.max(50L, config.trailIntervalTicks() * 50L);
            this.trailTask = scheduler.scheduleRepeating(trails::tick, trailMs, trailMs);

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

    private void closeQuietly() {
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
