package com.peoplesserver.supportermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.core.TestSupport.FakeDirectory;
import com.peoplesserver.supportermod.core.TestSupport.MutableClock;
import com.peoplesserver.supportermod.core.TestSupport.RecordingMessenger;
import com.peoplesserver.supportermod.platform.PluginLog;
import com.peoplesserver.supportermod.storage.SupporterStorage;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 2: chat identity — title and colour, their validation, and their persistence. */
class SupporterIdentityTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir Path tempDir;

    private MutableClock clock;
    private SupporterConfig config;
    private SupporterStorage storage;
    private SupporterService service;

    @BeforeEach
    void setUp() throws SQLException {
        clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        config = SupporterConfig.defaults();
        storage = SupporterStorage.open(tempDir.resolve("supporter.db"));
        service = newService();
        service.grant(ALICE, "Alice", 30, "test", null);
    }

    private SupporterService newService() {
        FakeDirectory directory = new FakeDirectory();
        directory.register("Alice", ALICE);
        return new SupporterService(
                storage, config, clock, directory, new RecordingMessenger(), PluginLog.console());
    }

    @AfterEach
    void tearDown() throws SQLException {
        storage.close();
    }

    @Test
    @DisplayName("a player with no identity reads as empty rather than null")
    void defaultsToEmpty() {
        SupporterIdentity identity = service.identity(ALICE);
        assertTrue(identity.isEmpty());
        assertFalse(identity.hasTitle());
        assertFalse(identity.hasColor());
    }

    @Test
    @DisplayName("title and colour are set independently")
    void setsIndependently() {
        service.setTitle(ALICE, "Founder");
        assertEquals("Founder", service.identity(ALICE).title());
        assertFalse(service.identity(ALICE).hasColor());

        service.setChatColor(ALICE, "#55FFFF");
        assertEquals("Founder", service.identity(ALICE).title(), "colour must not clear title");
        assertEquals("#55FFFF", service.identity(ALICE).chatColor());
    }

    @Test
    @DisplayName("identity survives a restart")
    void survivesRestart() {
        service.setTitle(ALICE, "Founder");
        service.setChatColor(ALICE, "#55FFFF");

        // A fresh service on the same database, as if the server had restarted. This is the
        // check that would have caught the identity living only in the cache.
        SupporterService restarted = newService();
        assertEquals("Founder", restarted.identity(ALICE).title());
        assertEquals("#55FFFF", restarted.identity(ALICE).chatColor());
    }

    @Test
    @DisplayName("a blank title clears rather than storing whitespace")
    void blankClears() {
        service.setTitle(ALICE, "Founder");
        service.setTitle(ALICE, "   ");
        assertNull(service.identity(ALICE).title());
    }

    @Test
    @DisplayName("titles longer than the configured cap are rejected")
    void rejectsLongTitles() {
        String tooLong = "x".repeat(config.maxTitleLength() + 1);
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> service.setTitle(ALICE, tooLong));
        assertTrue(e.getMessage().contains(String.valueOf(config.maxTitleLength())));
        assertFalse(service.identity(ALICE).hasTitle(), "a rejected title must not be stored");
    }

    @Test
    @DisplayName("control characters are rejected, so a title cannot break the chat line")
    void rejectsControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> service.setTitle(ALICE, "Found\ner"));
    }

    @Test
    @DisplayName("blocked words are rejected case-insensitively, without naming the match")
    void rejectsBlockedWords() throws Exception {
        config = SupporterConfig.defaults();
        setBlocklist(config, java.util.List.of("badword"));
        service = newService();

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> service.setTitle(ALICE, "The BadWord Lord"));
        // The message must not echo the matched word: that turns the blocklist into an oracle
        // somebody can probe to recover the whole list.
        assertFalse(e.getMessage().toLowerCase().contains("badword"));
    }

    @Test
    @DisplayName("only colours on the allowed list are accepted")
    void rejectsUnlistedColour() {
        assertThrows(IllegalArgumentException.class, () -> service.setChatColor(ALICE, "#000000"));
        assertFalse(service.identity(ALICE).hasColor());
    }

    @Test
    @DisplayName("an allowed colour is normalised to its configured spelling")
    void normalisesColourCase() {
        service.setChatColor(ALICE, "#55ffff");
        assertEquals("#55FFFF", service.identity(ALICE).chatColor(),
                "stored value should match the config, not the player's casing");
    }

    @Test
    @DisplayName("identity outlives the entitlement that paid for it")
    void survivesRevoke() {
        service.setTitle(ALICE, "Founder");
        service.revoke(ALICE, "test");

        assertFalse(service.isSupporter(ALICE), "entitlement should be gone");
        assertEquals("Founder", service.identity(ALICE).title(),
                "title must be kept so renewing restores it — never delete what was paid for");
    }

    /** The config has no setter for the blocklist; tests need one without widening the API. */
    private static void setBlocklist(SupporterConfig config, java.util.List<String> words)
            throws Exception {
        var field = SupporterConfig.class.getDeclaredField("titleBlocklist");
        field.setAccessible(true);
        field.set(config, words);
    }
}
