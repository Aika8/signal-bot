package io.github.signalbot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.signalbot.application.review.Expectancy;
import io.github.signalbot.config.TelegramProperties;
import io.github.signalbot.domain.port.PortfolioReader;
import io.github.signalbot.execution.ExecutionState;
import io.github.signalbot.execution.LatencyStats;
import io.github.signalbot.persistence.DailyPnlStore;
import io.github.signalbot.persistence.DailyReviewStore;
import io.github.signalbot.persistence.ExecutionStore;
import io.github.signalbot.persistence.ExecutionStore.SlippageRow;
import io.github.signalbot.persistence.PositionGuardStore;
import io.github.signalbot.persistence.PositionGuardStore.StoredGuard;
import io.github.signalbot.persistence.TradeQueryStore;
import io.github.signalbot.telegram.SignalBotTelegramBot;
import io.github.signalbot.telegram.TelegramSender;
import io.github.signalbot.telegram.TelegramText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Nightly job at 23:30 Almaty: summarize the day (incl. latency, slippage, exec counts) and the trades in R so far,
 * review, store, push. The "Trades in R" section is computed by code ({@link Expectancy}); Claude receives it inside
 * the summary, and it is appended unchanged after Claude's written review, so the numbers never depend on Claude.
 * The "Held without a stop" line (SB-035) is computed and appended the same way: held positions with no stored guard,
 * e.g. after a Skipped or expired exit SELL, which disarms and deletes the guard.
 */
@Component
public class NightlyReviewJob {

    private static final Logger LOG = LoggerFactory.getLogger(NightlyReviewJob.class);
    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

    private final PortfolioReader portfolioReader;
    private final DailyPnlStore dailyPnl;
    private final TradeQueryStore tradeQuery;
    private final ExecutionStore executionStore;
    private final DailyReviewStore reviewStore;
    private final ObjectProvider<ClaudeReviewer> reviewer;
    private final ObjectProvider<SignalBotTelegramBot> bot;
    private final TelegramSender sender;
    private final TelegramProperties telegram;
    private final ObjectMapper objectMapper;
    private final PositionGuardStore guardStore;

    /** Without a guard store: the review says {@code Held without a stop: not checked}. */
    public NightlyReviewJob(PortfolioReader portfolioReader, DailyPnlStore dailyPnl, TradeQueryStore tradeQuery,
                            ExecutionStore executionStore, DailyReviewStore reviewStore,
                            ObjectProvider<ClaudeReviewer> reviewer, ObjectProvider<SignalBotTelegramBot> bot,
                            TelegramSender sender, TelegramProperties telegram, ObjectMapper objectMapper) {
        this(portfolioReader, dailyPnl, tradeQuery, executionStore, reviewStore, reviewer, bot, sender, telegram,
                objectMapper, null);
    }

    @Autowired
    public NightlyReviewJob(PortfolioReader portfolioReader, DailyPnlStore dailyPnl, TradeQueryStore tradeQuery,
                            ExecutionStore executionStore, DailyReviewStore reviewStore,
                            ObjectProvider<ClaudeReviewer> reviewer, ObjectProvider<SignalBotTelegramBot> bot,
                            TelegramSender sender, TelegramProperties telegram, ObjectMapper objectMapper,
                            PositionGuardStore guardStore) {
        this.portfolioReader = portfolioReader;
        this.dailyPnl = dailyPnl;
        this.tradeQuery = tradeQuery;
        this.executionStore = executionStore;
        this.reviewStore = reviewStore;
        this.reviewer = reviewer;
        this.bot = bot;
        this.sender = sender;
        this.telegram = telegram;
        this.objectMapper = objectMapper;
        this.guardStore = guardStore;
    }

    @Scheduled(cron = "${signalbot.trigger.nightly-review-cron:0 30 23 * * *}", zone = "Asia/Almaty")
    public void run() {
        LocalDate today = LocalDate.now(ALMATY);
        Instant dayStart = today.atStartOfDay(ALMATY).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(ALMATY).toInstant();

        String computed = heldWithoutStop() + tradesInR();
        String summary = buildSummary(today, dayStart, dayEnd) + computed;
        String review = summary;
        ClaudeReviewer r = reviewer.getIfAvailable();
        if (r != null) {
            try {
                review = r.review(summary) + "\n\n" + computed;
            } catch (Exception e) {
                LOG.warn("Nightly review call failed: {}; storing raw summary", e.getMessage());
            }
        }
        persist(today, review, summary);
        push(review);
    }

