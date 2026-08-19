package com.peoplesserver.supportermod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Contents of {@code supporter.json}.
 *
 * <p>SUPPORTERMOD_SPEC.md §4 was not available when this was written, so the key set here is
 * reconstructed from the phase prompts. Every field has a working default, and unknown keys in
 * the file are ignored rather than fatal — so reconciling this against the real §4 is a rename,
 * not a rewrite. Fields are grouped by the phase that consumes them; only the Phase 1 group is
 * read by any code today.
 */
public final class SupporterConfig {

    // --- Phase 1: entitlement -------------------------------------------------------------
    private int graceDays = 3;
    private int reconcileHourUtc = 4;
    private int renewalNudgeDaysBefore = 5;
    private String databaseFile = "supporter.db";

    // --- Phase 2: identity ----------------------------------------------------------------
    private boolean announceNewSupporters = true;
    private String tagColorHex = "#FFAA00";
    private List<String> allowedChatColors =
            List.of("#FFAA00", "#55FFFF", "#FF55FF", "#55FF55", "#FFFF55", "#FF5555");
    private int maxTitleLength = 24;
    private List<String> titleBlocklist = List.of();

    // --- Phase 3: homes -------------------------------------------------------------------
    //
    // THESE ARE DESCRIPTIVE, NOT ENFORCED. SupporterMod does not implement homes and does not
    // police these numbers — EliteEssentials owns /home, /sethome and /delhome on this server,
    // and resolves the limit through LuckPerms:
    //
    //   eliteessentials.command.home.limit.unlimited
    //   eliteessentials.command.home.limit.<N>      (highest value wins)
    //   homes.limit.<N>
    //   ConfigManager.getMaxHomes()                 (fallback, mods/EliteEssentials/config.json)
    //
    // TWO TRAPS IN THAT LAST LINE, both read out of EliteEssentials 2.0.8 bytecode rather than
    // inferred from the key names:
    //
    //   1. getMaxHomes() returns homes.maxHomes. It is NOT a ceiling — it is the number every
    //      player WITHOUT a node gets. A node's value is returned outright and is never clamped
    //      against it. So maxHomes must be set to the NON-supporter allowance; leave it at 10 and
    //      supporters are paying for a limit everybody already has.
    //   2. homes.defaultMaxHomes is dead config. The string appears in exactly one class file:
    //      the one declaring it. Nothing reads it, so editing it changes nothing.
    //
    // If EliteEssentials is ever updated, re-check both. If a later version makes maxHomes a real
    // ceiling, this server's maxHomes of 3 would silently cap supporters at 3 and the perk would
    // quietly stop being worth anything.
    //
    // Building a second home system inside SupporterMod would mean two competing /home
    // commands, so the perk is delivered by configuring those instead. What these keys do is
    // drive what /supporter perks TELLS players — so if you change the real limits in
    // EliteEssentials or LuckPerms, change them here too or the plugin will advertise a perk
    // that does not match reality.
    //
    // Verified before changing the live limit: lowering it only makes /sethome return
    // LIMIT_REACHED. HomeService has no bulk delete, so existing homes above the new cap are
    // kept and stay usable.
    private int defaultHomeSlots = 3;
    private int supporterHomeSlots = 10;
    private int defaultHomeCooldownSec = 300;
    private int supporterHomeCooldownSec = 60;

    /**
     * Permission nodes granted to a supporter for as long as they are entitled.
     *
     * <p>Written to LuckPerms as <b>temporary</b> nodes expiring at the end of the grace window,
     * so LuckPerms removes them itself and nothing has to remember to. Empty this list to turn
     * the whole integration off.
     *
     * <p>The default is the node EliteEssentials reads for the home limit. Add others here as
     * more permission-gated perks appear — no code change needed.
     *
     * <p><b>Changing a node here leaves the old one behind.</b> The sync writes what this list
     * says, and its revoke path clears what this list says — so a node you remove from the list
     * is no longer known to either, and lingers in LuckPerms until its own expiry runs out,
     * roughly a month. That matters for the home limit in particular, because EliteEssentials
     * takes the HIGHEST matching {@code …home.limit.<N>}: dropping supporters from 10 to 7 has
     * no effect at all while the old {@code limit.10} is still there. Unset it from existing
     * supporters by hand — {@code lp user <name> permission unsettemp <old node>}, with
     * {@code unsettemp} rather than {@code unset} because these nodes carry an expiry.
     */
    private List<String> luckPermsNodes = List.of("eliteessentials.command.home.limit.10");

