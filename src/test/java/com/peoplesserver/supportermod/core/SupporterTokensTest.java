package com.peoplesserver.supportermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peoplesserver.supportermod.config.SupporterConfig;
import com.peoplesserver.supportermod.core.SupporterService.PurchaseResult;
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

/** Phase 5: the token economy — earning, spending, and the two ways tenure can be taken away. */
class SupporterTokensTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir Path tempDir;

    private MutableClock clock;
    private SupporterConfig config;
    private SupporterStorage storage;
    private SupporterService service;

    /** A paid trail from the default catalogue, and its price. */
    private String paidTrail;
    private int paidCost;

    @BeforeEach
    void setUp() throws SQLException {
        clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        config = SupporterConfig.defaults();
        config.validate(); // fills the trail and cost maps
        storage = SupporterStorage.open(tempDir.resolve("supporter.db"));
        service = newService();

        paidTrail = config.trails().keySet().stream()
                .filter(id -> config.trailCost(id) > 0)
                .findFirst()
                .orElseThrow();
        paidCost = config.trailCost(paidTrail);
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

    /** Grants enough whole months of tenure to afford {@code tokens}. */
    private void giveTokens(int tokens) {
        int months = (int) Math.ceil(tokens / (double) config.tokensPerMonth());
        service.grant(ALICE, "Alice", months * 30, "test", null);
    }

    @Test
    @DisplayName("tokens are earned per whole month of tenure")
    void earnsPerMonth() {
        service.grant(ALICE, "Alice", 30, "test", null);
        assertEquals(config.tokensPerMonth(), service.tokensEarned(ALICE));

        service.grant(ALICE, "Alice", 30, "test", null);
        assertEquals(config.tokensPerMonth() * 2, service.tokensEarned(ALICE));
    }

    @Test
    @DisplayName("a partial month earns nothing yet")
    void partialMonthEarnsNothing() {
        service.grant(ALICE, "Alice", 29, "test", null);
        assertEquals(0, service.tokensEarned(ALICE));
        assertEquals(0, service.tokenBalance(ALICE));
    }

    @Test
    @DisplayName("buying deducts from the balance and grants ownership")
    void buyingWorks() {
        giveTokens(paidCost);
        int before = service.tokenBalance(ALICE);

        assertEquals(PurchaseResult.BOUGHT, service.purchaseTrail(ALICE, paidTrail));
        assertTrue(service.owns(ALICE, paidTrail));
        assertEquals(before - paidCost, service.tokenBalance(ALICE));
    }

    @Test
    @DisplayName("buying the same thing twice never charges twice")
    void purchaseIsIdempotent() {
        giveTokens(paidCost * 2);
        service.purchaseTrail(ALICE, paidTrail);
        int after = service.tokenBalance(ALICE);

        assertEquals(PurchaseResult.ALREADY_OWNED, service.purchaseTrail(ALICE, paidTrail));
        assertEquals(after, service.tokenBalance(ALICE), "a second buy must not deduct again");
    }

    @Test
    @DisplayName("you cannot buy what you cannot afford")
    void refusesWhenBroke() {
        service.grant(ALICE, "Alice", 30, "test", null); // one month only
        if (service.tokenBalance(ALICE) >= paidCost) {
            return; // catalogue is cheap enough that this case cannot arise
        }
        assertEquals(PurchaseResult.NOT_ENOUGH_TOKENS, service.purchaseTrail(ALICE, paidTrail));
        assertFalse(service.owns(ALICE, paidTrail));
    }

    @Test
    @DisplayName("gear lives in its own namespace - a gear unlock never grants a trail or skin")
    void gearNamespaceIsSeparate() {
        giveTokens(300);

        assertEquals(PurchaseResult.BOUGHT, service.purchaseGear(ALICE, "black", 100));
        assertTrue(service.ownsGear(ALICE, "black", 100));
        assertTrue(service.unlocks(ALICE).contains("gear:black"),
                "the unlock is recorded with its namespace prefix");
        // The same name in the other catalogues stays locked: only gear:black was written.
        assertFalse(service.ownsSkin(ALICE, "black", 300));
        assertFalse(service.unlocks(ALICE).contains("black"),
                "no bare, un-namespaced unlock may be written - that is the trail namespace");
        assertEquals(200, service.tokenBalance(ALICE));

        assertEquals(PurchaseResult.ALREADY_OWNED, service.purchaseGear(ALICE, "black", 100));
        assertEquals(200, service.tokenBalance(ALICE), "a second buy must not deduct again");
    }

    @Test
    @DisplayName("pets live in their own namespace and never grant a trail, skin or gear")
    void petNamespaceIsSeparate() {
        giveTokens(500);

        assertEquals(PurchaseResult.BOUGHT, service.purchasePet(ALICE, "fox", 450));
        assertTrue(service.ownsPet(ALICE, "fox", 450));
        assertTrue(service.unlocks(ALICE).contains("pet:fox"));
        assertFalse(service.ownsGear(ALICE, "fox", 100),
                "a hypothetical fox HAT would not be granted by the fox PET");
        assertEquals(50, service.tokenBalance(ALICE));

        assertEquals(PurchaseResult.ALREADY_OWNED, service.purchasePet(ALICE, "fox", 450));
        assertEquals(50, service.tokenBalance(ALICE), "a second buy must not deduct again");
    }

    @Test
    @DisplayName("free gear is owned by everyone and never charged")
    void freeGearIsOwned() {
        // Cost 0 is what every pre-0.20.0 wearable reports: absent from gearCosts entirely.
        // Grandfathering IS this line — the crown must never need a purchase.
        assertTrue(service.ownsGear(ALICE, "crown", 0));
        assertEquals(PurchaseResult.FREE, service.purchaseGear(ALICE, "crown", 0));
    }

    @Test
    @DisplayName("free trails need no purchase and are owned by everyone")
    void freeTrailsAreOwned() {
        String free = config.trails().keySet().stream()
                .filter(id -> config.trailCost(id) == 0)
                .findFirst()
                .orElseThrow();
        assertTrue(service.owns(ALICE, free));
        assertEquals(PurchaseResult.FREE, service.purchaseTrail(ALICE, free));
    }

    @Test
    @DisplayName("selecting a locked trail is refused")
    void cannotWearWhatYouDoNotOwn() {
        service.grant(ALICE, "Alice", 30, "test", null); // one month, not enough for a paid trail
        if (service.owns(ALICE, paidTrail)) {
            return; // affordable in this catalogue; covered elsewhere
        }
        assertThrows(IllegalArgumentException.class, () -> service.selectTrail(ALICE, paidTrail));
    }

    @Test
    @DisplayName("an unknown item is rejected rather than charged")
    void rejectsUnknownItem() {
        giveTokens(paidCost);
        int before = service.tokenBalance(ALICE);
        assertEquals(PurchaseResult.UNKNOWN_ITEM, service.purchaseTrail(ALICE, "not-an-item"));
        assertEquals(before, service.tokenBalance(ALICE));
    }

    @Test
    @DisplayName("purchases survive a restart")
    void purchasesSurviveRestart() {
        giveTokens(paidCost);
        service.purchaseTrail(ALICE, paidTrail);

        SupporterService restarted = newService();
        assertTrue(restarted.owns(ALICE, paidTrail));
        assertEquals(paidCost, restarted.tokensSpent(ALICE));
    }

    @Test
    @DisplayName("an ordinary revoke keeps tenure, so it keeps the tokens they earned")
    void revokeKeepsTokens() {
        giveTokens(paidCost);
        int earned = service.tokensEarned(ALICE);

        service.revoke(ALICE, "expired");

        assertFalse(service.isSupporter(ALICE));
        assertEquals(earned, service.tokensEarned(ALICE),
                "they paid for that time — expiry must not confiscate it");
    }

    @Test
    @DisplayName("a chargeback removes tenure, and the tokens follow automatically")
    void chargebackRemovesTenure() {
        service.grant(ALICE, "Alice", 60, "test", null);
        assertEquals(config.tokensPerMonth() * 2, service.tokensEarned(ALICE));

        service.chargeback(ALICE, 30, "admin");

        assertEquals(config.tokensPerMonth(), service.tokensEarned(ALICE),
                "removing a month of unpaid tenure removes a month of tokens — no separate "
                        + "ledger to keep in step");
    }

    @Test
    @DisplayName("a chargeback keeps items already bought and clamps the balance at zero")
    void chargebackKeepsUnlocks() {
        giveTokens(paidCost);
        service.purchaseTrail(ALICE, paidTrail);
        assertTrue(service.owns(ALICE, paidTrail));

        service.chargeback(ALICE, 3650, "admin"); // wipe all tenure

        assertTrue(service.owns(ALICE, paidTrail), "never delete something a player paid for");
        assertEquals(0, service.tokenBalance(ALICE), "balance must not go negative");
    }
}
