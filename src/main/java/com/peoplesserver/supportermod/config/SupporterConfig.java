package com.peoplesserver.supportermod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private int defaultHomeSlots = 2;
    private int supporterHomeSlots = 5;
    private int defaultHomeCooldownSec = 300;
    private int supporterHomeCooldownSec = 60;

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

    // --- Phase 6: priority queue ----------------------------------------------------------
    private int reservedSlots = 0;

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
        return loaded;
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
        backfillTrails();
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
    private void backfillTrails() {
        if (trails == null) {
            trails = new LinkedHashMap<>();
        } else {
            trails = new LinkedHashMap<>(trails);
        }
        defaultTrails().forEach(trails::putIfAbsent);
    }

    /** Stock Hytale particle effects, chosen to be visually distinct from one another. */
    static Map<String, String> defaultTrails() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("sparkle", "Dust_Sparkles_Fine");
        out.put("ember", "Fire_Charge1");
        out.put("teal", "Fire_Teal");
        out.put("green", "Fire_Green");
        out.put("blue", "Fire_Blue");
        out.put("gem", "Block_Gem_Sparks");
        return out;
    }
}
