package io.github.signalbot.telegram;

import io.github.signalbot.application.account.AccountSnapshot;
import io.github.signalbot.domain.model.CashBalance;
import io.github.signalbot.domain.model.Position;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Renders a paper-account snapshot as a Telegram message. Pure and testable. */
@Component
public class PaperStateFormatter {

    public String format(AccountSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("Paper portfolio\n");
        sb.append("Total: ").append(money(snapshot.portfolio().totalValueKzt())).append(" KZT\n");

        if (snapshot.portfolio().positions().isEmpty()) {
            sb.append("\nNo open positions.\n");
        } else {
            sb.append("\nPositions:\n");
            for (Position p : snapshot.portfolio().positions()) {
                sb.append("- ").append(p.secCode())
                        .append(" x").append(stripZeros(p.quantity()))
                        .append(" @ ").append(stripZeros(p.avgEntryPrice()))
                        .append(" -> ").append(stripZeros(p.lastPrice()))
                        .append(" ").append(p.priceCurrency())
                        .append(" (").append(pct(p.unrealizedPnlPct())).append(")")
                        .append(" = ").append(money(p.marketValueKzt())).append(" KZT\n");
            }
        }

        sb.append("\nCash:\n");
        for (CashBalance c : snapshot.cash()) {
            sb.append("- ").append(c.currency()).append(": ").append(money(c.balance()))
                    .append(" (").append(money(c.balanceKzt())).append(" KZT)\n");
        }
        return sb.toString();
    }

    public String formatPnl(AccountSnapshot snapshot, BigDecimal realizedToday) {
        StringBuilder sb = new StringBuilder();
        sb.append("Paper P&L\n");
        sb.append("Total: ").append(money(snapshot.portfolio().totalValueKzt())).append(" KZT\n");
        sb.append("Realized today: ").append(money(realizedToday)).append(" KZT\n");
        sb.append("Open positions: ").append(snapshot.portfolio().positions().size());
        return sb.toString();
    }

    private static String money(BigDecimal value) {
        return value == null ? "0" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String stripZeros(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String pct(BigDecimal fraction) {
        if (fraction == null) {
            return "0%";
        }
        return fraction.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