    // --- Phase 4: trails ------------------------------------------------------------------
    private int trailIntervalTicks = 8;
    private int maxConcurrentTrails = 40;

    /**
     * Trail id → the particle effect the server spawns for it.
     *
     * <p>Deliberately configuration rather than code. These are stock Hytale effects (598 of
     * them ship with the server), so no custom art has to be authored and no asset pack has to
     * be bundled — and if one turns out to look wrong or cost too much, it is swapped here
     * without a rebuild.
     */
    private Map<String, String> trails = defaultTrails();

    /**
     * How far away a trail is visible, in blocks.
     *
     * <p>The particle is sent only to players inside this radius. FactionMod uses 64 for its
     * claim borders; trails are smaller and far more frequent, so this is tighter.
     */
    private int trailViewDistance = 40;

    /**
     * How far a player must move since the last emit before another particle is spawned.
     *
     * <p>This is the single most important performance control here. Without it a stationary
     * supporter emits a particle every tick forever, which is both ugly and pure waste — and
     * with a whole server standing in spawn it multiplies.
     */
    private double trailMinMoveDistance = 0.9;

    // --- Phase 5: tokens ------------------------------------------------------------------
    private int tokensPerMonth = 100;

    /**
     * Quest rewards (0.21.0), in tokens: three dailies and one weekly are live at once.
     *
     * <p><b>These numbers change what tokens are.</b> At the defaults, fully-cleared quests
     * pay 150/day + 100/week — roughly 46x the tenure rate — turning the shop from a loyalty
     * ladder into an engagement engine. That is a deliberate choice, and retuning it is one
     * edit here, no rebuild. Setting either to 0 stops advertising a reward but quests still
     * track; the balance stays derived either way.
     */
    private int questDailyReward = 50;
    private int questWeeklyReward = 100;

    /**
     * Trail id → cost in tokens. Absent or 0 means free to every supporter.
     *
     * <p>Prices are configuration so the ladder can be retuned without a rebuild and without
     * touching what anybody already owns — an unlock is recorded with the price paid, so
     * lowering a price later never retroactively charges or refunds.
     */
    private Map<String, Integer> trailCosts = defaultTrailCosts();

    /**
     * Skin name → cost in tokens. Absent or 0 means free.
     *
     * <p>Two tints ship free (gold, silver) and two priced, mirroring the trail ladder: every
     * supporter gets the perk immediately, tenure buys the rest. Costume presets are usually NOT
     * listed here — anything absent falls back to {@link #defaultCostumeCost} if it is a
     * costume, so a preset dropped into costumes/ is priced automatically. List a costume here
     * explicitly (0 to make it free) to override.
     */
    private Map<String, Integer> skinCosts = defaultSkinCosts();

    /**
     * What a costume costs when {@code skinCosts} does not name it. The price of "any preset an
     * admin drops in the folder", so new costumes are never accidentally free.
     */
    private int defaultCostumeCost = 300;

    // --- Phase 6: priority queue ----------------------------------------------------------
    private int reservedSlots = 0;

    // --- Storefront copy, for /supporter info ---------------------------------------------
    //
    // PRICING IS CONFIGURATION AND HAS NO DEFAULT ON PURPOSE. A placeholder price would be
    // advertised to every player the moment the plugin loaded, and a wrong price shown to a
    // paying customer is worse than no price at all. Until these are set, /supporter info says
    // pricing is not configured and tells the player to ask an admin — which is never untrue.

