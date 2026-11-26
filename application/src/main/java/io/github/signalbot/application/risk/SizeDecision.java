package io.github.signalbot.application.risk;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/**
 * The size of a BUY (D-003).
 *
 * @param quantity        whole shares to buy; {@code 0} when rejected
 * @param riskKzt         the KZT lost if the stop is hit: {@code quantity × riskPerShareKzt} (fees not included)
 * @param riskPerShareKzt {@code (entry − stop) × fxToKzt}
 * @param limitedBy       which bound set the quantity: {@code RISK}, {@code CLAUDE}, {@code CONCENTRATION} or {@code CASH}
 * @param rejection       why the BUY is rejected, non-null exactly when {@code quantity < 1}
 */
public record SizeDecision(long quantity, BigDecimal riskKzt, BigDecimal riskPerShareKzt, String limitedBy,
                           String rejection) {

    public static final String RISK = "RISK";
    public static final String CLAUDE = "CLAUDE";
    public static final String CONCENTRATION = "CONCENTRATION";
    public static final String CASH = "CASH";

    private static final Set<String> LIMITS = Set.of(RISK, CLAUDE, CONCENTRATION, CASH);

    public SizeDecision {
        Objects.requireNonNull(riskKzt, "riskKzt");
        Objects.requireNonNull(riskPerShareKzt, "riskPerShareKzt");
        Objects.requireNonNull(limitedBy, "limitedBy");
        if (!LIMITS.contains(limitedBy)) {
            throw new IllegalArgumentException("unknown limitedBy: " + limitedBy);
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity < 0: " + quantity);
        }
        if ((quantity < 1) != (rejection != null)) {
            throw new IllegalArgumentException("rejection must be set exactly when quantity < 1");
        }
    }

    public boolean rejected() {
        return rejection != null;
    }
}
