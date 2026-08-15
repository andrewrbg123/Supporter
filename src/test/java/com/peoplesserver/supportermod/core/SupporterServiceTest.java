package com.peoplesserver.supportermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.core.TestSupport.FakeDirectory;
import com.peoplesserver.supportermod.core.TestSupport.MutableClock;
import com.peoplesserver.supportermod.core.TestSupport.RecordingMessenger;
import com.peoplesserver.supportermod.platform.PluginLog;
import com.peoplesserver.supportermod.storage.SupporterStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the Phase 1 verification list from the build prompt, plus the payment-retry case the
 * prompt does not mention but which decides whether a purchase can be delivered twice.
 */
class SupporterServiceTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @TempDir Path tempDir;

    private MutableClock clock;
    private FakeDirectory directory;
    private RecordingMessenger messenger;
    private SupporterConfig config;
    private SupporterStorage storage;
    private SupporterService service;

    @BeforeEach
    void setUp() throws SQLException {
        clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        directory = new FakeDirectory();
        messenger = new RecordingMessenger();
        config = SupporterConfig.defaults();
        storage = SupporterStorage.open(tempDir.resolve("supporter.db"));
        service = newService();
    }

    private SupporterService newService() {
        return new SupporterService(
                storage, config, clock, directory, messenger, PluginLog.console());
    }

    @AfterEach
    void tearDown() throws SQLException {
        storage.close();
    }

    @Test
    @DisplayName("a fresh grant makes the player a supporter")
    void grantCreates() {
        GrantResult result = service.grant(ALICE, "Alice", 30, "tebex", "txn-1");

        assertEquals(GrantResult.Outcome.CREATED, result.outcome());
        assertTrue(service.isSupporter(ALICE));
        assertEquals(SupporterStatus.ACTIVE, service.status(ALICE));
        assertEquals(30, service.daysRemaining(ALICE));
    }

    @Test
    @DisplayName("two 30 day grants stack to 60 days and 2 months of tenure")
    void grantsStack() {
        service.grant(ALICE, "Alice", 30, "tebex", "txn-1");
        service.grant(ALICE, "Alice", 30, "tebex", "txn-2");

        assertEquals(60, service.daysRemaining(ALICE));
        SupporterRecord record = service.get(ALICE).orElseThrow();
        assertEquals(60, record.totalDays());
        assertEquals(2, record.totalMonths());
    }

    @Test
    @DisplayName("renewing extends from the existing expiry, never from now")
    void renewalNeverShortens() {
        service.grant(ALICE, "Alice", 30, "tebex", "txn-1");
        long firstExpiry = service.get(ALICE).orElseThrow().expiresAt();

        // Renew 10 days in, with 20 still on the clock.
        clock.advance(Duration.ofDays(10));
        service.grant(ALICE, "Alice", 30, "tebex", "txn-2");

        SupporterRecord after = service.get(ALICE).orElseThrow();
        assertEquals(firstExpiry + Duration.ofDays(30).toMillis(), after.expiresAt());
        // 20 days left plus 30 bought — not 30 from the moment of renewal.
        assertEquals(50, service.daysRemaining(ALICE));
    }

    @Test
    @DisplayName("a repeated transaction id is ignored")
    void duplicateTransactionIgnored() {
        service.grant(ALICE, "Alice", 30, "tebex", "txn-1");
        GrantResult retry = service.grant(ALICE, "Alice", 30, "tebex", "txn-1");

        assertEquals(GrantResult.Outcome.DUPLICATE_IGNORED, retry.outcome());
        assertEquals(0, retry.daysAdded());
        assertEquals(30, service.daysRemaining(ALICE), "a retry must not add a second month");
    }

    @Test
    @DisplayName("manual grants without a transaction id are not deduplicated")
    void manualGrantsAlwaysApply() {
        service.grant(ALICE, "Alice", 7, "admin", null);
        service.grant(ALICE, "Alice", 7, "admin", null);

        assertEquals(14, service.daysRemaining(ALICE));
    }

    @Test
    @DisplayName("perks continue through the grace window, then stop")
    void gracePeriod() {
        service.grant(ALICE, "Alice", 1, "tebex", "txn-1");

        clock.advance(Duration.ofDays(1).plusHours(1));
        assertEquals(SupporterStatus.GRACE, service.status(ALICE));
        assertTrue(service.isSupporter(ALICE), "grace keeps perks on");
        assertEquals(0, service.daysRemaining(ALICE), "paid days are exhausted in grace");

        clock.advance(Duration.ofDays(config.graceDays()));
        assertFalse(service.isSupporter(ALICE), "entitlement stops when grace closes");
    }

    @Test
    @DisplayName("reconcile expires lapsed supporters and writes a log row")
    void reconcileExpires() throws SQLException {
        service.grant(ALICE, "Alice", 1, "tebex", "txn-1");
        directory.setOnline(ALICE, true);

        clock.advance(Duration.ofDays(1 + config.graceDays() + 1));
        ReconcileReport report = service.reconcile();

        assertEquals(1, report.expiredCount());
        assertFalse(service.isSupporter(ALICE));
        assertEquals(SupporterStatus.EXPIRED, service.status(ALICE));
        assertEquals(1, storage.countLogEntries(ALICE, "EXPIRE"));
        assertTrue(messenger.sentContaining("expired"), "an online player is told");
    }

    @Test
    @DisplayName("reconcile leaves supporters inside their grace window alone")
    void reconcileSparesGrace() {
        service.grant(ALICE, "Alice", 1, "tebex", "txn-1");

        clock.advance(Duration.ofDays(1).plusHours(1));
        ReconcileReport report = service.reconcile();

        assertEquals(0, report.expiredCount());
        assertTrue(service.isSupporter(ALICE));
    }

    @Test
    @DisplayName("reconcile is idempotent")
    void reconcileTwiceExpiresOnce() throws SQLException {
        service.grant(ALICE, "Alice", 1, "tebex", "txn-1");
        clock.advance(Duration.ofDays(10));

        service.reconcile();
        ReconcileReport second = service.reconcile();

        assertEquals(0, second.expiredCount());
        assertEquals(1, storage.countLogEntries(ALICE, "EXPIRE"));
    }

    @Test
    @DisplayName("a grant for an unknown username is queued and applied on login")
    void offlineGrantResolvesOnLogin() {
        GrantResult queued = service.grantByUsername("Newbie", 30, "tebex", "txn-1");
        assertEquals(GrantResult.Outcome.QUEUED_PENDING, queued.outcome());
        assertFalse(service.isSupporter(BOB));

        SupporterService.LoginResult login = service.onLogin(BOB, "Newbie");

        assertEquals(30, login.claimedDays());
        assertTrue(service.isSupporter(BOB));
        assertEquals(30, service.daysRemaining(BOB));
    }

    @Test
    @DisplayName("a queued grant is claimed only once")
    void queuedGrantClaimedOnce() {
        service.grantByUsername("Newbie", 30, "tebex", "txn-1");

        service.onLogin(BOB, "Newbie");
        service.onLogin(BOB, "Newbie");

        assertEquals(30, service.daysRemaining(BOB), "a second login must not re-apply it");
    }

    @Test
    @DisplayName("a grant by username applies straight away when the player is known")
    void knownUsernameGrantsImmediately() {
        directory.register("Alice", ALICE);

        GrantResult result = service.grantByUsername("Alice", 30, "tebex", "txn-1");

        assertEquals(GrantResult.Outcome.CREATED, result.outcome());
        assertTrue(service.isSupporter(ALICE));
    }

    @Test
    @DisplayName("a lapsed supporter renewing offline is matched by their stored name")
    void lapsedSupporterResolvesWithoutDirectory() {
        service.grant(ALICE, "Alice", 1, "tebex", "txn-1");
        clock.advance(Duration.ofDays(30));
        service.reconcile();

        // The directory cannot resolve them, but we already hold their record.
        GrantResult renewal = service.grantByUsername("alice", 30, "tebex", "txn-2");

        assertEquals(GrantResult.Outcome.EXTENDED, renewal.outcome());
        assertTrue(service.isSupporter(ALICE));
    }

    @Test
    @DisplayName("revoke ends entitlement but keeps tenure")
    void revokeKeepsHistory() throws SQLException {
        service.grant(ALICE, "Alice", 60, "tebex", "txn-1");

        service.revoke(ALICE, "chargeback");

        assertFalse(service.isSupporter(ALICE));
        assertEquals(SupporterStatus.EXPIRED, service.status(ALICE));
        assertEquals(60, service.get(ALICE).orElseThrow().totalDays(), "tenure is history");
        assertEquals(1, storage.countLogEntries(ALICE, "REVOKE"));
    }

    @Test
    @DisplayName("a revoked player can be granted again")
    void regrantAfterRevoke() {
        service.grant(ALICE, "Alice", 60, "tebex", "txn-1");
        service.revoke(ALICE, "chargeback");

        service.grant(ALICE, "Alice", 30, "tebex", "txn-2");

        assertTrue(service.isSupporter(ALICE));
        assertEquals(30, service.daysRemaining(ALICE), "time restarts from now, not the old expiry");
    }

    @Test
    @DisplayName("state survives a restart")
    void statePersists() throws SQLException {
        service.grant(ALICE, "Alice", 30, "tebex", "txn-1");
        storage.close();

        storage = SupporterStorage.open(tempDir.resolve("supporter.db"));
        service = newService();

        assertTrue(service.isSupporter(ALICE));
        assertEquals(30, service.daysRemaining(ALICE));
        assertEquals(GrantResult.Outcome.DUPLICATE_IGNORED,
                service.grant(ALICE, "Alice", 30, "tebex", "txn-1").outcome(),
                "the transaction ledger survives too");
    }

    @Test
    @DisplayName("login nudges a supporter close to expiry")
    void nudgesNearExpiry() {
        service.grant(ALICE, "Alice", 30, "tebex", "txn-1");
        clock.advance(Duration.ofDays(27));

        SupporterService.LoginResult result = service.onLogin(ALICE, "Alice");

        assertEquals(SupporterService.Nudge.RENEWAL_SOON, result.nudge());
        assertTrue(messenger.sentContaining("expires in"));
    }

    @Test
    @DisplayName("login nudges a supporter inside the grace window")
    void nudgesInGrace() {
        service.grant(ALICE, "Alice", 1, "tebex", "txn-1");
        clock.advance(Duration.ofDays(1).plusHours(1));

        SupporterService.LoginResult result = service.onLogin(ALICE, "Alice");

        assertEquals(SupporterService.Nudge.GRACE, result.nudge());
        assertTrue(messenger.sentContaining("lapsed"));
    }

    @Test
    @DisplayName("login does not nudge a supporter with plenty of time")
    void noNudgeWhenHealthy() {
        service.grant(ALICE, "Alice", 30, "tebex", "txn-1");

        assertEquals(SupporterService.Nudge.NONE, service.onLogin(ALICE, "Alice").nudge());
    }

    @Test
    @DisplayName("a rename updates the stored username")
    void renameTracked() {
        service.grant(ALICE, "Alice", 30, "tebex", "txn-1");

        service.onLogin(ALICE, "AliceRenamed");

        assertEquals("AliceRenamed", service.get(ALICE).orElseThrow().username());
    }

    @Test
    @DisplayName("non-supporters are cheap to check and report nothing")
    void unknownPlayer() {
        assertFalse(service.isSupporter(BOB));
        assertEquals(SupporterStatus.NONE, service.status(BOB));
        assertEquals(0, service.daysRemaining(BOB));
        assertTrue(service.get(BOB).isEmpty());
    }

    @Test
    @DisplayName("a zero or negative grant is rejected")
    void rejectsNonPositiveDays() {
        assertThrows(IllegalArgumentException.class,
                () -> service.grant(ALICE, "Alice", 0, "admin", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.grant(ALICE, "Alice", -5, "admin", null));
    }

    @Test
    @DisplayName("the leaderboard is ordered by tenure")
    void leaderboardOrder() {
        service.grant(ALICE, "Alice", 30, "tebex", "a1");
        service.grant(BOB, "Bob", 90, "tebex", "b1");

        var ranked = service.activeByTenure();

        assertEquals(2, ranked.size());
        assertEquals(BOB, ranked.get(0).uuid());
        assertEquals(3, ranked.get(0).totalMonths());
    }

    @Test
    @DisplayName("a broken config file stops startup instead of silently defaulting")
    void badConfigFails() throws IOException {
        Path bad = tempDir.resolve("supporter.json");
        java.nio.file.Files.writeString(bad, "{ not json");

        assertThrows(IllegalStateException.class, () -> SupporterConfig.load(bad));
    }

    @Test
    @DisplayName("a missing config file is written with defaults")
    void writesDefaultConfig() throws IOException {
        Path file = tempDir.resolve("nested/supporter.json");

        SupporterConfig loaded = SupporterConfig.load(file);

        assertTrue(java.nio.file.Files.exists(file));
        assertEquals(SupporterConfig.defaults().graceDays(), loaded.graceDays());
    }
}