    /** Where to buy, shown verbatim. A store URL, or any instruction you like. */
    private String storeUrl = "";

    /**
     * Price tiers, one per line, shown exactly as written.
     *
     * <p>A free-text list rather than a number and a currency code, so any currency, any tier
     * structure and any wording works without a rebuild — and so a sale can be run by editing
     * one line. Example:
     *
     * <pre>
     * "priceLines": ["30 days - £5", "90 days - £12 (save 20%)"]
     * </pre>
     */
    private List<String> priceLines = List.of();

    public int graceDays() {
        return graceDays;
    }

    public int reconcileHourUtc() {
        return reconcileHourUtc;
    }

    public int renewalNudgeDaysBefore() {
        return renewalNudgeDaysBefore;
    }

    public String databaseFile() {
        return databaseFile;
    }

    public boolean announceNewSupporters() {
        return announceNewSupporters;
    }

    public String tagColorHex() {
        return tagColorHex;
    }

    public List<String> allowedChatColors() {
        return allowedChatColors;
    }

    public int maxTitleLength() {
        return maxTitleLength;
    }

    public List<String> titleBlocklist() {
        return titleBlocklist;
    }

    public List<String> luckPermsNodes() {
        return luckPermsNodes == null ? List.of() : luckPermsNodes;
    }

    public int defaultHomeSlots() {
        return defaultHomeSlots;
    }

    public int supporterHomeSlots() {
        return supporterHomeSlots;
    }

    public int defaultHomeCooldownSec() {
        return defaultHomeCooldownSec;
    }

    public int supporterHomeCooldownSec() {
        return supporterHomeCooldownSec;
    }

    /** Unmodifiable view: trail id → particle effect name. */
    public Map<String, String> trails() {
        return trails == null ? Map.of() : Collections.unmodifiableMap(trails);
    }

    public int trailViewDistance() {
        return trailViewDistance;
    }

    public double trailMinMoveDistance() {
        return trailMinMoveDistance;
    }

    public int trailIntervalTicks() {
        return trailIntervalTicks;
    }

    public int maxConcurrentTrails() {
        return maxConcurrentTrails;
    }

    public int tokensPerMonth() {
        return tokensPerMonth;
    }

    public int reservedSlots() {
        return reservedSlots;
    }

    public String storeUrl() {
        return storeUrl == null ? "" : storeUrl.trim();
    }

    public List<String> priceLines() {
        return priceLines == null ? List.of() : priceLines;
    }

    /** True once an admin has filled in either half of the storefront copy. */
    public boolean hasStorefront() {
        return !storeUrl().isEmpty() || !priceLines().isEmpty();
    }

    /** Defaults, for tests and for a first run with no config file present. */
    public static SupporterConfig defaults() {
        return new SupporterConfig();
    }

    /**
     * Loads {@code supporter.json}, writing a default file if it does not exist.
     *
     * @throws IllegalStateException if the file exists but is malformed — a broken config must
     *     stop startup rather than silently reverting somebody's paid grace period to a default
     */
    public static SupporterConfig load(Path file) throws IOException {
        return load(file, message -> { });
    }

    /**
     * As {@link #load(Path)}, reporting through {@code notice} when new keys are written into an
     * existing file.
     */
    public static SupporterConfig load(Path file, Consumer<String> notice) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        if (!Files.exists(file)) {
            SupporterConfig defaults = defaults();
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, gson.toJson(defaults), StandardCharsets.UTF_8);
            return defaults;
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        SupporterConfig loaded;
        try {
            loaded = gson.fromJson(json, SupporterConfig.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("supporter.json is not valid JSON: " + file, e);
        }
        if (loaded == null) {
            throw new IllegalStateException("supporter.json is empty: " + file);
        }
        loaded.validate();
        addMissingKeys(file, json, loaded, gson, notice);
        return loaded;
    }

