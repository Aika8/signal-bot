package io.github.signalbot.persistence;

import io.github.signalbot.application.trigger.TrailingLevels;
import io.github.signalbot.application.trigger.TrailingState;
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

/** Runs in CI (needs Docker; skipped without it, and CI fails on a skip): trailing guard columns round-trip (V7). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(PaperWalletIntegrationTest.FakeFeedConfig.class)
class PositionGuardTrailingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SEC = "TRAILTEST";

    @Autowired
    PositionGuardStore store;

    @AfterEach
    void cleanUp() {
        store.delete(SEC);
    }

    private StoredGuard read() {
        return store.findAllGuards().stream().filter(g -> SEC.equals(g.secCode())).findFirst().orElseThrow();
    }

    private static void assertSameValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void trailingLevelsAndStateReadBack() {
        store.upsert(SEC, new BigDecimal("96"), new BigDecimal("130"));
        store.saveTrailing(SEC, new TrailingLevels(new BigDecimal("108"), new BigDecimal("2"),
                new BigDecimal("0.2")), new BigDecimal("10000.50"));

        StoredGuard armed = read();
        assertSameValue("96", armed.stop());
        assertSameValue("130", armed.takeProfit());
        assertSameValue("108", armed.trailing().activation());
        assertSameValue("2", armed.trailing().offset());
        assertSameValue("0.2", armed.trailing().spread());
        assertSameValue("10000.50", armed.riskKzt());
        assertNull(armed.best());
        assertFalse(armed.activated());

        store.saveTrailingState(SEC, new TrailingState(new BigDecimal("115.25"), true));

        StoredGuard activated = read();
        assertSameValue("115.25", activated.best());
        assertTrue(activated.activated());
        assertSameValue("108", activated.trailing().activation());
    }

    @Test
    void reArmingWithoutTrailingClearsTheOldLevelsAndState() {
        store.upsert(SEC, new BigDecimal("96"), null);
        store.saveTrailing(SEC, new TrailingLevels(new BigDecimal("108"), new BigDecimal("2"),
                new BigDecimal("0.2")), new BigDecimal("10000"));
        store.saveTrailingState(SEC, new TrailingState(new BigDecimal("112"), true));

        store.upsert(SEC, new BigDecimal("50"), null);
        store.saveTrailing(SEC, null, null);

        StoredGuard reArmed = read();
        assertSameValue("50", reArmed.stop());
        assertNull(reArmed.trailing());
        assertNull(reArmed.best());
        assertFalse(reArmed.activated());
        assertNull(reArmed.riskKzt());
    }

    @Test
    void aGuardWrittenBeforeV7HasNoTrailing() {
        store.upsert(SEC, new BigDecimal("90"), new BigDecimal("120"));

        StoredGuard old = read();
        assertNull(old.trailing());
        assertNull(old.best());
        assertFalse(old.activated());
    }
}
