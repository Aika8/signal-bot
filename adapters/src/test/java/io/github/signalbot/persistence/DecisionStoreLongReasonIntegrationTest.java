package io.github.signalbot.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs in CI (needs Docker; skipped without it, and CI fails on a skip): a long news reason is cut, not lost. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(PaperWalletIntegrationTest.FakeFeedConfig.class)
class DecisionStoreLongReasonIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    DecisionStore store;
    @Autowired
    JdbcClient db;

    @Test
    void newsDecisionWithA150CharacterHeadlineIsStoredWithTheReasonCut() {
        String reason = "news: " + "H".repeat(150);

        store.record("LONG1", reason, "{}", "{}", "HOLD", 0.5, "long headline");

        String stored = storedReason("LONG1");
        assertEquals(64, stored.codePointCount(0, stored.length()));
        assertTrue(stored.startsWith("news: HHH"));
        assertTrue(stored.endsWith("…"));
        assertEquals("news: " + "H".repeat(57) + "…", stored);
    }

    @Test
    void anEmojiAtTheCutIsNotSplit() {
        // 6 + 56 = 62 code points, then the emoji is code point 63 (kept), the rest is cut.
        String emoji = "🚀"; // rocket, a surrogate pair
        String reason = "news: " + "A".repeat(56) + emoji + "B".repeat(100);

        store.record("LONG2", reason, "{}", "{}", "HOLD", 0.5, null);

        String stored = storedReason("LONG2");
        assertEquals(64, stored.codePointCount(0, stored.length()));
        assertEquals("news: " + "A".repeat(56) + emoji + "…", stored);
    }

    @Test
    void emojiOnTheLastKeptPositionStaysWhole() {
        // 6 + 57 = 63 code points; the emoji would be the 64th and is replaced by the ellipsis as a whole.
        String emoji = "🚀";
        String reason = "news: " + "A".repeat(57) + emoji + "B".repeat(100);

        store.record("LONG3", reason, "{}", "{}", "HOLD", 0.5, null);

        assertEquals("news: " + "A".repeat(57) + "…", storedReason("LONG3"));
    }

    @Test
    void aShortReasonIsStoredUnchanged() {
        store.record("SHORT", "news: short headline", "{}", "{}", "HOLD", 0.5, null);

        assertEquals("news: short headline", storedReason("SHORT"));
    }

    private String storedReason(String symbol) {
        return db.sql("SELECT trigger_reason FROM decisions WHERE symbol = :s ORDER BY id DESC LIMIT 1")
                .param("s", symbol).query(String.class).single();
    }
}
