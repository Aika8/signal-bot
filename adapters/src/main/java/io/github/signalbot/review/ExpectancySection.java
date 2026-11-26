package io.github.signalbot.review;

import io.github.signalbot.application.review.Expectancy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The "Trades in R" section of the nightly review (SB-016): the {@link Expectancy} of all closed trades so far, as
 * text computed by code. Claude may read it as input but never computes these numbers.
 */
final class ExpectancySection {

    /** Below this many trades in R the section says the numbers are too few to judge. */
    static final int MIN_TRADES_TO_JUDGE = 20;

    private ExpectancySection() {
    }

    /**
     * @param e       expectancy of the closed trades that have a result in R
     * @param leftOut closed trades without a result in R (no usable 1R), not part of {@code e}
     */
    static String format(Expectancy e, long leftOut) {
        StringBuilder sb = new StringBuilder("Trades in R (all closed trades so far):\n");
        sb.append("  n=").append(e.n())
                .append(", win rate ").append(e.winRate().movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString())
                .append(" %, avg win ").append(r(e.avgWin()))
                .append("R, avg loss ").append(r(e.avgLoss()))
                .append("R, E = ").append(signed(e.expectancy())).append("R\n");
        if (e.n() < MIN_TRADES_TO_JUDGE) {
            sb.append("  too few trades to judge (").append(e.n()).append(" of ").append(MIN_TRADES_TO_JUDGE)
                    .append(")\n");
        }
        if (leftOut > 0) {
            sb.append("  left out (no 1R): ").append(leftOut).append('\n');
        }
        return sb.toString();
    }

    private static String r(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String signed(BigDecimal value) {
        String s = r(value);
        return value.setScale(2, RoundingMode.HALF_UP).signum() > 0 ? "+" + s : s;
    }
}
