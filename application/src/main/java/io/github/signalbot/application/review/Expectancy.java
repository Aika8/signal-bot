package io.github.signalbot.application.review;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Expectancy of closed trades in R (D-004: {@code E = P(win)·avgWin − P(loss)·avgLoss}). The one place this rule
 * lives; the nightly review shows it, it is computed by code and never by Claude.
 *
 * <ul>
 *   <li>A win is a trade above 0R, a loss a trade below 0R. <b>A 0R trade counts as neither</b>: it is part of
 *       {@code n} (so it lowers both {@code P(win)} and {@code P(loss)}) but not of the win or loss averages.</li>
 *   <li>{@code winRate} = wins / n; {@code avgWin} = mean of the wins; {@code avgLoss} = mean of the losses as a
 *       positive number (a −1R loss gives 1R); either average is 0 when there are no such trades.</li>
 *   <li>{@code expectancy} is computed as {@code (Σ wins − Σ |losses|) / n}, which is the formula above without
 *       rounding the intermediate rates and averages. All values have scale 4, {@link RoundingMode#HALF_UP}.</li>
 *   <li>No trades ({@code n = 0}): every value is 0.</li>
 * </ul>
 *
 * @param n          number of trades
 * @param wins       trades above 0R
 * @param losses     trades below 0R
 * @param winRate    wins / n, a fraction (0.5 = 50 %)
 * @param avgWin     mean R of the wins, ≥ 0
 * @param avgLoss    mean R of the losses as a positive number, ≥ 0
 * @param expectancy expected R per trade
 */
public record Expectancy(int n, int wins, int losses, BigDecimal winRate, BigDecimal avgWin, BigDecimal avgLoss,
                         BigDecimal expectancy) {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * @param rs results of closed trades in R, not null, no null elements
     * @throws IllegalArgumentException if {@code rs} or an element is null
     */
    public static Expectancy of(List<BigDecimal> rs) {
        if (rs == null) {
            throw new IllegalArgumentException("rs is required");
        }
        int wins = 0;
        int losses = 0;
        BigDecimal sumWin = BigDecimal.ZERO;
        BigDecimal sumLoss = BigDecimal.ZERO;
        for (BigDecimal r : rs) {
            if (r == null) {
                throw new IllegalArgumentException("a result in R is null");
            }
            if (r.signum() > 0) {
                wins++;
                sumWin = sumWin.add(r);
            } else if (r.signum() < 0) {
                losses++;
                sumLoss = sumLoss.add(r.negate());
            }
        }
        int n = rs.size();
        BigDecimal zero = BigDecimal.ZERO.setScale(SCALE);
        if (n == 0) {
            return new Expectancy(0, 0, 0, zero, zero, zero, zero);
        }
        return new Expectancy(n, wins, losses,
                ratio(BigDecimal.valueOf(wins), n),
                wins == 0 ? zero : ratio(sumWin, wins),
                losses == 0 ? zero : ratio(sumLoss, losses),
                ratio(sumWin.subtract(sumLoss), n));
    }

    private static BigDecimal ratio(BigDecimal sum, int count) {
        return sum.divide(BigDecimal.valueOf(count), SCALE, ROUNDING);
    }
}
