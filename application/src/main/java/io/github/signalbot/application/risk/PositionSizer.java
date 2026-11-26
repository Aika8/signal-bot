package io.github.signalbot.application.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * The size rule of D-003, the one place it lives. Pure: no I/O, no state, no {@code FeeModel} or {@code FxConverter}
 * — the caller passes the estimated round-trip fees and the FX rate.
 *
 * <pre>
 * riskBudgetKzt   = totalValueKzt × riskPerTradePct
 * riskPerShareKzt = (entry − stop) × fxToKzt
 * qty             = floor((riskBudgetKzt − estRoundTripFeesKzt) / riskPerShareKzt)
 * qty             = min(qty, claudeQty, concentrationCapQty, cashQty)
 * qty &lt; 1         → rejected
 * </pre>
 *
 * On a tie the earlier bound in the order RISK, CLAUDE, CONCENTRATION, CASH is reported, so a Claude quantity equal to
 * or above the risk size is never reported as {@code CLAUDE}.
 */
public final class PositionSizer {

    static final String RISK_TOO_SMALL = "risk budget too small for this stop distance";
    static final String CLAUDE_TOO_SMALL = "Claude's quantity is below one share";
    static final String CONCENTRATION_TOO_SMALL = "concentration cap leaves less than one share";
    static final String CASH_TOO_SMALL = "available cash buys less than one share";

    private final RiskLimits limits;

    public PositionSizer(RiskLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Sizes a BUY.
     *
     * @param totalValueKzt        total portfolio value, KZT, positive
     * @param entry                entry price, instrument currency
     * @param stop                 stop price, instrument currency, below {@code entry}
     * @param fxToKzt              KZT per unit of the instrument currency, positive
     * @param estRoundTripFeesKzt  estimated buy + sell fees, KZT, not negative; counted inside the risk budget
     * @param claudeQty            Claude's quantity, an upper bound only; empty when Claude gave none
     * @param concentrationCapQty  shares the concentration limit still allows, not negative (computed by the caller)
     * @param cashQty              shares the available cash buys, not negative (computed by the caller)
     * @throws IllegalArgumentException when {@code entry ≤ stop} or another input is out of range
     */
    public SizeDecision size(BigDecimal totalValueKzt, BigDecimal entry, BigDecimal stop, BigDecimal fxToKzt,
                             BigDecimal estRoundTripFeesKzt, OptionalLong claudeQty, long concentrationCapQty,
                             long cashQty) {
        Objects.requireNonNull(totalValueKzt, "totalValueKzt");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(stop, "stop");
        Objects.requireNonNull(fxToKzt, "fxToKzt");
        Objects.requireNonNull(estRoundTripFeesKzt, "estRoundTripFeesKzt");
        Objects.requireNonNull(claudeQty, "claudeQty");
        if (entry.compareTo(stop) <= 0) {
            throw new IllegalArgumentException("entry " + entry + " must be above stop " + stop);
        }
        requirePositive(totalValueKzt, "totalValueKzt");
        requirePositive(fxToKzt, "fxToKzt");
        if (estRoundTripFeesKzt.signum() < 0) {
            throw new IllegalArgumentException("estRoundTripFeesKzt < 0: " + estRoundTripFeesKzt);
        }
        if (claudeQty.isPresent() && claudeQty.getAsLong() < 0) {
            throw new IllegalArgumentException("claudeQty < 0: " + claudeQty.getAsLong());
        }
        if (concentrationCapQty < 0 || cashQty < 0) {
            throw new IllegalArgumentException("caps must not be negative");
        }

        BigDecimal riskBudgetKzt = totalValueKzt.multiply(limits.riskPerTradePct());
        BigDecimal riskPerShareKzt = entry.subtract(stop).multiply(fxToKzt);
        BigDecimal net = riskBudgetKzt.subtract(estRoundTripFeesKzt);
        long riskQty = net.signum() <= 0 ? 0 : toLong(net.divide(riskPerShareKzt, 0, RoundingMode.FLOOR));

        long qty = riskQty;
        String limitedBy = SizeDecision.RISK;
        if (claudeQty.isPresent() && claudeQty.getAsLong() < qty) {
            qty = claudeQty.getAsLong();
            limitedBy = SizeDecision.CLAUDE;
        }
        if (concentrationCapQty < qty) {
            qty = concentrationCapQty;
            limitedBy = SizeDecision.CONCENTRATION;
        }
        if (cashQty < qty) {
            qty = cashQty;
            limitedBy = SizeDecision.CASH;
        }

        BigDecimal riskKzt = riskPerShareKzt.multiply(BigDecimal.valueOf(qty));
        return new SizeDecision(qty, riskKzt, riskPerShareKzt, limitedBy, qty < 1 ? rejection(limitedBy) : null);
    }

    private static String rejection(String limitedBy) {
        return switch (limitedBy) {
            case SizeDecision.CLAUDE -> CLAUDE_TOO_SMALL;
            case SizeDecision.CONCENTRATION -> CONCENTRATION_TOO_SMALL;
            case SizeDecision.CASH -> CASH_TOO_SMALL;
            default -> RISK_TOO_SMALL;
        };
    }

    private static long toLong(BigDecimal whole) {
        return whole.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : whole.longValueExact();
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
