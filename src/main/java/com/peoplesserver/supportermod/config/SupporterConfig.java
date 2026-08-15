package com.peoplesserver.supportermod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    }
}
