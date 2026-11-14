package io.github.signalbot.domain.port;

/**
 * The decision engine could not produce a verdict (call failed, response empty
 * or malformed). Never converted into a HOLD: a real HOLD and "no answer" must
 * stay distinguishable in logs, the decisions table and Telegram.
 */
public class DecisionFailedException extends RuntimeException {

    public DecisionFailedException(String message) {
        super(message);
    }

    public DecisionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
