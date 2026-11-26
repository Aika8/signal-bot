package io.github.signalbot.telegram;

import io.github.signalbot.application.risk.StopSource;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.execution.SizingInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Builds the text of an execution prompt (Executed / Skipped). Pure, so it is tested without Telegram.
 * <p>
 * The first line is the prompt as it always was. A BUY with sizing facts (D-002, D-003) adds the stop and its
 * source ({@code CLAUDE} / {@code ATR}, with Claude's original stop when the ATR stop replaced it), {@code ATR(14)},
 * 1R in tenge with the quantity, and the reminder that <b>Skipped</b> means "do not take the trade". A SELL or an
 * exit has no sizing lines.
 */
public final class ExecutionPromptFormatter {

    private ExecutionPromptFormatter() {
    }

    /**
     * @param stop   the stop stored on the execution (after {@code StopPolicy}); may be {@code null}
     * @param sizing the execution's sizing facts, or {@code null} when there are none
     */
    public static String format(String symbol, Action action, BigDecimal quantity, BigDecimal refPrice,
                                BigDecimal stop, SizingInfo sizing) {
        return format(symbol, action, quantity, refPrice, stop, sizing, null);
    }

    /**
     * @param limitPrice the suggested limit stored on the execution (a trailing exit, D-004), or {@code null};
     *                   when present, a line {@code Suggested limit 109.80} follows the first line
     */
    public static String format(String symbol, Action action, BigDecimal quantity, BigDecimal refPrice,
                                BigDecimal stop, SizingInfo sizing, BigDecimal limitPrice) {
        StringBuilder sb = new StringBuilder();
        sb.append(action).append(' ').append(symbol).append(" x").append(quantity)
                .append(" (ref ").append(refPrice).append(") — confirm?");
        if (limitPrice != null) {
            sb.append('\n').append("Suggested limit ").append(price(limitPrice));
        }
        if (action != Action.BUY || sizing == null) {
            return sb.toString();
        }
        sb.append('\n').append(stopLine(stop, sizing));
        if (sizing.atr() != null) {
            sb.append('\n').append("ATR(14) ").append(price(sizing.atr()));
        }
        if (sizing.riskKzt() != null) {
            sb.append('\n').append("1R = ").append(tenge(sizing.riskKzt())).append(" ₸");
            if (quantity != null) {
                sb.append(" (qty ").append(quantity.stripTrailingZeros().toPlainString()).append(')');
            }
        }
        sb.append('\n').append("Skipped = do not take the trade");
        return sb.toString();
    }

    private static String stopLine(BigDecimal stop, SizingInfo sizing) {
        StringBuilder sb = new StringBuilder("Stop ").append(stop == null ? "none" : price(stop));
        StopSource source = sizing.stopSource();
        if (source == StopSource.ATR) {
            sb.append(" (ATR; Claude: ")
                    .append(sizing.claudeStop() == null ? "none" : price(sizing.claudeStop())).append(')');
        } else if (source != null) {
            sb.append(" (").append(source.name()).append(')');
        }
        return sb.toString();
    }

    /** At least two decimals, never fewer than the value carries (display only, nothing is rounded away). */
    private static String price(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 2 ? value.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                : stripped.toPlainString();
    }

    /** Whole tenge, thousands separated by a space: {@code 12 500}. */
    private static String tenge(BigDecimal value) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        DecimalFormat format = new DecimalFormat("#,##0", symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }
}