    /**
     * Writes keys this build knows about but the file does not, leaving everything else alone.
     *
     * <p>Without this, a new option exists only in a changelog, and using it means telling an
     * admin to "add this key to supporter.json" — which invites pasting a fragment over the whole
     * file. That has already happened once on this server: a config was replaced by the snippet
     * meant to be merged into it, and it failed silently, loading zero items with no error.
     *
     * <p>So the merge happens here instead. <b>Only absent keys are added.</b> Existing values are
     * never touched, and neither are keys this build does not recognise, so an admin's own edits
     * and any hand-written notes survive a version bump. The rewrite is best-effort: a config that
     * loaded correctly must not fail startup because the disk is read-only.
     */
    private static void addMissingKeys(
            Path file, String json, SupporterConfig loaded, Gson gson, Consumer<String> notice) {
        try {
            JsonObject onDisk = JsonParser.parseString(json).getAsJsonObject();
            JsonObject full = gson.toJsonTree(loaded).getAsJsonObject();
            List<String> added = new ArrayList<>();
            for (String key : full.keySet()) {
                if (!onDisk.has(key)) {
                    onDisk.add(key, full.get(key));
                    added.add(key);
                }
            }
            if (added.isEmpty()) {
                return;
            }
            Files.writeString(file, gson.toJson(onDisk), StandardCharsets.UTF_8);
            notice.accept("Added " + added.size() + " new key(s) to supporter.json: "
                    + String.join(", ", added));
        } catch (RuntimeException | IOException e) {
            notice.accept("Could not add new keys to supporter.json (" + e.getMessage()
                    + "). The plugin is running on defaults for anything missing.");
        }
    }

    /** Rejects values that would silently corrupt entitlement arithmetic. */
    public void validate() {
        if (graceDays < 0) {
            throw new IllegalStateException("graceDays must be >= 0, got " + graceDays);
        }
        if (reconcileHourUtc < 0 || reconcileHourUtc > 23) {
            throw new IllegalStateException(
                    "reconcileHourUtc must be 0-23, got " + reconcileHourUtc);
        }
        if (renewalNudgeDaysBefore < 0) {
            throw new IllegalStateException(
                    "renewalNudgeDaysBefore must be >= 0, got " + renewalNudgeDaysBefore);
        }
        if (databaseFile == null || databaseFile.isBlank()) {
            throw new IllegalStateException("databaseFile must be set");
        }
        if (trailIntervalTicks < 1) {
            throw new IllegalStateException(
                    "trailIntervalTicks must be >= 1, got " + trailIntervalTicks);
        }
        if (tokensPerMonth < 0) {
            throw new IllegalStateException(
                    "tokensPerMonth must be >= 0, got " + tokensPerMonth);
        }
        backfillTrails();
        backfillTrailCosts();
        backfillSkinCosts();
        backfillGearCosts();
        backfillPetCosts();
    }

    /**
     * Adds any trail this build knows about that the live config has not seen yet.
     *
     * <p><b>Per key, not "if the whole map is empty".</b> A live {@code supporter.json} that
     * already lists three trails would never receive a fourth under a whole-map guard — the map
     * is not empty, so the guard skips, and the new trail silently does not exist on the one
     * server that matters. The same mistake has already been made once on this server in the
     * Jobs plugin.
     *
     * <p>An admin who deliberately renames or removes a trail keeps their change: only ids that
     * are absent are added back.
     */
    /** Cost of a trail in tokens; 0 when free or unpriced. */
    public int trailCost(String trailId) {
        if (trailCosts == null || trailId == null) {
            return 0;
        }
        Integer cost = trailCosts.get(trailId);
        return cost == null || cost < 0 ? 0 : cost;
    }

    public Map<String, Integer> trailCosts() {
        return trailCosts == null ? Map.of() : Collections.unmodifiableMap(trailCosts);
    }

