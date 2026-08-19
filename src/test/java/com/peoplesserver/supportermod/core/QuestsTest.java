package com.peoplesserver.supportermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.core.SupporterService.QuestClaimResult;
import com.peoplesserver.supportermod.core.SupporterService.QuestState;
import com.peoplesserver.supportermod.core.TestSupport.FakeDirectory;
import com.peoplesserver.supportermod.core.TestSupport.MutableClock;
import com.peoplesserver.supportermod.core.TestSupport.RecordingMessenger;
import com.peoplesserver.supportermod.platform.PluginLog;
import com.peoplesserver.supportermod.storage.SupporterStorage;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * v0.21.0: quests — the deterministic roster, progress, and the claim path that extends the
 * derived balance with a second append-only ledger.
 *
 * <p>The roster is a pure function of the date, so tests that need a specific quest type live
 * on that day SEARCH for a suitable date rather than assuming one — robust against pool edits.
 */
class QuestsTest {

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
        config.validate();
        storage = SupporterStorage.open(tempDir.resolve("supporter.db"));
        FakeDirectory directory = new FakeDirectory();
        directory.register("Alice", ALICE);
        service = new SupporterService(
                storage, config, clock, directory, new RecordingMessenger(), PluginLog.console());
    }

    @AfterEach
    void tearDown() throws SQLException {
        storage.close();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** Moves the clock to noon UTC on {@code date}. */
    private void setDate(LocalDate date) {
        clock.advance(Duration.between(
                clock.instant(), date.atTime(12, 0).toInstant(ZoneOffset.UTC)));
    }

    /** The first date from today whose daily roster contains a quest of {@code unit}. */
    private LocalDate dateWithDaily(Quests.Unit unit) {
        LocalDate d = today();
        for (int i = 0; i < 100; i++, d = d.plusDays(1)) {
            for (Quests.Def def : Quests.dailyRoster(d)) {
                if (def.unit() == unit) {
                    return d;
                }
            }
        }
        return fail("no date within 100 days has a daily " + unit + " quest — pool changed?");
    }

    private Quests.Def dailyOf(LocalDate date, Quests.Unit unit) {
        return Quests.dailyRoster(date).stream().filter(def -> def.unit() == unit)
                .findFirst().orElseThrow();
    }

    private QuestState stateFor(String key) {
        return service.quests(ALICE).stream().filter(q -> q.key().equals(key))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the roster is deterministic - same date, same three dailies, for everyone")
    void rosterIsDeterministic() {
        LocalDate d = LocalDate.of(2026, 8, 19);
        List<Quests.Def> roster = Quests.dailyRoster(d);
        assertEquals(roster, Quests.dailyRoster(d), "a restart must not reroll the day");
        assertEquals(Quests.DAILY_COUNT, roster.size());
        assertEquals(Quests.DAILY_COUNT,
                roster.stream().map(Quests.Def::id).distinct().count());
        assertNotNull(Quests.weeklyQuest(d));
        assertEquals(Quests.weeklyQuest(d), Quests.weeklyQuest(d));
    }

    @Test
    @DisplayName("progress accumulates and caps at the target")
    void progressCapsAtTarget() {
        service.grant(ALICE, "Alice", 30, "test", null);
        setDate(dateWithDaily(Quests.Unit.MINUTES));
        Quests.Def def = dailyOf(today(), Quests.Unit.MINUTES);
        String key = Quests.dailyKey(today(), def);

        service.recordQuestProgress(ALICE, Quests.Unit.MINUTES, 5);
        assertEquals(5, stateFor(key).progress());

        service.recordQuestProgress(ALICE, Quests.Unit.MINUTES, def.target() * 3);
        assertEquals(def.target(), stateFor(key).progress(), "progress never overshoots");
        assertTrue(stateFor(key).claimable());
    }

    @Test
    @DisplayName("claiming pays once: the grant ledger refuses a double credit")
    void claimPaysOnce() {
        service.grant(ALICE, "Alice", 30, "test", null);
        setDate(dateWithDaily(Quests.Unit.MINUTES));
        Quests.Def def = dailyOf(today(), Quests.Unit.MINUTES);
        String key = Quests.dailyKey(today(), def);

        assertEquals(QuestClaimResult.NOT_DONE, service.claimQuest(ALICE, key),
                "an unfinished quest pays nothing");

        service.recordQuestProgress(ALICE, Quests.Unit.MINUTES, def.target());
        int before = service.tokenBalance(ALICE);
        assertEquals(QuestClaimResult.CLAIMED, service.claimQuest(ALICE, key));
        assertEquals(before + config.questDailyReward(), service.tokenBalance(ALICE));

        assertEquals(QuestClaimResult.ALREADY_CLAIMED, service.claimQuest(ALICE, key));
        assertEquals(before + config.questDailyReward(), service.tokenBalance(ALICE),
                "a second claim must not credit again");
    }

    @Test
    @DisplayName("a non-supporter earns no progress and cannot claim")
    void nonSupporterEarnsNothing() {
        setDate(dateWithDaily(Quests.Unit.MINUTES));
        Quests.Def def = dailyOf(today(), Quests.Unit.MINUTES);
        String key = Quests.dailyKey(today(), def);

        service.recordQuestProgress(ALICE, Quests.Unit.MINUTES, def.target());
        assertEquals(0, stateFor(key).progress(), "progress for a non-supporter is not recorded");
        assertEquals(QuestClaimResult.NOT_SUPPORTER, service.claimQuest(ALICE, key));
    }

    @Test
    @DisplayName("a claim for a quest that is not on today's roster is refused")
    void expiredQuestIsRefused() {
        service.grant(ALICE, "Alice", 30, "test", null);
        assertEquals(QuestClaimResult.UNKNOWN_QUEST,
                service.claimQuest(ALICE, "d:2020-01-01:play30"));
    }

    @Test
    @DisplayName("a chargeback removes tenure tokens but keeps quest earnings")
    void chargebackKeepsQuestTokens() {
        service.grant(ALICE, "Alice", 30, "test", null);
        setDate(dateWithDaily(Quests.Unit.MINUTES));
        Quests.Def def = dailyOf(today(), Quests.Unit.MINUTES);
        service.recordQuestProgress(ALICE, Quests.Unit.MINUTES, def.target());
        service.claimQuest(ALICE, Quests.dailyKey(today(), def));

        service.chargeback(ALICE, 3650, "admin"); // wipe all tenure

        assertEquals(config.questDailyReward(), service.tokenBalance(ALICE),
                "quest tokens were earned by playing, not by the refunded payment");
    }

    @Test
    @DisplayName("a day is counted once, and the next day counts again")
    void dayCountedOnce() {
        // Needs a date whose WEEKLY quest counts days AND whose next day is the same ISO week.
        LocalDate d = today();
        LocalDate found = null;
        for (int i = 0; i < 200 && found == null; i++, d = d.plusDays(1)) {
            if (Quests.weeklyQuest(d).unit() == Quests.Unit.DAYS
                    && Quests.weekKey(d).equals(Quests.weekKey(d.plusDays(1)))) {
                found = d;
            }
        }
        assertNotNull(found, "no day-counting weekly within 200 days — pool changed?");
        service.grant(ALICE, "Alice", 30, "test", null);
        setDate(found);
        Quests.Def weekly = Quests.weeklyQuest(found);
        String key = Quests.weeklyKey(found, weekly);

        service.markQuestDay(ALICE);
        service.markQuestDay(ALICE);
        assertEquals(1, stateFor(key).progress(), "the same day must count once");

        // Next day, same ISO week: the same weekly quest is still live, and counts again.
        setDate(found.plusDays(1));
        service.markQuestDay(ALICE);
        assertEquals(2, stateFor(key).progress(), "a new day must count exactly once more");
    }
}