    private String buildSummary(LocalDate day, Instant dayStart, Instant dayEnd) {
        List<Long> latencies = executionStore.latenciesBetween(dayStart, dayEnd);
        List<SlippageRow> slippage = executionStore.slippageBetween(dayStart, dayEnd);
        StringBuilder sb = new StringBuilder();
        sb.append("Date: ").append(day).append('\n');
        sb.append("Portfolio total: ").append(portfolioReader.read().totalValueKzt()).append(" KZT\n");
        sb.append("Realized P&L today: ").append(dailyPnl.realizedOn(day)).append(" KZT\n");
        sb.append("Fills: ").append(latencies.size())
                .append(", EXPIRED: ").append(executionStore.countInStateBetween(ExecutionState.EXPIRED, dayStart, dayEnd))
                .append(", SKIPPED: ").append(executionStore.countInStateBetween(ExecutionState.SKIPPED, dayStart, dayEnd))
                .append('\n');
        sb.append("Latency median/max (ms): ").append(LatencyStats.median(latencies))
                .append(" / ").append(LatencyStats.max(latencies)).append('\n');
        sb.append("Avg slippage (fill - ref): ").append(avgSlippage(slippage)).append('\n');
        ExecutionStore.FeeTotals fees = executionStore.feeTotalsBetween(dayStart, dayEnd);
        sb.append("Fees KZT (per-share / per-order / percentage / total): ")
                .append(fees.perShare()).append(" / ").append(fees.perOrder())
                .append(" / ").append(fees.percentage()).append(" / ").append(fees.total()).append('\n');
        List<String> trades = tradeQuery.summariesBetween(dayStart, dayEnd);
        sb.append("Trades (").append(trades.size()).append("):\n");
        trades.forEach(t -> sb.append("  - ").append(t).append('\n'));
        return sb.toString();
    }

    private String tradesInR() {
        return ExpectancySection.format(Expectancy.of(tradeQuery.closedResultsR()), tradeQuery.closedWithoutRCount());
    }

    /** SB-035: held positions (quantity above zero) whose symbol has no stored guard, one line. */
    private String heldWithoutStop() {
        String prefix = "Held without a stop: ";
        if (guardStore == null) {
            return prefix + "not checked\n";
        }
        Set<String> guarded;
        try {
            guarded = guardStore.findAllGuards().stream().map(StoredGuard::secCode).collect(Collectors.toSet());
        } catch (Exception e) {
            LOG.warn("Nightly review could not read the stored guards: {}", e.getMessage());
            return prefix + "not checked (guards unreadable)\n";
        }
        String unguarded = portfolioReader.read().positions().stream()
                .filter(p -> p.quantity() != null && p.quantity().signum() > 0)
                .filter(p -> !guarded.contains(p.secCode()))
                .map(p -> p.secCode() + " (qty " + p.quantity().stripTrailingZeros().toPlainString() + ")")
                .collect(Collectors.joining(", "));
        return prefix + (unguarded.isEmpty() ? "none" : unguarded) + "\n";
    }

    private static BigDecimal avgSlippage(List<SlippageRow> rows) {
        if (rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (SlippageRow r : rows) {
            sum = sum.add(r.fillPrice().subtract(r.refPrice()));
        }
        return sum.divide(BigDecimal.valueOf(rows.size()), 6, RoundingMode.HALF_UP);
    }

    private void persist(LocalDate day, String review, String summary) {
        try {
            reviewStore.upsert(day, review, objectMapper.writeValueAsString(Map.of("summary", summary)));
        } catch (Exception e) {
            LOG.warn("Failed to store daily review: {}", e.getMessage());
        }
    }

    private void push(String review) {
        SignalBotTelegramBot activeBot = bot.getIfAvailable();
        if (activeBot == null || telegram.chatId() == null || telegram.chatId().isBlank()) {
            LOG.info("Nightly review (not pushed):\n{}", review);
            return;
        }
        List<String> parts = TelegramText.split("Daily review\n" + review);
        for (int i = 0; i < parts.size(); i++) {
            try {
                sender.send(activeBot, telegram.chatId(), parts.get(i));
            } catch (Exception e) {
                LOG.warn("Failed to push nightly review part {}/{}: {}; the remaining parts are not sent",
                        i + 1, parts.size(), e.getMessage());
                return;
            }
        }
    }
}
