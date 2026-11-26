package io.github.signalbot.domain.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the decision engine is given for one trigger. Assembled by the
 * application layer; the engine must not fetch anything itself.
 *
 * @param symbol          the symbol under evaluation
 * @param triggerReason   why this evaluation fired (price move, news, earnings)
 * @param portfolio       current portfolio snapshot
 * @param cash            current cash balances
 * @param news            recent news for the symbol; empty = the feed answered "none"
 * @param snapshot        latest price snapshot, or null only when no market-data feed is configured
 * @param previousVerdict the last verdict for this symbol, or null if none
 * @param newsFeedEnabled whether a news feed is configured; false means {@code news} is
 *                        "not available", not "no news"
 * @param atr             ATR(14) of the symbol on daily bars (D-002), or null when it is not available (no price
 *                        history configured, the history failed, or too few bars); context for Claude, not a gate
 */
public record DecisionContext(
        String symbol,
        String triggerReason,
        Portfolio portfolio,
        List<CashBalance> cash,
        List<NewsItem> news,
        PriceSnapshot snapshot,
        Verdict previousVerdict,
        boolean newsFeedEnabled,
        BigDecimal atr
) {
    public DecisionContext {
        cash = cash == null ? List.of() : List.copyOf(cash);
        news = news == null ? List.of() : List.copyOf(news);
    }

    /** A context without an ATR ({@code atr} = null, "not available"). */
    public DecisionContext(String symbol, String triggerReason, Portfolio portfolio, List<CashBalance> cash,
                           List<NewsItem> news, PriceSnapshot snapshot, Verdict previousVerdict,
                           boolean newsFeedEnabled) {
        this(symbol, triggerReason, portfolio, cash, news, snapshot, previousVerdict, newsFeedEnabled, null);
    }
}
