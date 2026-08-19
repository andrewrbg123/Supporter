package com.peoplesserver.supportermod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Storefront copy, the part of the config that faces paying players.
 *
 * <p>The case that matters most here is the third one: a {@code supporter.json} written by an
 * earlier version does not contain these keys at all, and the live server has exactly such a
 * file. Adding a key that a live config silently mishandles is the failure this project has
 * already hit once, in a sibling plugin, so it gets a test rather than an assumption.
 */
class SupporterConfigTest {

    @Test
    @DisplayName("a fresh config invents no price")
    void defaultsAdvertiseNothing() {
        SupporterConfig config = SupporterConfig.defaults();

        assertFalse(config.hasStorefront(),
                "a default build must not advertise a price nobody set");
        assertEquals("", config.storeUrl());
        assertTrue(config.priceLines().isEmpty());
    }

    @Test
    @DisplayName("storefront copy is read back exactly as written")
    void storefrontRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("supporter.json");
        Files.writeString(file, """
                {
                  "storeUrl": "  https://store.example/supporter  ",
                  "priceLines": ["30 days - 5 GBP", "90 days - 12 GBP (save 20%)"]
                }
                """, StandardCharsets.UTF_8);

        SupporterConfig config = SupporterConfig.load(file);

        assertTrue(config.hasStorefront());
        // Trimmed, because a trailing space in a pasted URL is invisible in the config and very
        // visible in chat.
        assertEquals("https://store.example/supporter", config.storeUrl());
        assertEquals(2, config.priceLines().size());
        // Verbatim: whatever the admin writes is what players read, including the wording of
        // the currency. Nothing here parses or reformats a price.
        assertEquals("30 days - 5 GBP", config.priceLines().get(0));
    }

    @Test
    @DisplayName("a config file predating these keys still loads, and claims no storefront")
    void olderConfigLoadsWithoutStorefront(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("supporter.json");
        // A real 0.7.0-era file: no storeUrl, no priceLines, and a value the admin changed.
        Files.writeString(file, """
                {
                  "graceDays": 7,
                  "tokensPerMonth": 150
                }
                """, StandardCharsets.UTF_8);

        SupporterConfig config = SupporterConfig.load(file);

        assertEquals(7, config.graceDays(), "the admin's own setting must survive");
        assertEquals(150, config.tokensPerMonth());
        assertFalse(config.hasStorefront());
        assertEquals("", config.storeUrl(), "an absent key must read as empty, never null");
        assertTrue(config.priceLines().isEmpty());
    }

    @Test
    @DisplayName("loading an older file writes the new keys into it, keeping every existing value")
    void missingKeysAreWrittenBack(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("supporter.json");
        Files.writeString(file, """
                {
                  "graceDays": 7,
                  "_note": "hand-written, must survive"
                }
                """, StandardCharsets.UTF_8);
        List<String> notices = new ArrayList<>();

        SupporterConfig.load(file, notices::add);

        String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("\"storeUrl\""), "the new key must appear in the file");
        assertTrue(rewritten.contains("\"priceLines\""));
        assertTrue(rewritten.contains("\"graceDays\": 7"), "an existing value must not be reset");
        assertTrue(rewritten.contains("_note"), "an unrecognised key must not be deleted");
        assertEquals(1, notices.size(), "the admin is told once, in the log");
        assertTrue(notices.get(0).contains("storeUrl"));

        // And the values still load correctly from the file it just wrote.
        SupporterConfig reloaded = SupporterConfig.load(file, notices::add);
        assertEquals(7, reloaded.graceDays());
        assertEquals(1, notices.size(), "a second load has nothing to add and says nothing");
    }

    @Test
    @DisplayName("explicit nulls do not blow up the info command")
    void explicitNullsAreSafe(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("supporter.json");
        Files.writeString(file, """
                {
                  "storeUrl": null,
                  "priceLines": null
                }
                """, StandardCharsets.UTF_8);

        SupporterConfig config = SupporterConfig.load(file);

        assertFalse(config.hasStorefront());
        assertEquals("", config.storeUrl());
        assertTrue(config.priceLines().isEmpty());
    }

    @Test
    @DisplayName("every default trail has a price entry - free is a decision, not an omission")
    void everyTrailIsPriced() {
        // A trail added to defaultTrails without a defaultTrailCosts line would be silently
        // free (trailCost falls back to 0). Free must be an explicit 0 in the ladder.
        assertEquals(SupporterConfig.defaultTrails().keySet(),
                SupporterConfig.defaultTrailCosts().keySet());
    }

    @Test
    @DisplayName("gear prices land in a live config through the per-key backfill")
    void gearCostsBackfillPerKey(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("supporter.json");
        // A 0.19.x-era file: gearCosts absent entirely, trailCosts present but missing the new
        // trails. The whole-map "if empty" guard is the bug this project documents; per key is
        // the rule.
        Files.writeString(file, """
                {
                  "trailCosts": {"sparkle": 0, "dust": 0, "snow": 150,
                                 "gold": 200, "heal": 250, "morph": 999}
                }
                """, StandardCharsets.UTF_8);

        SupporterConfig config = SupporterConfig.load(file);

        assertEquals(100, config.gearCost("black"), "a new map arrives with its defaults");
        assertEquals(200, config.gearCost("wizard"));
        assertEquals(0, config.gearCost("crown"), "unlisted gear is free");
        assertEquals(0, config.gearCost(null));
        assertEquals(999, config.trailCost("morph"), "the admin's own price must survive");
        assertEquals(400, config.trailCost("disco"),
                "a trail this build added must be priced in a live config too");
        assertTrue(config.trails().containsKey("disco"),
                "and the trail itself must exist there");
    }

    @Test
    @DisplayName("quest rewards default to 50 daily / 100 weekly and never go negative")
    void questRewardDefaults(@TempDir Path dir) throws IOException {
        SupporterConfig config = SupporterConfig.defaults();
        config.validate();
        assertEquals(50, config.questDailyReward());
        assertEquals(100, config.questWeeklyReward());

        // An admin setting a negative reward gets 0, not a token sink.
        Path file = dir.resolve("supporter.json");
        Files.writeString(file, """
                {"questDailyReward": -5}
                """, StandardCharsets.UTF_8);
        assertEquals(0, SupporterConfig.load(file).questDailyReward());
    }

    @Test
    @DisplayName("every priced gear name is a real catalogue entry in the command source")
    void gearPricesMatchTheCatalogue() throws IOException {
        // The catalogues live in SupporterCommand (which needs the server jar, absent from the
        // test classpath), so this reads the source the same way ManifestTest reads the
        // manifest. A typo'd key in defaultGearCosts would price nothing, silently - the item
        // it meant to gate would stay free.
        String src = Files.readString(Path.of("src", "main", "java", "com", "peoplesserver",
                "supportermod", "command", "SupporterCommand.java"), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:DESIGNS|HATS|SHOES)\\.put\\(\"([a-z-]+)\"").matcher(src);
        List<String> names = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
        }
        assertFalse(names.isEmpty(), "catalogue scan found nothing - the pattern went stale");
        assertEquals(names.size(), names.stream().distinct().count(),
                "design names must be unique across capes, hats and shoes - they share the "
                        + "gear: unlock namespace: " + names);
        for (String priced : SupporterConfig.defaultGearCosts().keySet()) {
            assertTrue(names.contains(priced),
                    "priced gear '" + priced + "' is not in any catalogue - it would gate nothing");
        }

        // Pets are their own namespace and their own map; same guard, separately.
        java.util.regex.Matcher pm = java.util.regex.Pattern
                .compile("PETS\\.put\\(\"([a-z-]+)\"").matcher(src);
        List<String> petNames = new ArrayList<>();
        while (pm.find()) {
            petNames.add(pm.group(1));
        }
        assertFalse(petNames.isEmpty(), "pet catalogue scan found nothing");
        assertEquals(petNames.size(), petNames.stream().distinct().count(),
                "pet names must be unique: " + petNames);
        for (String priced : SupporterConfig.defaultPetCosts().keySet()) {
            assertTrue(petNames.contains(priced),
                    "priced pet '" + priced + "' is not in the catalogue - it would gate nothing");
        }
    }

    @Test
    @DisplayName("pet prices land in a live config through the per-key backfill")
    void petCostsBackfillPerKey(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("supporter.json");
        Files.writeString(file, """
                {"petCosts": {"bunny": 123}}
                """, StandardCharsets.UTF_8);

        SupporterConfig config = SupporterConfig.load(file);

        assertEquals(123, config.petCost("bunny"), "the admin's own price must survive");
        assertEquals(450, config.petCost("fox"), "new keys arrive per key");
        assertEquals(20, config.maxConcurrentPets());
        assertEquals(0, config.petCost(null));
    }

    @Test
    @DisplayName("a store link alone is enough to show players where to go")
    void urlWithoutPricesStillCounts(@TempDir Path dir) throws IOException {
        // Half-configured is a normal state — an admin who does not want to maintain prices in
        // two places sets only the link. That must still count as a storefront, or players get
        // told pricing does not exist while a perfectly good link sits in the config.
        Path file = dir.resolve("supporter.json");
        Files.writeString(file, """
                {
                  "storeUrl": "https://store.example/supporter"
                }
                """, StandardCharsets.UTF_8);

        SupporterConfig config = SupporterConfig.load(file);

        assertTrue(config.hasStorefront());
        assertTrue(config.priceLines().isEmpty());
    }
}
