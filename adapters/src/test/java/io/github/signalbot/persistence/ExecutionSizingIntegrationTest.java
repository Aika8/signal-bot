package io.github.signalbot.persistence;

import io.github.signalbot.application.risk.StopSource;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.execution.ExecutionService;
import io.github.signalbot.execution.ExecutionSource;
import io.github.signalbot.execution.SizingInfo;
import io.github.signalbot.persistence.ExecutionStore.ExecutionRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Runs in CI (needs Docker; skipped without it, and CI fails on a skip): executions keep their sizing facts (V7). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(PaperWalletIntegrationTest.FakeFeedConfig.class)
class ExecutionSizingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ExecutionService executionService;
    @Autowired
    ExecutionStore executionStore;

    private static Verdict buy(String symbol) {
        return new Verdict(symbol, Action.BUY, new BigDecimal("3"), new BigDecimal("150"),
                new BigDecimal("140"), new BigDecimal("170"), 0.8, "test", Instant.now().plusSeconds(900));
    }

    @Test
    void executionSubmittedWithSizingReadsBackTheSameValues() {
        SizingInfo sizing = new SizingInfo(StopSource.CLAUDE, new BigDecimal("140.25"), new BigDecimal("4.123456"),
                new BigDecimal("9.75"), new BigDecimal("15375.50"), new BigDecimal("4"));

        long id = executionService.submit(buy("AAPL"), ExecutionSource.VERDICT, new BigDecimal("150"), sizing);

        SizingInfo read = executionStore.find(id).map(ExecutionRow::sizing).orElseThrow();
        assertEquals(StopSource.CLAUDE, read.stopSource());
        assertSame(sizing.claudeStop(), read.claudeStop());
        assertSame(sizing.atr(), read.atr());
        assertSame(sizing.riskPerShare(), read.riskPerShare());
        assertSame(sizing.riskKzt(), read.riskKzt());
        assertSame(sizing.claudeQuantity(), read.claudeQuantity());
    }

    @Test
    void atrStopWithoutClaudeStopReadsBackWithNullClaudeStop() {
        SizingInfo sizing = new SizingInfo(StopSource.ATR, null, new BigDecimal("5"), new BigDecimal("10"),
                new BigDecimal("5250"), null);

        long id = executionService.submit(buy("NVDA"), ExecutionSource.VERDICT, new BigDecimal("150"), sizing);

        SizingInfo read = executionStore.find(id).map(ExecutionRow::sizing).orElseThrow();
        assertEquals(StopSource.ATR, read.stopSource());
        assertNull(read.claudeStop());
        assertSame(sizing.atr(), read.atr());
        assertNull(read.claudeQuantity());
    }

    @Test
    void executionSubmittedTheOldWayReadsBackWithNullSizing() {
        long id = executionService.submit(buy("MSFT"), ExecutionSource.VERDICT, new BigDecimal("150"));

        ExecutionRow row = executionStore.find(id).orElseThrow();
        assertNotNull(row);
        assertNull(row.sizing());
    }

    /** Same number; the NUMERIC column may change the scale. */
    private static void assertSame(BigDecimal expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, expected.compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }
}
