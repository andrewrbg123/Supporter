package com.peoplesserver.supportermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.core.TestSupport.FakeDirectory;
import com.peoplesserver.supportermod.core.TestSupport.MutableClock;
import com.peoplesserver.supportermod.core.TestSupport.RecordingMessenger;
import com.peoplesserver.supportermod.platform.PermissionSync;
import com.peoplesserver.supportermod.platform.PluginLog;
import com.peoplesserver.supportermod.storage.SupporterStorage;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The permissions handoff.
 *
 * <p>Homes are the one perk SupporterMod does not gate itself — EliteEssentials owns them and
 * reads LuckPerms. So the thing worth testing is not LuckPerms itself but that entitlement
 * changes actually reach the port, in the right direction, at every point where they can change.
 * A missed revoke is somebody keeping a perk they stopped paying for; a missed grant is somebody
 * not getting what they bought.
 */
class PermissionSyncTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    /** Records what would have been written to the permissions plugin. */
    private static final class RecordingSync implements PermissionSync {
        record Call(String kind, UUID uuid, long expiresAt) {}

        final List<Call> calls = new ArrayList<>();

        @Override
        public void grant(UUID uuid, String username, long expiresAtMs) {
            calls.add(new Call("grant", uuid, expiresAtMs));
        }

        @Override
        public void revoke(UUID uuid, String username) {
            calls.add(new Call("revoke", uuid, 0L));
        }

        String lastKind() {
            return calls.isEmpty() ? "none" : calls.get(calls.size() - 1).kind();
        }
    }

    @TempDir Path tempDir;

    private MutableClock clock;
    private SupporterConfig config;
    private SupporterStorage storage;
    private RecordingSync sync;
    private SupporterService service;

    @BeforeEach
    void setUp() throws SQLException {
        clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        config = SupporterConfig.defaults();
        storage = SupporterStorage.open(tempDir.resolve("supporter.db"));
        sync = new RecordingSync();
        FakeDirectory directory = new FakeDirectory();
        directory.register("Alice", ALICE);
        service = new SupporterService(storage, config, clock, directory,
                new RecordingMessenger(), PluginLog.console(), sync);
    }

    @AfterEach
    void tearDown() throws SQLException {
        storage.close();
    }

    @Test
    @DisplayName("a brand-new supporter is announced once, and a renewal is not")
    void announcesOnlyOnce() {
        RecordingMessenger msgr = new RecordingMessenger();
        FakeDirectory dir = new FakeDirectory();
        dir.register("Alice", ALICE);
        SupporterService svc = new SupporterService(
                storage, config, clock, dir, msgr, PluginLog.console(), sync);

        svc.grant(ALICE, "Alice", 30, "test", null);
        assertEquals(1, msgr.broadcasts.size(), "a first grant should announce");

        svc.grant(ALICE, "Alice", 30, "test", null);
        assertEquals(1, msgr.broadcasts.size(),
                "a renewal must not announce again — monthly renewals would be twelve a year");

        assertTrue(msgr.broadcasts.get(0).contains("Alice"));
    }

    @Test
    @DisplayName("a duplicate payment delivery does not announce twice")
    void duplicateDeliveryDoesNotAnnounce() {
        RecordingMessenger msgr = new RecordingMessenger();
        FakeDirectory dir = new FakeDirectory();
        dir.register("Alice", ALICE);
        SupporterService svc = new SupporterService(
                storage, config, clock, dir, msgr, PluginLog.console(), sync);

        svc.grant(ALICE, "Alice", 30, "tebex", "txn-9");
        svc.grant(ALICE, "Alice", 30, "tebex", "txn-9");   // provider retry

        assertEquals(1, msgr.broadcasts.size(),
                "Tebex retries until acknowledged; the retry must not re-announce");
    }

    @Test
    @DisplayName("a grant pushes permissions out")
    void grantSyncs() {
        service.grant(ALICE, "Alice", 30, "test", null);
        assertEquals("grant", sync.lastKind());
    }

    @Test
    @DisplayName("permissions expire with the grace window, not with the paid period")
    void expiryMatchesGraceWindow() {
        service.grant(ALICE, "Alice", 30, "test", null);
        long graceUntil = service.get(ALICE).orElseThrow().graceUntil();

        RecordingSync.Call last = sync.calls.get(sync.calls.size() - 1);
        assertEquals(graceUntil, last.expiresAt(),
                "a supporter must not lose their homes before they lose their chat tag");
    }

    @Test
    @DisplayName("a revoke pushes a removal out")
    void revokeSyncs() {
        service.grant(ALICE, "Alice", 30, "test", null);
        service.revoke(ALICE, "test");
        assertEquals("revoke", sync.lastKind());
    }

    @Test
    @DisplayName("a chargeback pushes a removal out")
    void chargebackSyncs() {
        service.grant(ALICE, "Alice", 30, "test", null);
        service.chargeback(ALICE, 30, "admin");
        assertEquals("revoke", sync.lastKind());
    }

    @Test
    @DisplayName("the nightly reconcile removes permissions when the grace window closes")
    void reconcileSyncs() {
        service.grant(ALICE, "Alice", 30, "test", null);
        clock.advance(Duration.ofDays(30 + config.graceDays() + 1));

        service.reconcile();

        assertEquals("revoke", sync.lastKind(),
                "expiry must reach the permissions plugin, not just our own table");
    }

    @Test
    @DisplayName("login re-asserts permissions, so a lost node self-heals")
    void loginReasserts() {
        service.grant(ALICE, "Alice", 30, "test", null);
        sync.calls.clear();

        service.onLogin(ALICE, "Alice");

        assertEquals("grant", sync.lastKind());
    }

    @Test
    @DisplayName("a grant queued for an unknown player syncs when they first log in")
    void queuedGrantSyncsOnLogin() {
        // No UUID exists for this name yet, so nothing can be written to LuckPerms at purchase
        // time — this is the Tebex gift case.
        service.grantByUsername("Newcomer", 30, "tebex", "txn-1");
        assertTrue(sync.calls.isEmpty(), "nothing to sync before the player exists");

        UUID newcomer = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
        service.onLogin(newcomer, "Newcomer");

        assertEquals("grant", sync.lastKind());
    }
}
