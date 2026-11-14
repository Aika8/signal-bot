package io.github.signalbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Trigger-engine tuning.
 *
 * @param dryRun                   increment 3: log triggers instead of calling Claude
 * @param priceMovePct             T1 threshold (0.03 == 3%)
 * @param priceWindowMinutes       T1 window in minutes
 * @param debounceMinutes          at most one dispatch per symbol per this many minutes
 * @param earningsWithinTradingDays T3 window in trading days
 * @param newsPollCron             cron for the company-news poll
 * @param earningsPollCron         cron for the daily earnings poll
 */
@ConfigurationProperties(prefix = "signalbot.trigger")
public record TriggerProperties(
        boolean dryRun,
        BigDecimal priceMovePct,
        int priceWindowMinutes,
        int debounceMinutes,
        int earningsWithinTradingDays,
        String newsPollCron,
        String earningsPollCron) {
}
