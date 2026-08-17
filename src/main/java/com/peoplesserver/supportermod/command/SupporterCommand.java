package com.peoplesserver.supportermod.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.Argument;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgumentType;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.peoplesserver.supportermod.SupporterPlugin;
import com.peoplesserver.supportermod.core.GrantResult;
import com.peoplesserver.supportermod.core.SupporterIdentity;
import com.peoplesserver.supportermod.core.ReconcileReport;
import com.peoplesserver.supportermod.core.SupporterRecord;
import com.peoplesserver.supportermod.core.SupporterService;
import java.awt.Color;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /supporter} — status for players, grant/revoke/reconcile for admins.
 *
 * <p><b>Admin subcommands extend {@link CommandBase}, not {@link AbstractPlayerCommand}.</b>
 * This is the correction from HANDOFF §4.1 and it is load-bearing rather than stylistic:
 * {@code AbstractPlayerCommand.execute} takes a {@code PlayerRef} and a {@code World}, so the
 * framework will not dispatch it without a player. Tebex delivers purchases by running commands
 * from the console, so a player-only {@code /supporter grant} would fail on every real purchase
 * while working perfectly in testing.
 *
 * <p><b>No optional positional arguments anywhere.</b> {@code withOptionalArg} registers a
 * <em>named flag</em> — usage renders as {@code [--name=?]} — and a bare positional word is then
 * rejected as an unexpected required parameter. FactionMod's changelog records this bug twice.
 * Where an argument is genuinely optional, use {@code addUsageVariant}, which dispatches on
 * required-argument count.
 *
 * <p>{@code CommandBase.executeSync} runs on the async ForkJoin pool, so nothing here may touch
 * {@code Store.getComponent}. Every lookup goes through {@code Universe} or the plugin's own
 * SQLite, both of which are safe from any thread.
 */
public final class SupporterCommand extends AbstractPlayerCommand {

    static final Color OK = new Color(0x7FD962);
    static final Color ERR = new Color(0xE05B5B);
    static final Color INFO = new Color(0xBFC7D5);

    private final SupporterPlugin plugin;

    public SupporterCommand(SupporterPlugin plugin) {
        super("supporter", "Supporter rank: your status, and admin grant/revoke.");
        setPermissionGroup(GameMode.Adventure);
        this.plugin = plugin;
        addSubCommand(new StatusSub(plugin));
        addSubCommand(new ListSub(plugin));
        addSubCommand(new TitleSub(plugin));
        addSubCommand(new ColorSub(plugin));
        addSubCommand(new GrantSub(plugin));
        addSubCommand(new RevokeSub(plugin));
        addSubCommand(new ReconcileSub(plugin));
    }

