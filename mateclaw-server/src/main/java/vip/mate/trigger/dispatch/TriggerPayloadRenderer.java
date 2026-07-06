package vip.mate.trigger.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.workflow.compiler.PebbleSubsetEvaluator;

import java.util.Map;

@Component
public class TriggerPayloadRenderer {

    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final PebbleSubsetEvaluator pebble;
    private final ObjectMapper objectMapper;

    public TriggerPayloadRenderer(PebbleSubsetEvaluator pebble, ObjectMapper objectMapper) {
        this.pebble = pebble;
        this.objectMapper = objectMapper;
    }

    /**
     * Render the trigger's payload template into an input map. Empty template
     * means "use the raw event"; non-empty template must render to a JSON object.
     */
    public Map<String, Object> renderInputs(TriggerEntity trigger, Map<String, Object> event) {
        if (trigger.getPayloadTemplate() == null || trigger.getPayloadTemplate().isBlank()) {
            return event == null ? Map.of() : event;
        }
        var compiled = pebble.parseTemplate(trigger.getPayloadTemplate());
        String rendered = pebble.evaluateAsString(compiled,
                Map.of("event", event == null ? Map.of() : event,
                        "trigger", Map.of(
                                "id", trigger.getId(),
                                "name", trigger.getName() == null ? "" : trigger.getName())));
        try {
            return objectMapper.readValue(rendered, MAP_REF);
        } catch (Exception e) {
            throw new RuntimeException("payloadTemplate produced non-JSON output: " + e.getMessage(), e);
        }
    }
}
