package io.github.signalbot.persistence;

import io.github.signalbot.application.trigger.TrailingLevels;
import io.github.signalbot.application.trigger.TrailingSnapshot;
import io.github.signalbot.persistence.PositionGuardStore.StoredGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs in CI (needs Docker; skipped without it, and CI fails on a skip). SB-033 (#30): a trailing state carrying the
 * levels of an earlier guard is not written over a row re-armed with new levels and reset by a fill.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(PaperWalletIntegrationTest.FakeFeedConfig.class)
class TrailingStaleWriteIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SEC = "STALETEST";
    private static final TrailingLevels OLD = new TrailingLevels(bd("108"), bd("2"), bd("0.2"));
    private static final TrailingLevels NEW = new TrailingLevels(bd("55"), bd("1.5"), bd("0.15"));

    @Autowired
    PositionGuardStore store;

    @AfterEach
    void cleanUp() {
        store.delete(SEC);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private StoredGuard read() {
        return store.findAllGuards().stream().filter(g -> SEC.equals(g.secCode())).findFirst().orElseThrow();
    }

    private static void assertSameValue(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void aStateWithTheRowsLevelsIsWritten() {
        store.upsert(SEC, bd("96"), null);
        store.saveTrailing(SEC, OLD, bd("10000"));

        assertTrue(store.saveTrailingState(SEC, new TrailingSnapshot(OLD, bd("112"), true)));

        StoredGuard row = read();
        assertSameValue("112", row.best());
        assertTrue(row.activated());
    }

    @Test
    void aStaleStateIsNotWrittenOverARearmedGuard() {
        store.upsert(SEC, bd("96"), null);
        store.saveTrailing(SEC, OLD, bd("10000"));
        TrailingSnapshot stale = new TrailingSnapshot(OLD, bd("112"), true);

        // A new BUY fill re-arms the row with new levels and resets its state.
        store.upsert(SEC, bd("50"), null);
        store.saveTrailing(SEC, NEW, bd("5000"));

        assertFalse(store.saveTrailingState(SEC, stale), "0 rows updated");

        StoredGuard row = read();
        assertSameValue("55", row.trailing().activation());
        assertSameValue("1.5", row.trailing().offset());
        assertNull(row.best());
        assertFalse(row.activated());
    }

    @Test
    void levelsWithMoreDecimalsThanTheColumnsStillMatchTheirOwnRow() {
        TrailingLevels precise = new TrailingLevels(bd("108.12345678"), bd("2.00000049"), bd("0.2"));
        store.upsert(SEC, bd("96"), null);
        store.saveTrailing(SEC, precise, bd("10000"));

        assertTrue(store.saveTrailingState(SEC, new TrailingSnapshot(precise, bd("110"), true)));
        assertSameValue("110", read().best());
    }

    @Test
    void aStateForAMissingRowUpdatesNothing() {
        assertFalse(store.saveTrailingState(SEC, new TrailingSnapshot(OLD, bd("112"), true)));
    }
}
