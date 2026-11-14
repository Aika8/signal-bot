package io.github.signalbot.domain.model;

import java.util.List;

/**
 * Everything the decision engine is given for one trigger. Assembled by the
 * application layer; the engine must not fetch anything itself.
 *
 * @param symbol          the symbol under evaluation
 * @param triggerReason   why this evaluation fired (price move, news, earnings)
 * @param portfolio       current portfolio snapshot
 * @param cash            current cash balances
 * @param news            recent news for the symbol
 * @param snapshot        latest price snapshot for the symbol
 * @param previousVerdict the last verdict for this symbol, or null if none
 */
public record DecisionContext(
        String symbol,
        String triggerReason,
        Portfolio portfolio,
        List<CashBalance> cash,
        List<NewsItem> news,
        PriceSnapshot snapshot,
        Verdict previousVerdict
) {
    public DecisionContext {
        cash = cash == null ? List.of() : List.copyOf(cash);
        news = news == null ? List.of() : List.copyOf(news);
    }
}
