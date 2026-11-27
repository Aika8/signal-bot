package io.github.signalbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Risk settings of the live path.
 *
 * @param earningsBlackoutTradingDays D-006: no new BUY when the company reports earnings within this many trading
 *                                    days, today included (starting value 2, D-005); {@code 0} switches it off
 */
@ConfigurationProperties(prefix = "signalbot.risk")
public record RiskProperties(int earningsBlackoutTradingDays) {

    /** The blackout switched off: the behaviour before D-006. */
    public static RiskProperties blackoutOff() {
        return new RiskProperties(0);
    }
}