    /**
     * A ladder, not a paywall: two trails are free so every supporter gets the perk
     * immediately, and the rest are something to spend tenure on. At the default 100 tokens a
     * month, the cheapest paid trail is about six weeks of support.
     */
    static Map<String, Integer> defaultTrailCosts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("sparkle", 0);
        out.put("dust", 0);
        out.put("snow", 150);
        out.put("gold", 200);
        out.put("heal", 250);
        out.put("morph", 300);
        out.put("hearts", 150);
        out.put("zzz", 150);
        out.put("rage", 200);
        out.put("toxic", 250);
        out.put("rune", 300);
        out.put("disco", 400); // seven spawners; the flagship trail carries its own weight
        return out;
    }

    private void backfillTrailCosts() {
        if (trailCosts == null) {
            trailCosts = new LinkedHashMap<>();
        } else {
            trailCosts = new LinkedHashMap<>(trailCosts);
        }
        defaultTrailCosts().forEach(trailCosts::putIfAbsent);
    }

    private static Map<String, Integer> defaultSkinCosts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("iron", 150);
        out.put("shadow", 150);
        return out;
    }

    private void backfillSkinCosts() {
        if (skinCosts == null) {
            skinCosts = new LinkedHashMap<>();
        } else {
            skinCosts = new LinkedHashMap<>(skinCosts);
        }
        defaultSkinCosts().forEach(skinCosts::putIfAbsent);
    }

    /**
     * Cost of a skin in tokens.
     *
     * @param isCostume whether the name is a costume preset rather than a tint — costumes not
     *     named in {@code skinCosts} fall back to {@code defaultCostumeCost}, so a preset
     *     dropped into the folder is never accidentally free
     */
    public int skinCost(String name, boolean isCostume) {
        if (name == null) {
            return 0;
        }
        Integer explicit = skinCosts == null ? null : skinCosts.get(name.toLowerCase());
        if (explicit != null) {
            return Math.max(0, explicit);
        }
        return isCostume ? Math.max(0, defaultCostumeCost) : 0;
    }

    private void backfillTrails() {
        if (trails == null) {
            trails = new LinkedHashMap<>();
        } else {
            trails = new LinkedHashMap<>(trails);
        }
        defaultTrails().forEach(trails::putIfAbsent);
    }

    /**
     * Stock Hytale particle effects usable as a trail.
     *
     * <p><b>Every effect here must declare a finite {@code LifeSpan}, and that is not a
     * stylistic preference.</b> Of the 598 particle systems the server ships, <b>540 have no
     * {@code LifeSpan} at all</b> — they are designed to be attached to something persistent
     * like a projectile or a torch, and spawned free-standing they never expire. The first
     * version of this list picked six of those by name, and the live server ended up carpeted
     * in glowing blobs that stayed put.
     *
     * <p>So before adding a trail, check the effect actually expires:
     *
     * <pre>
     * unzip -p Assets.zip Server/Particles/&lt;path&gt;.particlesystem | grep LifeSpan
     * </pre>
     *
     * <p>No {@code LifeSpan} line means do not use it. Prefer few {@code SpawnerId} entries too
     * — this is emitted continuously, so a seven-spawner explosion costs seven times a
     * one-spawner puff.
     */
    static Map<String, String> defaultTrails() {
        Map<String, String> out = new LinkedHashMap<>();
        // name              effect                      life   spawners
        out.put("sparkle", "Potion_Signature_Burst");  // 0.6s   1
        out.put("gold", "Potion_Stamina_Burst");       // 2.0s   1
        out.put("heal", "Potion_Health_Implosion");    // 2.0s   2
        out.put("morph", "Potion_Morph_Burst");        // 2.0s   2
        out.put("snow", "Block_Hit_Snow");             // 0.17s  2
        out.put("dust", "Block_Hit_Mud");              // 0.17s  2
        // The 0.20.0 restock, all re-verified against the finite-LifeSpan scan. Disco is the
        // one deliberate exception to "prefer few spawners" — seven of them, which is why it
        // costs what it costs and there is exactly one of it.
        out.put("hearts", "Hearts");                   // 3.0s   1
        out.put("zzz", "Sleepy");                      // 5.0s   1
        out.put("rage", "Angry");                      // 3.0s   2
        out.put("toxic", "Status_Poisoned");           // 5.0s   3
        out.put("rune", "Memory_Catch_Rune");          // 5.0s   2
        out.put("disco", "Dance_Lights2");             // 2.0s   7
        return out;
    }

    // --- 0.20.0: priced gear --------------------------------------------------------------

    /**
     * Wearable design name → cost in tokens. Absent or 0 means free.
     *
     * <p>Only the 0.20.0 additions are listed; everything that shipped free before stays free
     * by not being listed — pricing must never take away something players already had, the
     * same grandfathering rule the skins follow. Unlike costumes there is no default-cost
     * fallback here: gear only enters the catalogue through a build, so a new item's price is
     * a deliberate line in this map, not an accident.
     */
    private Map<String, Integer> gearCosts = defaultGearCosts();

    static Map<String, Integer> defaultGearCosts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("black", 100);   // the seventh cape — the first paid one
        out.put("beanie", 100);
        out.put("top-hat", 150); // was "top" until 0.21.4; a stale "top" key in a live config
                                 // prices nothing and is harmless
        out.put("wizard", 200);
        return out;
    }

    /**
     * Pet name → cost in tokens; absent or 0 means free. Chick and piglet ship free so every
     * supporter has a companion immediately — the trails/tints shape — and the rest are the
     * premium ladder up to the 450 flagships. Own {@code pet:} unlock namespace; same
     * grandfathering rule as everything else: the tick re-spawns a stored choice without
     * re-checking ownership.
     */
    private Map<String, Integer> petCosts = defaultPetCosts();

    /**
     * Server-wide cap on live pets, the trails' maxConcurrentTrails lesson applied to NPCs:
     * cost must not scale with sales. Selection past the cap stays stored and spawns when
     * room frees up.
     */
    private int maxConcurrentPets = 20;

    static Map<String, Integer> defaultPetCosts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("bunny", 200);
        out.put("fox", 450);
        out.put("penguin", 450);
        out.put("squirrel", 250);
        out.put("mouse", 250);
        out.put("frog", 300);
        out.put("duck", 300);
        out.put("meerkat", 400);
        out.put("tortoise", 400);
        out.put("chick", 0);   // the free pair — a supporter's first companion
        out.put("piglet", 0);
        out.put("rat", 250);
        out.put("gecko", 300);
        out.put("lamb", 350);
        out.put("goat", 350);
        return out;
    }

    private void backfillPetCosts() {
        if (petCosts == null) {
            petCosts = new LinkedHashMap<>();
        } else {
            petCosts = new LinkedHashMap<>(petCosts);
        }
        defaultPetCosts().forEach(petCosts::putIfAbsent);
    }

    /** Cost of a pet in tokens. Anything not listed is free — which no shipped pet is. */
    public int petCost(String name) {
        if (name == null || petCosts == null) {
            return 0;
        }
        Integer cost = petCosts.get(name.toLowerCase());
        return cost == null ? 0 : Math.max(0, cost);
    }

    public int maxConcurrentPets() {
        return Math.max(0, maxConcurrentPets);
    }

    private void backfillGearCosts() {
        if (gearCosts == null) {
            gearCosts = new LinkedHashMap<>();
        } else {
            gearCosts = new LinkedHashMap<>(gearCosts);
        }
        defaultGearCosts().forEach(gearCosts::putIfAbsent);
    }

    public int questDailyReward() {
        return Math.max(0, questDailyReward);
    }

    public int questWeeklyReward() {
        return Math.max(0, questWeeklyReward);
    }

    /** Cost of a wearable in tokens. Anything not listed is free. */
    public int gearCost(String name) {
        if (name == null || gearCosts == null) {
            return 0;
        }
        Integer cost = gearCosts.get(name.toLowerCase());
        return cost == null ? 0 : Math.max(0, cost);
    }

    public Map<String, Integer> gearCosts() {
        return gearCosts == null ? Map.of() : Collections.unmodifiableMap(gearCosts);
    }
}
