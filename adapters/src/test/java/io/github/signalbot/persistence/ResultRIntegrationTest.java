package io.github.signalbot.persistence;

import io.github.signalbot.application.risk.StopSource;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.PaperFill;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.PaperWallet;
import io.github.signalbot.execution.ExecutionSource;
import io.github.signalbot.execution.SizingInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs in CI (needs Docker; skipped without it, and CI fails on a skip): a SELL stores its result in R on its
 * {@code paper_trade} row (D-003, SB-015). FX is fixed at 500 here so the hand-computed values stay round:
 * US fee = (0.005 × qty + 1.00) USD × 500.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "signalbot.fx.usd-kzt=500")
@Import(PaperWalletIntegrationTest.FakeFeedConfig.class)
class ResultRIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    PaperWallet paperWallet;
    @Autowired
    ExecutionStore executionStore;
    @Autowired
    JdbcClient db;

    @Test
    void lossBeyondOneRIsStoredNotClipped() {
        // BUY 10 @ 100 with 1R = 10 000; fee (0.05 + 1) × 500 = 525.
        long buy = fill("AAPL", Action.BUY, "10", "100", new BigDecimal("10000"));
        // SELL 10 @ 97.61: realised (97.61 − 100) × 10 × 500 = −11 950; fee 525.
        // Net −11 950 − 525 − 525 = −13 000 → −13 000 / 10 000 = −1.3000.
        long sell = fill("AAPL", Action.SELL, "10", "97.61", null);

        assertEquals(Optional.of(new BigDecimal("-1.3000")), resultR(sell));
        assertTrue(resultR(buy).isEmpty(), "a BUY has no result in R");
    }

    @Test
    void severalBuysSumTheirOneRAndAPartialSellProratesTheirFees() {
        // BUY 4 @ 50, 1R 6 000, fee (0.02 + 1) × 500 = 510; BUY 4 @ 50, 1R 4 000, fee 510 → 1R 10 000, fees 1 020.
        fill("MSFT", Action.BUY, "4", "50", new BigDecimal("6000"));
        fill("MSFT", Action.BUY, "4", "50", new BigDecimal("4000"));
        // SELL 4 @ 60: realised (60 − 50) × 4 × 500 = 20 000; fee 510; BUY fees × 4 / 8 = 510.
        // Net 20 000 − 510 − 510 = 18 980 → 1.8980.
        long first = fill("MSFT", Action.SELL, "4", "60", null);
        // The rest at the same price closes the position with the same result: 1.8980.
        long second = fill("MSFT", Action.SELL, "4", "60", null);

        assertEquals(Optional.of(new BigDecimal("1.8980")), resultR(first));
        assertEquals(Optional.of(new BigDecimal("1.8980")), resultR(second));
    }

    @Test
    void aReopenedPositionUsesOnlyItsOwnBuys() {
        fill("NVDA", Action.BUY, "10", "100", new BigDecimal("99999"));
        fill("NVDA", Action.SELL, "10", "100", null);
        // New position: BUY 10 @ 100, 1R 10 000. SELL 10 @ 97.61 → −1.3000 as in the first test.
        fill("NVDA", Action.BUY, "10", "100", new BigDecimal("10000"));
        long sell = fill("NVDA", Action.SELL, "10", "97.61", null);

        assertEquals(Optional.of(new BigDecimal("-1.3000")), resultR(sell));
    }

    @Test
    void aBuyWithoutOneRLeavesTheSellWithoutAResult() {
        fill("AMZN", Action.BUY, "10", "100", null);
        long sell = fill("AMZN", Action.SELL, "10", "90", null);

        assertTrue(resultR(sell).isEmpty());
    }

    /** Books a confirmed execution the way {@code ExecutionService} does: insert, apply, record the fee. */
    private long fill(String symbol, Action action, String qty, String price, BigDecimal riskKzt) {
        SizingInfo sizing = riskKzt == null ? null
                : new SizingInfo(StopSource.ATR, null, new BigDecimal("1"), new BigDecimal("2"), riskKzt, null);
        BigDecimal fillPrice = new BigDecimal(price);
        long id = executionStore.insertPending(symbol, action, ExecutionSource.VERDICT, new BigDecimal(qty),
                new BigDecimal("1"), new BigDecimal("1000"), fillPrice, Instant.now(), sizing);
        Verdict verdict = new Verdict(symbol, action, new BigDecimal(qty), null, null, null,
                0.9, "integration test", Instant.now().plusSeconds(300));
        PaperFill fill = paperWallet.applyAt(verdict, fillPrice, id);
        executionStore.recordFee(id, fill.feeBreakdown());
        return id;
    }

    private Optional<BigDecimal> resultR(long executionId) {
        java.util.List<BigDecimal> values = db.sql("SELECT result_r FROM paper_trade WHERE execution_id = :id")
                .param("id", executionId).query((rs, i) -> rs.getBigDecimal("result_r")).list();
        assertEquals(1, values.size(), "one trade per execution");
        return Optional.ofNullable(values.get(0));
    }
}
