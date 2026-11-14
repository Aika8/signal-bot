package io.github.signalbot.decision;

import io.github.signalbot.domain.model.CashBalance;
import io.github.signalbot.domain.model.DecisionContext;
import io.github.signalbot.domain.model.NewsItem;
import io.github.signalbot.domain.model.Position;
import io.github.signalbot.domain.model.Verdict;
import org.springframework.stereotype.Component;

/** Builds the system and user prompts for one decision. Pure. */
@Component
public class ClaudePromptBuilder {

    private static final String SYSTEM = """
            You are a trading signal assistant for a PAPER portfolio. No real orders are ever placed.
            Given the context, return a single trading verdict.

            Respond with ONE JSON object and nothing else (no markdown, no prose outside the JSON):
            {
              "action": "BUY" | "SELL" | "HOLD",
              "quantity": number,         // units; 0 for HOLD
              "entry": number|null,       // suggested entry price
              "stop": number|null,        // stop-loss price
              "takeProfit": number|null,  // take-profit price
              "confidence": number,       // 0.0 - 1.0
              "rationale": string,        // one short sentence
              "whatChanged": string       // what changed since the last verdict for this symbol
            }

            Rules:
            - The DEFAULT answer is HOLD. Only propose BUY or SELL when the context clearly supports it.
            - Always fill "whatChanged"; if nothing changed since the last verdict, say so explicitly.
            - Keep "rationale" to one sentence. Do not include any text outside the JSON object.
            """;

    public String systemPrompt() {
        return SYSTEM;
    }

    public String userPrompt(DecisionContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Symbol: ").append(ctx.symbol()).append('\n');
        sb.append("Trigger: ").append(ctx.triggerReason()).append('\n');
        if (ctx.snapshot() != null) {
            sb.append("Last price: ").append(ctx.snapshot().last())
                    .append(", prior close: ").append(ctx.snapshot().priorClose()).append('\n');
        } else {
            sb.append("Last price: not available (no market-data feed configured)\n");
        }
        sb.append("Previous verdict: ").append(previousVerdict(ctx.previousVerdict())).append('\n');

        sb.append("Portfolio total (KZT): ")
                .append(ctx.portfolio() == null ? "n/a" : ctx.portfolio().totalValueKzt()).append('\n');
        if (ctx.portfolio() == null) {
            sb.append("Positions: not available\n");
        } else if (ctx.portfolio().positions().isEmpty()) {
            sb.append("Positions: none\n");
        } else {
            sb.append("Positions:\n");
            for (Position p : ctx.portfolio().positions()) {
                sb.append("  - ").append(p.secCode()).append(" qty ").append(p.quantity())
                        .append(" avg ").append(p.avgEntryPrice()).append(" last ").append(p.lastPrice())
                        .append(" uPnL% ").append(p.unrealizedPnlPct()).append('\n');
            }
        }
        if (ctx.cash().isEmpty()) {
            sb.append("Cash: none\n");
        } else {
            sb.append("Cash:");
            for (CashBalance c : ctx.cash()) {
                sb.append(' ').append(c.currency()).append('=').append(c.balance());
            }
            sb.append('\n');
        }
        if (!ctx.newsFeedEnabled()) {
            sb.append("Recent news (24h): not available (no news feed configured)\n");
        } else if (ctx.news().isEmpty()) {
            sb.append("Recent news (24h): none\n");
        } else {
            sb.append("Recent news (24h):\n");
            for (NewsItem n : ctx.news()) {
                sb.append("  - ").append(n.headline()).append(" (").append(n.source()).append(")\n");
            }
        }
        return sb.toString();
    }

    private static String previousVerdict(Verdict v) {
        return v == null ? "none" : v.action() + " @ " + v.entry() + " (conf " + v.confidence() + ")";
    }
}