    @Override
    protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                           PlayerRef player, World world) {
        StatusSub.render(plugin, ctx, player.getUuid());
    }

    // --- shared helpers ---------------------------------------------------------------------

    /**
     * Returns the live service, or null after telling the caller why it is unavailable.
     *
     * <p>Startup failure must never present as "you are not a supporter" — that is
     * indistinguishable from a genuine answer and would send admins hunting the wrong problem.
     */
    static SupporterService service(SupporterPlugin plugin, CommandContext ctx) {
        SupporterService service = plugin.service();
        if (service == null) {
            String why = plugin.startupError();
            ctx.sendMessage(Message.raw("[Supporter] Unavailable — the plugin failed to start"
                    + (why == null ? "." : ": " + why)).color(ERR));
            return null;
        }
        return service;
    }

    static void ok(CommandContext ctx, String text) {
        ctx.sendMessage(Message.raw("[Supporter] " + text).color(OK));
    }

    static void err(CommandContext ctx, String text) {
        ctx.sendMessage(Message.raw("[Supporter] " + text).color(ERR));
    }

    static void info(CommandContext ctx, String text) {
        ctx.sendMessage(Message.raw(text).color(INFO));
    }

    // --- /supporter status ------------------------------------------------------------------

    public static final class StatusSub extends AbstractPlayerCommand {
        private final SupporterPlugin plugin;

        public StatusSub(SupporterPlugin plugin) {
            super("status", "Show your supporter status.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            render(plugin, ctx, player.getUuid());
        }

        static void render(SupporterPlugin plugin, CommandContext ctx, UUID uuid) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            Optional<SupporterRecord> record = service.get(uuid);
            if (record.isEmpty()) {
                info(ctx, "You are not currently a supporter.");
                return;
            }
            ok(ctx, "Status: " + service.status(uuid)
                    + " — " + service.daysRemaining(uuid) + " day(s) remaining, "
                    + record.get().totalDays() + " day(s) total.");
        }
    }

    // --- /supporter list --------------------------------------------------------------------

    public static final class ListSub extends AbstractPlayerCommand {
        private static final int LIMIT = 15;

        private final SupporterPlugin plugin;

        public ListSub(SupporterPlugin plugin) {
            super("list", "List current supporters, longest-serving first.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            List<SupporterRecord> all = service.activeByTenure();
            if (all.isEmpty()) {
                info(ctx, "No active supporters.");
                return;
            }
            ok(ctx, "Supporters (" + all.size() + "):");
            int shown = Math.min(LIMIT, all.size());
            for (int i = 0; i < shown; i++) {
                SupporterRecord r = all.get(i);
                info(ctx, "  " + (i + 1) + ". " + r.username() + " — " + r.totalDays() + "d");
            }
            if (all.size() > shown) {
                info(ctx, "  ... and " + (all.size() - shown) + " more.");
            }
        }
    }

    // --- /supporter title [text] --------------------------------------------------------------

    /**
     * {@code /supporter title} clears; {@code /supporter title <text>} sets.
     *
     * <p>The bare form and the one-argument form are two commands, not one command with an
     * optional argument, because {@code withOptionalArg} registers a named flag
     * ({@code [--text=?]}) and rejects a bare positional word. {@code addUsageVariant}
     * dispatches on required-argument count, which is the supported way to do this.
     *
     * <p>GREEDY_STRING so a multi-word title works — but note the variant is selected by token
     * count, so this only works because the greedy argument is the command's sole parameter.
     */
    public static final class TitleSub extends AbstractPlayerCommand {
        private final SupporterPlugin plugin;

        public TitleSub(SupporterPlugin plugin) {
            super("title", "Clear your chat title. Use /supporter title <text> to set one.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            addUsageVariant(new SetTitleVariant(plugin));
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            apply(plugin, ctx, player.getUuid(), null);
        }

        static void apply(SupporterPlugin plugin, CommandContext ctx, UUID uuid, String title) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            if (!service.isSupporter(uuid)) {
                err(ctx, "Chat titles are a supporter perk. /supporter to find out more.");
                return;
            }
            try {
                SupporterIdentity updated = service.setTitle(uuid, title);
                if (updated.hasTitle()) {
                    ok(ctx, "Title set to: " + updated.title());
                } else {
                    ok(ctx, "Title cleared.");
                }
            } catch (IllegalArgumentException e) {
                err(ctx, e.getMessage());
            } catch (RuntimeException e) {
                err(ctx, "Could not save your title: " + e.getMessage());
                plugin.log().error("setTitle failed for " + uuid, e);
            }
        }
    }

    /** The one-argument form of {@code /supporter title}. */
    public static final class SetTitleVariant extends AbstractPlayerCommand {
        private final SupporterPlugin plugin;
        private final Argument textArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public SetTitleVariant(SupporterPlugin plugin) {
            super("Set your chat title.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            this.textArg = withRequiredArg("text", "your title",
                    (ArgumentType) ArgTypes.GREEDY_STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            // GREEDY_STRING keeps trailing whitespace; the service trims, but trim here too so
            // the length check sees what the player actually typed.
            TitleSub.apply(plugin, ctx, player.getUuid(),
                    String.valueOf(ctx.get(textArg)).trim());
        }
    }

    // --- /supporter colour [hex] --------------------------------------------------------------

    /** {@code /supporter colour} clears and lists the options; with an argument, sets. */
    public static final class ColorSub extends AbstractPlayerCommand {
        private final SupporterPlugin plugin;

        public ColorSub(SupporterPlugin plugin) {
            super("colour", "Clear your chat colour. Use /supporter colour <hex> to set one.");
            setPermissionGroup(GameMode.Adventure);
            addAliases(new String[] {"color"});
            this.plugin = plugin;
            addUsageVariant(new SetColorVariant(plugin));
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            apply(plugin, ctx, player.getUuid(), null);
            info(ctx, "Available: " + String.join(", ", plugin.config().allowedChatColors()));
        }

        static void apply(SupporterPlugin plugin, CommandContext ctx, UUID uuid, String color) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            if (!service.isSupporter(uuid)) {
                err(ctx, "Chat colours are a supporter perk. /supporter to find out more.");
                return;
            }
            try {
                SupporterIdentity updated = service.setChatColor(uuid, color);
                if (updated.hasColor()) {
                    ok(ctx, "Chat colour set to " + updated.chatColor() + ".");
                } else {
                    ok(ctx, "Chat colour cleared.");
                }
            } catch (IllegalArgumentException e) {
                err(ctx, e.getMessage());
            } catch (RuntimeException e) {
                err(ctx, "Could not save your colour: " + e.getMessage());
                plugin.log().error("setChatColor failed for " + uuid, e);
            }
        }
    }

    /** The one-argument form of {@code /supporter colour}. */
    public static final class SetColorVariant extends AbstractPlayerCommand {
        private final SupporterPlugin plugin;
        private final Argument hexArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public SetColorVariant(SupporterPlugin plugin) {
            super("Set your chat colour to one of the allowed values.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            this.hexArg = withRequiredArg("hex", "e.g. #55FFFF", (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            ColorSub.apply(plugin, ctx, player.getUuid(), String.valueOf(ctx.get(hexArg)).trim());
        }
    }

    // --- /supporter grant <player> <days> ----------------------------------------------------

    /** Console-capable: this is the command Tebex runs on purchase. */
    public static final class GrantSub extends CommandBase {
        private final SupporterPlugin plugin;
        private final Argument playerArg;
        private final Argument daysArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public GrantSub(SupporterPlugin plugin) {
            super("grant", "Grant supporter days: <player> <days>");
            setPermissionGroup(GameMode.Creative);
            this.plugin = plugin;
            this.playerArg = withRequiredArg("player", "username", (ArgumentType) ArgTypes.STRING);
            this.daysArg = withRequiredArg("days", "days to add", (ArgumentType) ArgTypes.INTEGER);
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            String username = String.valueOf(ctx.get(playerArg)).trim();
            int days = (Integer) ctx.get(daysArg);
            if (days <= 0) {
                err(ctx, "days must be positive, got " + days);
                return;
            }
            try {
                // No transaction id: this is a manual admin grant, so it is deliberately not
                // idempotent. Tebex-delivered grants pass their txn id and are applied once.
                GrantResult result = service.grantByUsername(username, days, "admin", null);
                switch (result.outcome()) {
                    case CREATED -> ok(ctx, "Created supporter for " + username
                            + " — " + days + " day(s).");
                    case EXTENDED -> ok(ctx, "Extended " + username + " by " + days
                            + " day(s) from their existing expiry.");
                    case QUEUED_PENDING -> ok(ctx, username + " has never logged in, so "
                            + days + " day(s) are queued and will apply on their next login.");
                    case DUPLICATE_IGNORED -> info(ctx,
                            "Already applied — ignored as a duplicate.");
                }
            } catch (RuntimeException e) {
                err(ctx, "Grant failed: " + e.getMessage());
                plugin.log().error("Grant failed for " + username, e);
            }
        }
    }

    // --- /supporter revoke <player> ----------------------------------------------------------

    public static final class RevokeSub extends CommandBase {
        private final SupporterPlugin plugin;
        private final Argument playerArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public RevokeSub(SupporterPlugin plugin) {
            super("revoke", "End somebody's supporter entitlement: <player>");
            setPermissionGroup(GameMode.Creative);
            this.plugin = plugin;
            this.playerArg = withRequiredArg("player", "username", (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            String username = String.valueOf(ctx.get(playerArg)).trim();
            Optional<SupporterRecord> record = service.findByUsername(username);
            if (record.isEmpty()) {
                err(ctx, "No supporter record for " + username + ".");
                return;
            }
            try {
                // Entitlement ends; tenure and anything already paid for are kept, per the
                // standing rule that nothing a player bought is ever deleted.
                service.revoke(record.get().uuid(), "revoked by " + ctx.sender().getUsername(),
                        ctx.sender().getUsername());
                ok(ctx, "Revoked supporter for " + username + ". Their history is retained.");
            } catch (RuntimeException e) {
                err(ctx, "Revoke failed: " + e.getMessage());
                plugin.log().error("Revoke failed for " + username, e);
            }
        }
    }

    // --- /supporter reconcile -----------------------------------------------------------------

    public static final class ReconcileSub extends CommandBase {
        private final SupporterPlugin plugin;

        public ReconcileSub(SupporterPlugin plugin) {
            super("reconcile", "Run the expiry sweep now instead of waiting for tonight.");
            setPermissionGroup(GameMode.Creative);
            this.plugin = plugin;
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            try {
                ReconcileReport report = service.reconcile();
                ok(ctx, "Reconcile complete — expired " + report.expiredCount() + " supporter(s).");
            } catch (RuntimeException e) {
                err(ctx, "Reconcile failed: " + e.getMessage());
                plugin.log().error("Manual reconcile failed", e);
            }
        }
    }
}
