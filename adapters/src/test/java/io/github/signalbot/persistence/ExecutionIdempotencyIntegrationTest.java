package io.github.signalbot.persistence;

import io.github.signalbot.domain.model.Action;
import io.github.signalbot.execution.ExecutionResult;
import io.github.signalbot.execution.ExecutionResult.Outcome;
import io.github.signalbot.execution.ExecutionService;
import io.github.signalbot.execution.ExecutionSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Runs in CI (needs Docker; skipped without it, and CI fails on a skip): a confirmed execution books exactly one paper trade. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(PaperWalletIntegrationTest.FakeFeedConfig.class)
class ExecutionIdempotencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ExecutionService executionService;
    @Autowired
    ExecutionStore executionStore;
    @Autowired
    JdbcClient db;

    @Test
    void concurrentFillsBookOneTradeAndSkipAfterFillIsRefused() throws Exception {
        long id = executionStore.insertPending("MSFT", Action.BUY, ExecutionSource.VERDICT, BigDecimal.ONE,
                null, null, new BigDecimal("100"), Instant.now());

        CountDownLatch start = new CountDownLatch(1);
        Callable<ExecutionResult> fill = () -> {
            start.await();
            return executionService.fill(id, new BigDecimal("100"));
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ExecutionResult>> futures = List.of(pool.submit(fill), pool.submit(fill));
            start.countDown();
            long filled = 0;
            for (Future<ExecutionResult> f : futures) {
                if (f.get().outcome() == Outcome.FILLED) {
                    filled++;
                }
            }
            assertEquals(1, filled);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, tradesFor(id));

        ExecutionResult skip = executionService.skip(id, "late skip");
        assertEquals(Outcome.NOT_PENDING, skip.outcome());

        assertThrows(DataIntegrityViolationException.class, () -> db.sql("""
                        INSERT INTO paper_trade (sec_code, action, quantity, fill_price, notional, currency,
                                                 filled_at, execution_id)
                        VALUES ('MSFT', 'BUY', 1, 100, 52500, 'USD', now(), :id)
                        """).param("id", id).update());
    }

    private int tradesFor(long id) {
        return db.sql("SELECT COUNT(*) FROM paper_trade WHERE execution_id = :id")
                .param("id", id).query(Integer.class).single();
    }
}
