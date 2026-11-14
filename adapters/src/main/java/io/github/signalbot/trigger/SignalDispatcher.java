package io.github.signalbot.trigger;

import io.github.signalbot.application.trigger.Debouncer;
import io.github.signalbot.application.trigger.TriggerSignal;
import io.github.signalbot.config.TriggerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies the per-symbol debounce, then acts on a trigger. In increment 3
 * (dry-run) it only logs; the Claude decision call is wired in increment 4.
 */
@Component
public class SignalDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(SignalDispatcher.class);

    private final Debouncer debouncer;
    private final TriggerProperties props;
    private final LiveDecisionHandler liveHandler;

    public SignalDispatcher(Debouncer dispatchDebouncer, TriggerProperties props, LiveDecisionHandler liveHandler) {
        this.debouncer = dispatchDebouncer;
        this.props = props;
        this.liveHandler = liveHandler;
    }

    public void dispatch(TriggerSignal signal) {
        if (!debouncer.tryDispatch(signal.symbol(), signal.at())) {
            LOG.debug("Debounced {} {}", signal.symbol(), signal.type());
            return;
        }
        if (props.dryRun()) {
            LOG.info("DRY-RUN trigger [{}] {} - {}", signal.type(), signal.symbol(), signal.reason());
            return;
        }
        liveHandler.handle(signal);
    }
}
