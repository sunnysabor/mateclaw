package vip.mate.trigger.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.trigger.model.TriggerEntity;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Translates a fired trigger into a workflow run. Renders the trigger's
 * {@code payloadTemplate} as JSON via Pebble, parses the result into the
 * input map, and asks the runner to execute the latest revision of the
 * target workflow. Logs and swallows failures so a bad trigger never takes
 * the scheduler thread down.
 */
@Slf4j
@Component
public class TriggerDispatcher {

    private final Map<String, TriggerTargetDispatcher> dispatchers;

    public TriggerDispatcher(List<TriggerTargetDispatcher> dispatchers) {
        this.dispatchers = dispatchers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        d -> d.targetType().toLowerCase(Locale.ROOT),
                        Function.identity()));
    }

    /**
     * Dispatch a single fire of {@code trigger}. {@code event} is the
     * source-event context (cron tick metadata, channel message, etc.) —
     * its top-level fields are exposed to the payload template under
     * {@code event.*}. Returns a {@link DispatchResult} so the caller
     * can distinguish a real fire from a pre-flight skip or a runner
     * failure and update {@code fireCount} / {@code lastFiredAt} /
     * {@code lastError} accordingly.
     */
    public DispatchResult dispatch(TriggerEntity trigger, Map<String, Object> event) {
        String targetType = trigger.getTargetType();
        TriggerTargetDispatcher dispatcher = targetType == null
                ? null
                : dispatchers.get(targetType.toLowerCase(Locale.ROOT));
        if (dispatcher == null) {
            log.warn("Trigger {} target_type {} not supported; skipping fire",
                    trigger.getId(), targetType);
            return DispatchResult.skipped("unsupported target_type: " + targetType);
        }
        return dispatcher.dispatch(trigger, event);
    }
}
