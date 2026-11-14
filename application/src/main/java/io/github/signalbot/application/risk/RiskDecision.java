package io.github.signalbot.application.risk;

/**
 * The outcome of a risk check.
 *
 * @param allowed true if the verdict may proceed
 * @param reason  the violated limit, or null when allowed
 * @param message human-readable explanation (shown in Telegram on rejection)
 */
public record RiskDecision(boolean allowed, RejectionReason reason, String message) {

    public static RiskDecision allow() {
        return new RiskDecision(true, null, "OK");
    }

    public static RiskDecision reject(RejectionReason reason, String message) {
        return new RiskDecision(false, reason, message);
    }
}
