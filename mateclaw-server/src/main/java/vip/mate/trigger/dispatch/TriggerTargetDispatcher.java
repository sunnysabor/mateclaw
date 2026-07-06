package vip.mate.trigger.dispatch;

import vip.mate.trigger.model.TriggerEntity;

import java.util.Map;

public interface TriggerTargetDispatcher {

    String targetType();

    DispatchResult dispatch(TriggerEntity trigger, Map<String, Object> event);
}
