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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
        // Real players type the short form. Observed during non-OP testing: the first thing
        // tried was "/sup", which returned "Command not found".
        addAliases(new String[] {"sup", "supporters"});
        this.plugin = plugin;
        addSubCommand(new InfoSub(plugin));
        addSubCommand(new StatusSub(plugin));
        addSubCommand(new ListSub(plugin));
        addSubCommand(new TitleSub(plugin));
        addSubCommand(new ColorSub(plugin));
        addSubCommand(new TrailSub(plugin));
        addSubCommand(new TrailsVisibilitySub(plugin));
        addSubCommand(new PerksSub(plugin));
        addSubCommand(new CapeSub(plugin));
        addSubCommand(new HatSub(plugin));
        addSubCommand(new ShoesSub(plugin));
        addSubCommand(new SkinSub(plugin));
        addSubCommand(new TokensSub(plugin));
        addSubCommand(new ShopSub(plugin));
        addSubCommand(new BuySub(plugin));
        addSubCommand(new QuestsSub(plugin));
        addSubCommand(new PetSub(plugin));
        addSubCommand(new PettestSub(plugin));
        addSubCommand(new UitestSub(plugin));
        addSubCommand(new ChargebackSub(plugin));
        addSubCommand(new GrantSub(plugin));
        addSubCommand(new RevokeSub(plugin));
        addSubCommand(new ReconcileSub(plugin));
    }

    /**
     * The root is public. It is the entry point every player has to be able to see — the admin
     * subcommands underneath keep their own permissions and are filtered individually.
     */
    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    /**
     * Opens the panel, or prints the chat status if it cannot be opened.
     *
     * <p>The fallback is the point. HyUI is optional, panels can fail to build, and a player
     * typing {@code /supporter} must always get an answer — so a UI failure costs them the window,
     * never the information.
     */
    @Override
    protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                           PlayerRef player, World world) {
        if (plugin.openPanel(player, store, world)) {
            return;
        }
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

    /**
     * A player-facing command that is deliberately not permission-gated.
     *
     * <p>Discovery and execution are two different checks, and having them disagree is the worst
     * of both worlds. {@code CommandTreeBuilder} filters the client's tab-completion list through
     * {@code AbstractCommand.hasPermission}, whose first line is:
     *
     * <pre>
     * String perm = getPermission();
     * if (perm == null) return true;                  // no permission =&gt; always visible
     * if (!sender.hasPermission(perm)) return false;
     * </pre>
     *
     * <p>{@code setPermissionGroup(...)} causes a permission to be generated, so these commands
     * were hidden from every non-OP player's suggestions — while still being perfectly runnable
     * if typed in full. Players could use the shop and never discover it existed. That is why
     * EliteEssentials' commands appear for everyone and ours did not.
     *
     * <p>So the player-facing half declares no permission: visible to all, runnable by all,
     * which is what they already were in practice. The four admin subcommands — grant, revoke,
     * reconcile, chargeback — keep theirs, because those genuinely must be restricted.
     */
    private abstract static class PublicPlayerCommand extends AbstractPlayerCommand {
        PublicPlayerCommand(String name, String description) {
            super(name, description);
        }

        PublicPlayerCommand(String description) {
            super(description);
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }
    }

    // --- /supporter status ------------------------------------------------------------------

    public static final class StatusSub extends PublicPlayerCommand {
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

    public static final class ListSub extends PublicPlayerCommand {
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
    public static final class TitleSub extends PublicPlayerCommand {
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
                err(ctx, "Chat titles are a supporter perk. /supporter info to find out more.");
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
    public static final class SetTitleVariant extends PublicPlayerCommand {
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
    public static final class ColorSub extends PublicPlayerCommand {
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
                err(ctx, "Chat colours are a supporter perk. /supporter info to find out more.");
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
    public static final class SetColorVariant extends PublicPlayerCommand {
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

    // --- /supporter trail [id] ----------------------------------------------------------------

    /** {@code /supporter trail} clears and lists; with an id, sets. */
    public static final class TrailSub extends PublicPlayerCommand {
        private final SupporterPlugin plugin;

        public TrailSub(SupporterPlugin plugin) {
            super("trail", "Clear your trail. Use /supporter trail <name> to pick one.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            addUsageVariant(new SetTrailVariant(plugin));
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            apply(plugin, ctx, player.getUuid(), null);
            info(ctx, "Available: " + String.join(", ", plugin.config().trails().keySet()));
        }

        static void apply(SupporterPlugin plugin, CommandContext ctx, UUID uuid, String trail) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            if (!service.isSupporter(uuid)) {
                err(ctx, "Trails are a supporter perk. /supporter info to find out more.");
                return;
            }
            try {
                // selectTrail, not setTrail: it refuses a trail the player has not unlocked.
                SupporterIdentity updated = service.selectTrail(uuid, trail);
                if (updated.hasTrail()) {
                    ok(ctx, "Trail set to " + updated.trail() + ". Walk around to see it.");
                } else {
                    ok(ctx, "Trail cleared.");
                }
            } catch (IllegalArgumentException e) {
                err(ctx, e.getMessage());
            } catch (RuntimeException e) {
                err(ctx, "Could not save your trail: " + e.getMessage());
                plugin.log().error("setTrail failed for " + uuid, e);
            }
        }
    }

    /** The one-argument form of {@code /supporter trail}. */
    public static final class SetTrailVariant extends PublicPlayerCommand {
        private final SupporterPlugin plugin;
        private final Argument idArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public SetTrailVariant(SupporterPlugin plugin) {
            super("Choose a particle trail.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            this.idArg = withRequiredArg("trail", "trail name", (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            TrailSub.apply(plugin, ctx, player.getUuid(), String.valueOf(ctx.get(idArg)).trim());
        }
    }

    // --- /supporter trails <on|off> -----------------------------------------------------------

    /**
     * Hides or shows other people's trails.
     *
     * <p><b>Not a supporter perk, deliberately.</b> Anyone may turn other players' particles
     * off. A cosmetic effect that the people who have to look at it cannot switch off is a
     * nuisance rather than a perk — and it is the honest answer to "the server is too busy".
     */
    public static final class TrailsVisibilitySub extends PublicPlayerCommand {
        private final SupporterPlugin plugin;
        private final Argument stateArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public TrailsVisibilitySub(SupporterPlugin plugin) {
            super("trails", "Show or hide other players' trails: <on|off>");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            this.stateArg = withRequiredArg("state", "on or off", (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            String state = String.valueOf(ctx.get(stateArg)).trim().toLowerCase();
            boolean show;
            if (state.equals("on") || state.equals("show")) {
                show = true;
            } else if (state.equals("off") || state.equals("hide")) {
                show = false;
            } else {
                err(ctx, "Use /supporter trails on or /supporter trails off.");
                return;
            }
            try {
                service.setHideTrails(player.getUuid(), !show);
                ok(ctx, show ? "Other players' trails are now visible."
                        : "Other players' trails are now hidden for you.");
            } catch (RuntimeException e) {
                err(ctx, "Could not save that: " + e.getMessage());
                plugin.log().error("setHideTrails failed", e);
            }
        }
    }

    // --- /supporter perks ---------------------------------------------------------------------

    /**
     * What supporters get, and what the player currently has.
     *
     * <p>Doubles as the source of truth for the store page: if this and the shop disagree with
     * what the store advertises, the store is wrong.
     *
     * <p>The home numbers come from config and are <b>descriptive only</b> — EliteEssentials
     * owns homes on this server and resolves limits through LuckPerms. If those are changed,
     * {@code supporter.json} has to be changed to match or this command lies to players.
     */
    public static final class PerksSub extends PublicPlayerCommand {
        private final SupporterPlugin plugin;

        public PerksSub(SupporterPlugin plugin) {
            super("perks", "What supporter rank gets you.");
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
            var config = plugin.config();
            UUID uuid = player.getUuid();
            boolean active = service.isSupporter(uuid);

            ok(ctx, active ? "Supporter perks — you have these now:" : "Supporter perks:");
            info(ctx, "  Chat tag, custom title and chat colour");
            info(ctx, "    /supporter title <text>, /supporter colour <hex>");
            info(ctx, "  Particle trails — " + config.trails().size() + " to choose from");
            info(ctx, "    /supporter trail <name>, /supporter shop");
            info(ctx, "  A supporter cape — " + CapeSub.DESIGNS.size() + " designs");
            info(ctx, "    /supporter cape");
            info(ctx, "  Headwear — " + String.join(", ", HatSub.HATS.keySet()));
            info(ctx, "    /supporter hat");
            info(ctx, "  Supporter trainers");
            info(ctx, "    /supporter shoes — or the panel Wardrobe tab for all of it");
            info(ctx, "  Homes: " + config.supporterHomeSlots()
                    + " instead of " + config.defaultHomeSlots());
            info(ctx, "  Tokens: " + config.tokensPerMonth() + " per month of support");
            info(ctx, "  Quests: 3 dailies at " + config.questDailyReward()
                    + " tokens, a weekly at " + config.questWeeklyReward()
                    + " — /supporter quests");
            info(ctx, "  Pets — " + String.join(", ", PetSub.PETS.keySet())
                    + ": /supporter pet");

            if (active) {
                info(ctx, "");
                ok(ctx, "You: " + service.daysRemaining(uuid) + " day(s) left, "
                        + service.tokenBalance(uuid) + " token(s), "
                        + service.unlocks(uuid).size() + " unlock(s).");
            } else {
                info(ctx, "");
                info(ctx, "You are not a supporter yet.");
            }
        }
    }

    // --- /supporter info ----------------------------------------------------------------------

    /**
     * The front page: what supporter rank is, what it gives, and what it costs.
     *
     * <p>Separate from {@code /supporter perks}, which lists features to somebody who already
     * knows what this is. {@code info} is for the player who has just seen a chat tag and
     * wondered what it was — so it leads with the thing that actually matters on a faction PvP
     * server, which is that none of this can be used to win a fight.
     *
     * <p>Four messages already pointed players at "/supporter to find out more", and
     * {@code /supporter} shows their status instead — so the pointer answered a question nobody
     * had asked. They now point here.
     *
     * <p>Pricing is read from config and never hard-coded. If it has not been filled in, this
     * says so plainly rather than inventing a number, and warns the admin once in the log.
     */
    public static final class InfoSub extends PublicPlayerCommand {
        private static final AtomicBoolean UNCONFIGURED_WARNED = new AtomicBoolean();

        private final SupporterPlugin plugin;

        public InfoSub(SupporterPlugin plugin) {
            super("info", "What supporter rank is, and what it costs.");
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
            var config = plugin.config();
            boolean active = service.isSupporter(player.getUuid());

            ok(ctx, "Supporter rank — what it is");
            info(ctx, "  This server is paid for by the people who play on it. Supporter rank");
            info(ctx, "  is how you chip in, if you want to and can afford to.");
            info(ctx, "");
            info(ctx, "  Everything it gives you is cosmetic or convenience. It does NOT touch");
            info(ctx, "  combat, claims, power, outpost income, KOTJ or bucks — you cannot buy");
            info(ctx, "  an advantage here, and supporters have no edge in a fight.");
            info(ctx, "");
            info(ctx, "  What you get:");
            info(ctx, "    A chat tag, your own title and chat colour");
            info(ctx, "    Particle trails — " + config.trails().size() + " to choose from");
            info(ctx, "    A supporter cape — " + CapeSub.DESIGNS.size() + " designs, /supporter cape");
            info(ctx, "    Headwear and trainers — " + String.join(", ", HatSub.HATS.keySet()) + ", trainers");
            info(ctx, "    Homes: " + config.supporterHomeSlots()
                    + " instead of " + config.defaultHomeSlots());
            info(ctx, "    Pets — cosmetic followers that cannot fight: "
                    + String.join(", ", PetSub.PETS.keySet()));
            info(ctx, "    " + config.tokensPerMonth()
                    + " tokens a month, plus daily and weekly quests that pay more");
            info(ctx, "");

            if (config.hasStorefront()) {
                if (!config.priceLines().isEmpty()) {
                    info(ctx, "  Price:");
                    for (String line : config.priceLines()) {
                        info(ctx, "    " + line);
                    }
                }
                if (!config.storeUrl().isEmpty()) {
                    info(ctx, "  Where: " + config.storeUrl());
                } else {
                    // A price with no way to pay it is a dead end. This is a real state — the
                    // price gets decided before the store is built — so it needs an answer
                    // rather than silence.
                    info(ctx, "  Where: ask an admin — the store is not open yet.");
                }
            } else {
                info(ctx, "  Pricing is not set up yet — ask an admin how to support the server.");
                if (UNCONFIGURED_WARNED.compareAndSet(false, true)) {
                    plugin.log().warn("/supporter info has no pricing to show: set storeUrl and "
                            + "priceLines in plugins/SupporterMod/supporter.json. Players asking "
                            + "how to support the server are currently told to ask an admin.");
                }
            }

            info(ctx, "");
            if (active) {
                info(ctx, "  You are a supporter — thank you. /supporter status for your time "
                        + "left.");
            } else {
                info(ctx, "  /supporter perks for the full list, /supporter status for your own.");
            }
        }
    }

    // --- /supporter cape ----------------------------------------------------------------------

    /**
     * {@code /supporter cape} lists the designs; {@code /supporter cape <design>} hands one over.
     *
     * <p><b>Six designs, one per allowed chat colour</b> — the same palette as
     * {@code allowedChatColors}, so a supporter can match their cape to their chat identity.
     * Amber keeps the original {@code Supporter_Cape} item id because capes already in players'
     * inventories must keep resolving; the other five are suffixed ids.
     *
     * <p><b>Re-issuable on purpose, in any design, as often as asked.</b> A cape is an item, so
     * it can be dropped, lost on death or left in a chest, and the standing rule here is that
     * nobody loses what they paid for. The entitlement is the record of truth and the item is
     * minted on demand. There is deliberately no one-cape limit — the designs are the perk.
     *
     * <p>It is a chest-slot armour piece with zero damage resistance — no combat advantage, and
     * wearing it means wearing no chest armour. That trade-off is the whole design: it is a
     * cosmetic to be seen in, not an edge in a fight.
     *
     * <p>The design argument lives on a nameless usage variant, dispatched on required-argument
     * count — the standard workaround for {@code withOptionalArg} registering a named flag
     * rather than an optional positional.
     */
    public static final class CapeSub extends PublicPlayerCommand {

        /**
         * Design name → item id, matching Server/Item/Items/Armor/Cloak/ in our asset pack.
         * Insertion order is display order.
         */
        public static final java.util.Map<String, String> DESIGNS = new java.util.LinkedHashMap<>();

        static {
            DESIGNS.put("amber", "Supporter_Cape");
            DESIGNS.put("frost", "Supporter_Cape_Frost");
            DESIGNS.put("rose", "Supporter_Cape_Rose");
            DESIGNS.put("jade", "Supporter_Cape_Jade");
            DESIGNS.put("gold", "Supporter_Cape_Gold");
            DESIGNS.put("ember", "Supporter_Cape_Ember");
            // The seventh design and the first PAID one — gearCosts prices it; the free six
            // stay free forever (they are the advertised perk).
            DESIGNS.put("black", "Supporter_Cape_Black");
        }

        public CapeSub(SupporterPlugin plugin) {
            super("cape", "Your supporter cape: 7 designs to choose from.");
            setPermissionGroup(GameMode.Adventure);
            // "/supporter capes" reads more naturally for the list, so it is an alias — the
            // bare form of either spelling lists, and "<design>" on either delivers.
            addAliases(new String[] {"capes"});
            addUsageVariant(new CapeDesignVariant(plugin));
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            // The bare command only informs, so it stays useful to non-supporters too — seeing
            // the designs is part of the pitch.
            ok(ctx, "Cape designs: " + String.join(", ", DESIGNS.keySet()));
            info(ctx, "/supporter cape <design> to get one — matching your chat colour, "
                    + "if you like.");
        }
    }

    /** The {@code /supporter cape <design>} form. */
    public static final class CapeDesignVariant extends PublicPlayerCommand {
        private final SupporterPlugin plugin;
        private final Argument designArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public CapeDesignVariant(SupporterPlugin plugin) {
            super("Get a supporter cape by design name.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            this.designArg = withRequiredArg("design", "cape design",
                    (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            if (!service.isSupporter(player.getUuid())) {
                err(ctx, "The cape is a supporter perk. /supporter info to find out more.");
                return;
            }
            String design = String.valueOf(ctx.get(designArg)).trim().toLowerCase();
            String itemId = CapeSub.DESIGNS.get(design);
            if (itemId == null) {
                err(ctx, "No such design. Choose from: "
                        + String.join(", ", CapeSub.DESIGNS.keySet()));
                return;
            }
            int cost = plugin.config().gearCost(design);
            if (!service.ownsGear(player.getUuid(), design, cost)) {
                err(ctx, "The " + design + " cape is locked — " + cost
                        + " tokens. /supporter buy " + design + ", or the Shop tab.");
                return;
            }
            try {
                ItemStackTransaction tx = Player.giveItem(new ItemStack(itemId, 1), ref, store);
                ItemStack remainder = tx == null ? null : tx.getRemainder();
                if (remainder != null && !remainder.isEmpty()) {
                    err(ctx, "No room in your inventory — clear a slot and try again.");
                    return;
                }
                ok(ctx, "Supporter cape (" + design + ") delivered. Wear it in your chest slot.");
                info(ctx, "It gives no protection at all, so take it off before a fight. "
                        + "Lost it, or want another design? Run this again any time.");
            } catch (Throwable t) {
                err(ctx, "Could not deliver the cape: " + t.getMessage());
                plugin.log().error("Cape delivery failed for " + player.getUuid(), t);
            }
        }
    }

    // --- /supporter hat -----------------------------------------------------------------------

    /**
     * {@code /supporter hat} — the Supporter Crown, head-slot counterpart of the cape.
     *
     * <p>Same shape as the cape in every way that matters: an armour item with zero damage
     * resistance, so no combat advantage and wearing it means wearing no helmet; re-issuable on
     * demand because nobody loses what they paid for; and the geometry ships in our asset pack —
     * the plugin's first self-authored model, built against the box schema read out of Violet's
     * Wardrobe's beanie (root node named "Head" is the bone anchor).
     *
     * <p>One design for now, deliberately — the cape went one design first too, and colour
     * variants are trivial once the bone attachment and scale are proven in game.
     */
    public static final class HatSub extends PublicPlayerCommand {

        /** Hat name → item id, matching Server/Item/Items/Armor/ in our asset pack. */
        public static final java.util.Map<String, String> HATS = new java.util.LinkedHashMap<>();

        static {
            HATS.put("crown", "Supporter_Crown");
            HATS.put("cowboy", "Supporter_Hat_Cowboy");
            HATS.put("shades", "Supporter_Shades");
            // The 0.20.0 restock — all three priced via gearCosts; the original three stay free.
            // "top-hat" was "top" until 0.21.4 (renamed on request — "top" alone read as
            // nothing). The ITEM id stays Supporter_Hat_Top forever, and migration V7 moved
            // the gear:top unlocks, because a rename must never cost anybody a purchase.
            HATS.put("top-hat", "Supporter_Hat_Top");
            HATS.put("wizard", "Supporter_Hat_Wizard");
            HATS.put("beanie", "Supporter_Hat_Beanie");
        }

        public HatSub(SupporterPlugin plugin) {
            super("hat", "Your supporter headwear: " + String.join(", ", HATS.keySet()));
            setPermissionGroup(GameMode.Adventure);
            addAliases(new String[] {"hats", "crown"});
            addUsageVariant(new HatDesignVariant(plugin));
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            ok(ctx, "Headwear: " + String.join(", ", HATS.keySet()));
            info(ctx, "One head item at a time - the head slot holds one thing.");
            info(ctx, "/supporter hat <name> to get one.");
        }
    }

    /** The {@code /supporter hat <name>} form. */
    public static final class HatDesignVariant extends PublicPlayerCommand {
        private final SupporterPlugin plugin;
        private final Argument nameArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public HatDesignVariant(SupporterPlugin plugin) {
            super("Get a supporter hat.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            this.nameArg = withRequiredArg("hat", "hat name", (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            if (!service.isSupporter(player.getUuid())) {
                err(ctx, "Hats are a supporter perk. /supporter info to find out more.");
                return;
            }
            String name = String.valueOf(ctx.get(nameArg)).trim().toLowerCase();
            String itemId = HatSub.HATS.get(name);
            if (itemId == null) {
                err(ctx, "No such hat. Choose from: " + String.join(", ", HatSub.HATS.keySet()));
                return;
            }
            int cost = plugin.config().gearCost(name);
            if (!service.ownsGear(player.getUuid(), name, cost)) {
                err(ctx, "The " + name + " hat is locked — " + cost
                        + " tokens. /supporter buy " + name + ", or the Shop tab.");
                return;
            }
            try {
                ItemStackTransaction tx = Player.giveItem(new ItemStack(itemId, 1), ref, store);
                ItemStack remainder = tx == null ? null : tx.getRemainder();
                if (remainder != null && !remainder.isEmpty()) {
                    err(ctx, "No room in your inventory — clear a slot and try again.");
                    return;
                }
                ok(ctx, "Supporter hat (" + name + ") delivered. Wear it in your head slot.");
                info(ctx, "It gives no protection at all, so take it off before a fight. "
                        + "Lost it, or want the other one? Run this again any time.");
            } catch (Throwable t) {
                err(ctx, "Could not deliver the hat: " + t.getMessage());
                plugin.log().error("Hat delivery failed for " + player.getUuid(), t);
            }
        }
    }

    // --- /supporter skin ----------------------------------------------------------------------

    /**
     * {@code /supporter skin} — body tints: the player's own model with its gradient swapped.
     *
     * <p><b>Session-only, stated up front.</b> The tint rides the live {@code ModelComponent},
     * which the server rebuilds from the account skin on relogin, so it resets when you relog —
     * and that reset is load-bearing, because it is also the always-correct restore. If the live
     * test proves the render, persistence (re-apply on login from the identity table) is the
     * follow-up.
     *
     * <p>{@code off} pushes back the model cached at first apply. It cannot rebuild from the
     * account skin instead — that is the exact call that stripped the character to underwear in
     * the cape spike.
     */
    public static final class SkinSub extends PublicPlayerCommand {

        public SkinSub(SupporterPlugin plugin) {
            super("skin", "Body tints: "
                    + String.join(", ", com.peoplesserver.supportermod.ui.SkinChanger.SKINS
                            .keySet()) + ", off");
            setPermissionGroup(GameMode.Adventure);
            addAliases(new String[] {"skins"});
            addUsageVariant(new SkinVariant(plugin));
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            ok(ctx, "Skins: " + String.join(", ",
                    com.peoplesserver.supportermod.ui.SkinChanger.allNames()) + " — or off");
            info(ctx, "/supporter skin <name>. Yours stays on until you turn it off — "
                    + "even across relogs.");
        }
    }

    /** The {@code /supporter skin <name|off>} form. */
    public static final class SkinVariant extends PublicPlayerCommand {
        private final SupporterPlugin plugin;
        private final Argument nameArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public SkinVariant(SupporterPlugin plugin) {
            super("Apply a body tint, or off.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            this.nameArg = withRequiredArg("skin", "skin name or off",
                    (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            if (!service.isSupporter(player.getUuid())) {
                err(ctx, "Skins are a supporter perk. /supporter info to find out more.");
                return;
            }
            String name = String.valueOf(ctx.get(nameArg)).trim().toLowerCase();
            boolean off = name.equals("off");
            if (!off && !com.peoplesserver.supportermod.ui.SkinChanger.knows(name)) {
                err(ctx, "No such skin. Choose from: " + String.join(", ",
                        com.peoplesserver.supportermod.ui.SkinChanger.allNames()) + ", off");
                return;
            }
            java.util.UUID uuid = player.getUuid();
            // v0.19.0: priced skins must be unlocked first — same ladder as the trails. Free
            // skins pass straight through; owned ones too. The login re-apply deliberately does
            // NOT re-check, so a skin worn before pricing existed keeps working until changed.
            if (!off) {
                int cost = plugin.config().skinCost(name,
                        com.peoplesserver.supportermod.ui.SkinChanger.isCostume(name));
                if (!service.ownsSkin(uuid, name, cost)) {
                    err(ctx, "That skin is locked — " + cost + " tokens. /supporter buy "
                            + name + ", or the Shop tab.");
                    return;
                }
            }
            // The DB write happens before the world-thread hop: SQLite is safe from any thread,
            // and the choice must persist even if the visual push fails — login re-applies it.
            try {
                service.setSkin(uuid, off ? null : name);
            } catch (RuntimeException e) {
                err(ctx, "Could not save your skin choice: " + e.getMessage());
                plugin.log().error("setSkin failed for " + uuid, e);
                return;
            }
            // Explicit hand-off: every SkinChanger entry point touches components, and being
            // explicit about the world thread costs nothing.
            world.execute(() -> {
                try {
                    com.peoplesserver.supportermod.ui.SkinChanger.Result result = off
                            ? com.peoplesserver.supportermod.ui.SkinChanger.restore(
                                    store, ref, uuid)
                            : com.peoplesserver.supportermod.ui.SkinChanger.applyByName(
                                    store, ref, uuid, name);
                    if (result.applied() || off) {
                        ok(ctx, off ? "Skin off — you are fully restored."
                                : "Skin on: " + name + ". Third person to see it.");
                        if (!off) {
                            info(ctx, "Statue mode: hair and clothing are packed away while "
                                    + "it is on; worn supporter gear still shows. It stays "
                                    + "until you turn it off — even across relogs.");
                        }
                    } else {
                        err(ctx, "Skin change failed: " + result.detail());
                    }
                } catch (Throwable t) {
                    err(ctx, "Skin change threw: " + t);
                    plugin.log().error("Skin change failed for " + uuid, t);
                }
            });
        }
    }

    // --- /supporter shoes ---------------------------------------------------------------------

    /**
     * {@code /supporter shoes} — footwear, in the LEGS slot because the game has no feet slot.
     *
     * <p>That makes this the steepest cosmetic trade-off in the plugin and worth being explicit
     * about: {@code ItemArmorSlot} defines Head, Chest, Hands, Legs and nothing else, vanilla
     * "boots" are Legs-slot items, and even the cheapest wool pair carries +13 Health and
     * resistances. Wearing cosmetic trainers therefore costs real protection — same rule as the
     * cape and hats, dialled up. The item hides only the cosmetic Shoes part, not Pants, so
     * trousers stay visible.
     *
     * <p>Third self-authored model and the first animated one: two root bones (L-Calf/R-Calf)
     * with foot anchors the walk cycle drives, positions copied digit-for-digit from the vanilla
     * Bronze legs model because those numbers are the bone binding.
     */
    public static final class ShoesSub extends PublicPlayerCommand {

        /** Shoe name → item id, matching Server/Item/Items/Armor/ in our asset pack. */
        public static final java.util.Map<String, String> SHOES = new java.util.LinkedHashMap<>();

        static {
            SHOES.put("trainers", "Supporter_Trainers");
        }

        public ShoesSub(SupporterPlugin plugin) {
            super("shoes", "Your supporter footwear: " + String.join(", ", SHOES.keySet()));
            setPermissionGroup(GameMode.Adventure);
            addAliases(new String[] {"trainers"});
            addUsageVariant(new ShoeDesignVariant(plugin));
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            ok(ctx, "Footwear: " + String.join(", ", SHOES.keySet()));
            info(ctx, "/supporter shoes <name> to get a pair. They use the legs armour slot.");
        }
    }

    /** The {@code /supporter shoes <name>} form. */
    public static final class ShoeDesignVariant extends PublicPlayerCommand {
        private final SupporterPlugin plugin;
        private final Argument nameArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public ShoeDesignVariant(SupporterPlugin plugin) {
            super("Get supporter footwear.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            this.nameArg = withRequiredArg("shoe", "footwear name", (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            if (!service.isSupporter(player.getUuid())) {
                err(ctx, "Footwear is a supporter perk. /supporter info to find out more.");
                return;
            }
            String name = String.valueOf(ctx.get(nameArg)).trim().toLowerCase();
            String itemId = ShoesSub.SHOES.get(name);
            if (itemId == null) {
                err(ctx, "No such footwear. Choose from: "
                        + String.join(", ", ShoesSub.SHOES.keySet()));
                return;
            }
            // Unreachable today (no footwear is priced) and deliberately here anyway: a shoe
            // priced in gearCosts tomorrow must not silently bypass the gate the capes and
            // hats have.
            int cost = plugin.config().gearCost(name);
            if (!service.ownsGear(player.getUuid(), name, cost)) {
                err(ctx, "That footwear is locked — " + cost
                        + " tokens. /supporter buy " + name + ", or the Shop tab.");
                return;
            }
            try {
                ItemStackTransaction tx = Player.giveItem(new ItemStack(itemId, 1), ref, store);
                ItemStack remainder = tx == null ? null : tx.getRemainder();
                if (remainder != null && !remainder.isEmpty()) {
                    err(ctx, "No room in your inventory — clear a slot and try again.");
                    return;
                }
                ok(ctx, "Supporter " + name + " delivered. Wear them in your legs slot.");
                info(ctx, "They give no protection at all — that slot is real armour on anyone "
                        + "else, so take them off before a fight. Lost them? Run this again.");
            } catch (Throwable t) {
                err(ctx, "Could not deliver: " + t.getMessage());
                plugin.log().error("Shoe delivery failed for " + player.getUuid(), t);
            }
        }
    }

    // --- /supporter pet -----------------------------------------------------------------------

    /**
     * {@code /supporter pet} — cosmetic followers, the premium tier of the shop.
     *
     * <p>The pet cannot fight, aggro or be commanded to: the roles are {@code Type:"Generic"},
     * which has no combat tree, so the pay-to-win line is enforced by the role type itself.
     * The choice persists; the pet tick re-spawns it at login and across world changes, and a
     * killed pet is back within a second — a cosmetic must not be killable in any way that
     * matters.
     */
    public static final class PetSub extends PublicPlayerCommand {

        /**
         * Pet name → NPC role in our asset pack. THE catalogue — the panel, the pet tick and
         * pettest all read this map. Entries are added only after the rig passed a pettest
         * look (spawns, ANIMATES, follows): bunny, fox and penguin all did on 2026-08-19.
         */
        public static final java.util.Map<String, String> PETS = new java.util.LinkedHashMap<>();

        static {
            PETS.put("bunny", "SupporterPet_Bunny");
            PETS.put("fox", "SupporterPet_Fox");
            PETS.put("penguin", "SupporterPet_Penguin");
            // The 0.22.1 batch — all walkers on the proven recipe (fliers like the owl and
            // parrot wait until a pettest proves a flying rig walks acceptably). Each of
            // these still gets a pettest look before players are pointed at it.
            PETS.put("squirrel", "SupporterPet_Squirrel");
            PETS.put("mouse", "SupporterPet_Mouse");
            PETS.put("meerkat", "SupporterPet_Meerkat");
            PETS.put("frog", "SupporterPet_Frog");
            PETS.put("duck", "SupporterPet_Duck");
            PETS.put("tortoise", "SupporterPet_Tortoise");
            // 0.22.2: chick and piglet are the FREE pair — every supporter gets a companion
            // immediately, tokens buy the menagerie, the same shape as trails and tints.
            PETS.put("chick", "SupporterPet_Chick");
            PETS.put("piglet", "SupporterPet_Piglet");
            PETS.put("rat", "SupporterPet_Rat");
            PETS.put("gecko", "SupporterPet_Gecko");
            PETS.put("lamb", "SupporterPet_Lamb");
            PETS.put("goat", "SupporterPet_Goat");
        }

        private final SupporterPlugin plugin;

        public PetSub(SupporterPlugin plugin) {
            super("pet", "Your supporter pet: " + String.join(", ", PETS.keySet())
                    + " - or off.");
            setPermissionGroup(GameMode.Adventure);
            addAliases(new String[] {"pets"});
            this.plugin = plugin;
            addUsageVariant(new PetChoiceVariant(plugin));
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            // Bare form informs, visible to non-supporters — seeing the pets is the pitch.
            ok(ctx, "Pets: " + String.join(", ", PETS.keySet())
                    + " - cosmetic followers. They cannot fight, for you or anyone.");
            info(ctx, "/supporter pet <name> to bring one out; /supporter pet off sends it "
                    + "home. It follows you and stays across relogs.");
        }
    }

    /** The {@code /supporter pet <name|off>} form. */
    public static final class PetChoiceVariant extends PublicPlayerCommand {
        private final SupporterPlugin plugin;
        private final Argument petArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public PetChoiceVariant(SupporterPlugin plugin) {
            super("Bring out a pet by name, or off to send it home.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            this.petArg = withRequiredArg("pet", "pet name, or off",
                    (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            var pets = plugin.pets();
            if (pets == null) {
                err(ctx, "Pets unavailable — the plugin failed to start.");
                return;
            }
            UUID uuid = player.getUuid();
            String name = String.valueOf(ctx.get(petArg)).trim().toLowerCase();
            if (name.equals("off")) {
                service.setPet(uuid, null);
                pets.removeFor(uuid, store);
                ok(ctx, "Pet sent home.");
                return;
            }
            if (!service.isSupporter(uuid)) {
                err(ctx, "Pets are a supporter perk. /supporter info to find out more.");
                return;
            }
            String role = PetSub.PETS.get(name);
            if (role == null) {
                err(ctx, "No such pet. Choose from: " + String.join(", ", PetSub.PETS.keySet())
                        + " - or off.");
                return;
            }
            int cost = plugin.config().petCost(name);
            if (!service.ownsPet(uuid, name, cost)) {
                err(ctx, "The " + name + " is locked — " + cost + " tokens. /supporter buy "
                        + name + ", or the Shop tab.");
                return;
            }
            service.setPet(uuid, name);
            var transform = store.getComponent(ref,
                    com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
                            .getComponentType());
            String failure = transform == null || transform.getPosition() == null
                    ? "could not read your position — it will appear within a second or two"
                    : pets.spawn(store, uuid,
                            new org.joml.Vector3d(transform.getPosition()), role);
            if (failure != null) {
                info(ctx, "Saved: " + failure);
                return;
            }
            ok(ctx, "Pet out: " + name + "! It follows you and stays across relogs.");
            info(ctx, "/supporter pet off sends it home.");
        }
    }

    // --- /supporter uitest (SPIKE) ------------------------------------------------------------

    /**
     * Opens the panel redesign spike: the flat-colour shell and its Status screen. Admin-only
     * and additive — {@code /supporter} still opens the live panel, so the redesign can be
     * judged in game without anybody else seeing a half-converted UI.
     */
    public static final class UitestSub extends AbstractPlayerCommand {
        private final SupporterPlugin plugin;

        public UitestSub(SupporterPlugin plugin) {
            super("uitest", "ADMIN: preview the redesigned panel (Status screen only).");
            setPermissionGroup(GameMode.Creative);
            this.plugin = plugin;
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            if (plugin.openFlatPanel(player, store, world)) {
                info(ctx, "Redesign preview open. Judge the surfaces, not the wiring - the nav "
                        + "is inert and only Status is built.");
                info(ctx, "Check the probe strip at the bottom: whichever swatches are visible "
                        + "name the fill texture path that resolves.");
                return;
            }
            err(ctx, "Could not open the preview - HyUI may be unavailable.");
        }
    }

    // --- /supporter pettest (SPIKE) -----------------------------------------------------------

    /**
     * The spike that proved pets (2026-08-19: all three rigs animate, follow and clean up),
     * kept as the ADMIN RIG-VETTING TOOL: spawns without ownership, persistence or entitlement
     * so a new candidate role can be eyeballed before it earns a PetSub.PETS entry. Reads the
     * same catalogue as the real command — one source of truth.
     */
    public static final class PettestSub extends AbstractPlayerCommand {

        private final SupporterPlugin plugin;
        private final Argument petArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public PettestSub(SupporterPlugin plugin) {
            super("pettest", "ADMIN rig vetting: spawn a follower pet - <"
                    + String.join("|", PetSub.PETS.keySet()) + "|off>");
            setPermissionGroup(GameMode.Creative);
            this.plugin = plugin;
            this.petArg = withRequiredArg("pet", "pet name, or off",
                    (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            var pets = plugin.pets();
            if (pets == null) {
                err(ctx, "Pet system unavailable — the plugin failed to start.");
                return;
            }
            String name = String.valueOf(ctx.get(petArg)).trim().toLowerCase();
            UUID uuid = player.getUuid();
            if (name.equals("off")) {
                ok(ctx, pets.removeFor(uuid, store)
                        ? "Pet removed." : "You have no pet out.");
                return;
            }
            String role = PetSub.PETS.get(name);
            if (role == null) {
                err(ctx, "No such pet. Choose from: " + String.join(", ", PetSub.PETS.keySet())
                        + " — or off.");
                return;
            }
            var transform = store.getComponent(ref,
                    com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
                            .getComponentType());
            if (transform == null || transform.getPosition() == null) {
                err(ctx, "Could not read your position.");
                return;
            }
            String failure = pets.spawn(store, uuid,
                    new org.joml.Vector3d(transform.getPosition()), role);
            if (failure != null) {
                err(ctx, "Pet spawn failed: " + failure);
                plugin.log().warn("Pet spawn failed for " + uuid + ": " + failure);
                return;
            }
            ok(ctx, "Pet out: " + name + ". Walk around — it should follow within a second "
                    + "or two, and teleport to you if left more than 40 blocks behind.");
            info(ctx, "Watch for: does it animate, does it keep up when you sprint, does it "
                    + "get in the way. /supporter pettest off removes it.");
        }
    }

    // --- /supporter tokens --------------------------------------------------------------------

    public static final class TokensSub extends PublicPlayerCommand {
        private final SupporterPlugin plugin;

        public TokensSub(SupporterPlugin plugin) {
            super("tokens", "Show your token balance.");
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
            UUID uuid = player.getUuid();
            int earned = service.tokensEarned(uuid);
            int spent = service.tokensSpent(uuid);
            ok(ctx, "Tokens: " + service.tokenBalance(uuid)
                    + "  (earned " + earned + ", spent " + spent + ")");
            info(ctx, "You earn " + plugin.config().tokensPerMonth()
                    + " per whole month of support. /supporter shop to spend them.");
        }
    }

    // --- /supporter shop ----------------------------------------------------------------------

    public static final class ShopSub extends PublicPlayerCommand {
        private final SupporterPlugin plugin;

        public ShopSub(SupporterPlugin plugin) {
            super("shop", "See what your tokens can buy.");
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
            UUID uuid = player.getUuid();
            int balance = service.tokenBalance(uuid);
            ok(ctx, "Supporter shop — you have " + balance + " token(s):");
            for (String id : plugin.config().trails().keySet()) {
                int cost = plugin.config().trailCost(id);
                String state;
                if (cost <= 0) {
                    state = "free";
                } else if (service.owns(uuid, id)) {
                    state = "owned";
                } else if (balance >= cost) {
                    state = cost + " tokens — /supporter buy " + id;
                } else {
                    state = cost + " tokens (need " + (cost - balance) + " more)";
                }
                info(ctx, "  " + id + " — " + state);
            }
        }
    }

    // --- /supporter buy <id> ------------------------------------------------------------------

    public static final class BuySub extends PublicPlayerCommand {
        private final SupporterPlugin plugin;
        private final Argument idArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public BuySub(SupporterPlugin plugin) {
            super("buy", "Spend tokens on a trail: <name>");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            this.idArg = withRequiredArg("item", "what to buy", (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            UUID uuid = player.getUuid();
            if (!service.isSupporter(uuid)) {
                err(ctx, "The shop is for supporters. /supporter info to find out more.");
                return;
            }
            String item = String.valueOf(ctx.get(idArg)).trim();
            try {
                // Trails first; if the name is not a trail, try the skin catalogue. The two
                // namespaces collide on purpose-friendly names ("gold" is both), and trails win
                // for backwards compatibility — the gold SKIN is bought via the panel, where the
                // sections are separate.
                SupporterService.PurchaseResult result = service.purchaseTrail(uuid, item);
                String wearHint = "/supporter trail " + item;
                if (result == SupporterService.PurchaseResult.UNKNOWN_ITEM
                        && com.peoplesserver.supportermod.ui.SkinChanger.knows(item)) {
                    int cost = plugin.config().skinCost(item,
                            com.peoplesserver.supportermod.ui.SkinChanger.isCostume(item));
                    result = service.purchaseSkin(uuid, item, cost);
                    wearHint = "/supporter skin " + item;
                }
                // Third catalogue: wearables. Same order every time — trails, skins, gear,
                // pets — so a colliding name always resolves the same way.
                if (result == SupporterService.PurchaseResult.UNKNOWN_ITEM) {
                    String lower = item.toLowerCase();
                    String getHint = CapeSub.DESIGNS.containsKey(lower) ? "/supporter cape "
                            : HatSub.HATS.containsKey(lower) ? "/supporter hat "
                            : ShoesSub.SHOES.containsKey(lower) ? "/supporter shoes " : null;
                    if (getHint != null) {
                        result = service.purchaseGear(uuid, lower,
                                plugin.config().gearCost(lower));
                        wearHint = getHint + lower;
                    }
                }
                // Fourth catalogue: pets.
                if (result == SupporterService.PurchaseResult.UNKNOWN_ITEM) {
                    String lower = item.toLowerCase();
                    if (PetSub.PETS.containsKey(lower)) {
                        result = service.purchasePet(uuid, lower, plugin.config().petCost(lower));
                        wearHint = "/supporter pet " + lower;
                    }
                }
                String hint = wearHint;
                switch (result) {
                    case BOUGHT -> ok(ctx, "Unlocked " + item + "! Use " + hint
                            + " to wear it. Tokens left: " + service.tokenBalance(uuid));
                    case ALREADY_OWNED -> info(ctx, "You already own " + item + ".");
                    case FREE -> info(ctx, item + " is free — just " + hint + ".");
                    case NOT_ENOUGH_TOKENS -> err(ctx, "Not enough tokens — you have "
                            + service.tokenBalance(uuid) + ".");
                    case UNKNOWN_ITEM -> err(ctx, "No such item. /supporter shop to see the list.");
                }
            } catch (RuntimeException e) {
                err(ctx, "Purchase failed: " + e.getMessage());
                plugin.log().error("purchase failed for " + uuid, e);
            }
        }
    }

    // --- /supporter quests --------------------------------------------------------------------

    /**
     * The chat fallback for the Quests tab: list with numbers, claim by number. The panel is
     * the intended surface, but every panel feature keeps a chat path — HyUI is optional.
     */
    public static final class QuestsSub extends PublicPlayerCommand {
        private final SupporterPlugin plugin;

        public QuestsSub(SupporterPlugin plugin) {
            super("quests", "Your daily and weekly quests: list, and claim <number>.");
            setPermissionGroup(GameMode.Adventure);
            addAliases(new String[] {"quest"});
            this.plugin = plugin;
            addUsageVariant(new QuestClaimVariant(plugin));
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            UUID uuid = player.getUuid();
            if (!service.isSupporter(uuid)) {
                err(ctx, "Quests are a supporter perk. /supporter info to find out more.");
                return;
            }
            ok(ctx, "Quests - dailies pay " + plugin.config().questDailyReward()
                    + " tokens, the weekly pays " + plugin.config().questWeeklyReward()
                    + ". Reset is midnight UTC.");
            int n = 1;
            for (SupporterService.QuestState q : service.quests(uuid)) {
                String state = q.claimed() ? " (claimed)"
                        : q.complete() ? " (READY - /supporter quests " + n + ")"
                        : "";
                info(ctx, "  " + n + ". " + (q.daily() ? "" : "[weekly] ") + q.label()
                        + " - " + q.progress() + "/" + q.target() + state);
                n++;
            }
        }
    }

    /** The {@code /supporter quests <number>} form — claims that quest. */
    public static final class QuestClaimVariant extends PublicPlayerCommand {
        private final SupporterPlugin plugin;
        private final Argument numberArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public QuestClaimVariant(SupporterPlugin plugin) {
            super("Claim a completed quest by its number from the list.");
            setPermissionGroup(GameMode.Adventure);
            this.plugin = plugin;
            this.numberArg = withRequiredArg("number", "quest number from the list",
                    (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            UUID uuid = player.getUuid();
            List<SupporterService.QuestState> quests = service.quests(uuid);
            int number;
            try {
                number = Integer.parseInt(String.valueOf(ctx.get(numberArg)).trim());
            } catch (NumberFormatException e) {
                err(ctx, "Give the quest's number - /supporter quests to see the list.");
                return;
            }
            if (number < 1 || number > quests.size()) {
                err(ctx, "No quest " + number + " - /supporter quests to see the list.");
                return;
            }
            SupporterService.QuestState q = quests.get(number - 1);
            try {
                switch (service.claimQuest(uuid, q.key())) {
                    case CLAIMED -> ok(ctx, "Quest complete: " + q.label() + " - +"
                            + q.reward() + " tokens. Balance: " + service.tokenBalance(uuid));
                    case NOT_DONE -> info(ctx, q.label() + " - " + q.progress() + "/"
                            + q.target() + ". Not finished yet.");
                    case ALREADY_CLAIMED -> info(ctx, "Already claimed.");
                    case NOT_SUPPORTER -> err(ctx,
                            "Quests are a supporter perk. /supporter info to find out more.");
                    case UNKNOWN_QUEST -> err(ctx,
                            "That quest expired at the reset - /supporter quests for today's.");
                }
            } catch (RuntimeException e) {
                err(ctx, "Claim failed: " + e.getMessage());
                plugin.log().error("Quest claim failed for " + uuid, e);
            }
        }
    }

    // --- /supporter chargeback <player> <days> ------------------------------------------------

    /**
     * Console-capable, because a chargeback notification arrives by email, not in game.
     *
     * <p>Distinct from revoke on purpose: revoke ends entitlement but keeps the tenure the
     * player paid for, while a chargeback removes tenure that was never paid for — and since
     * tokens are derived from tenure, they go with it. Items already bought are kept.
     */
    public static final class ChargebackSub extends CommandBase {
        private final SupporterPlugin plugin;
        private final Argument playerArg;
        private final Argument daysArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public ChargebackSub(SupporterPlugin plugin) {
            super("chargeback", "Remove unpaid tenure after a chargeback: <player> <days>");
            setPermissionGroup(GameMode.Creative);
            this.plugin = plugin;
            this.playerArg = withRequiredArg("player", "username", (ArgumentType) ArgTypes.STRING);
            this.daysArg = withRequiredArg("days", "days to remove",
                    (ArgumentType) ArgTypes.INTEGER);
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            String username = String.valueOf(ctx.get(playerArg)).trim();
            int days = (Integer) ctx.get(daysArg);
            Optional<SupporterRecord> record = service.findByUsername(username);
            if (record.isEmpty()) {
                err(ctx, "No supporter record for " + username + ".");
                return;
            }
            try {
                service.chargeback(record.get().uuid(), days, ctx.sender().getUsername());
                ok(ctx, "Chargeback applied to " + username + " — " + days
                        + " day(s) of tenure removed. Items they already bought are kept.");
            } catch (IllegalArgumentException e) {
                err(ctx, e.getMessage());
            } catch (RuntimeException e) {
                err(ctx, "Chargeback failed: " + e.getMessage());
                plugin.log().error("chargeback failed for " + username, e);
            }
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
            super("grant", "Grant supporter days: <player> <days> [transaction-id]");
            setPermissionGroup(GameMode.Creative);
            this.plugin = plugin;
            this.playerArg = withRequiredArg("player", "username", (ArgumentType) ArgTypes.STRING);
            this.daysArg = withRequiredArg("days", "days to add", (ArgumentType) ArgTypes.INTEGER);
            // The three-argument form is what a payment provider calls. See GrantWithTxnVariant.
            addUsageVariant(new GrantWithTxnVariant(plugin));
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

    /**
     * {@code /supporter grant <player> <days> <transaction-id>} — the form a payment provider
     * calls.
     *
     * <p><b>This variant is what makes a purchase safe to deliver.</b> Tebex retries a command
     * until the server acknowledges it, so without a transaction id one purchase can be granted
     * several times. {@code supporter_txn} exists to stop exactly that — its primary key IS the
     * transaction id — but the ledger is useless unless something passes a key to it, and until
     * now nothing could: the only grant command hard-coded {@code null}.
     *
     * <p>So configure the store to run:
     *
     * <pre>
     * supporter grant {username} 30 {transaction}
     * </pre>
     *
     * <p>A retry then reports {@code DUPLICATE_IGNORED} and changes nothing. The command still
     * succeeds, because a provider that sees a failure will simply retry harder.
     *
     * <p>The two-argument form deliberately keeps no transaction id: a human typing
     * {@code /supporter grant Someone 30} twice means it twice.
     */
    public static final class GrantWithTxnVariant extends CommandBase {
        private final SupporterPlugin plugin;
        private final Argument playerArg;
        private final Argument daysArg;
        private final Argument txnArg;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public GrantWithTxnVariant(SupporterPlugin plugin) {
            super("Grant supporter days from a purchase, applied at most once per transaction.");
            setPermissionGroup(GameMode.Creative);
            this.plugin = plugin;
            this.playerArg = withRequiredArg("player", "username", (ArgumentType) ArgTypes.STRING);
            this.daysArg = withRequiredArg("days", "days to add", (ArgumentType) ArgTypes.INTEGER);
            this.txnArg = withRequiredArg("transaction", "payment transaction id",
                    (ArgumentType) ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            SupporterService service = service(plugin, ctx);
            if (service == null) {
                return;
            }
            String username = String.valueOf(ctx.get(playerArg)).trim();
            int days = (Integer) ctx.get(daysArg);
            String txn = String.valueOf(ctx.get(txnArg)).trim();
            if (days <= 0) {
                err(ctx, "days must be positive, got " + days);
                return;
            }
            if (txn.isEmpty()) {
                err(ctx, "transaction id must not be empty — omit it entirely for a manual grant.");
                return;
            }
            try {
                GrantResult result = service.grantByUsername(username, days, "tebex", txn);
                switch (result.outcome()) {
                    case CREATED -> ok(ctx, "Purchase applied: created supporter for " + username
                            + " — " + days + " day(s). [txn " + txn + "]");
                    case EXTENDED -> ok(ctx, "Purchase applied: extended " + username + " by "
                            + days + " day(s) from their existing expiry. [txn " + txn + "]");
                    case QUEUED_PENDING -> ok(ctx, "Purchase queued for " + username
                            + " — they have never logged in; " + days
                            + " day(s) apply on their next login. [txn " + txn + "]");
                    case DUPLICATE_IGNORED -> info(ctx, "Transaction " + txn
                            + " was already applied — ignored. This is the retry guard working.");
                }
            } catch (RuntimeException e) {
                err(ctx, "Grant failed: " + e.getMessage());
                plugin.log().error("Purchase grant failed for " + username + " txn " + txn, e);
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
