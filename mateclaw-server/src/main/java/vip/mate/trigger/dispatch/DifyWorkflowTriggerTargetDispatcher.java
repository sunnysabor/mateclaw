package vip.mate.trigger.dispatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.dify.api.DifyWorkflowRunVO;
import vip.mate.dify.service.DifyWorkflowService;
import vip.mate.trigger.model.TriggerEntity;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DifyWorkflowTriggerTargetDispatcher implements TriggerTargetDispatcher {

    private final DifyWorkflowService difyWorkflowService;
    private final TriggerPayloadRenderer payloadRenderer;

    @Override
    public String targetType() {
        return "dify_workflow";
    }

    @Override
    public DispatchResult dispatch(TriggerEntity trigger, Map<String, Object> event) {
        Map<String, Object> inputs;
        try {
            inputs = payloadRenderer.renderInputs(trigger, event);
        } catch (Exception e) {
            return DispatchResult.failed("payload render failed: " + e.getMessage());
        }
        try {
            DifyWorkflowRunVO run = difyWorkflowService.runFromTrigger(trigger, inputs);
            if (run == null) {
                return DispatchResult.failed("Dify workflow service returned null run");
            }
            if ("failed".equalsIgnoreCase(run.state())) {
                return DispatchResult.failed(run.id(),
                        "Dify workflow run failed: "
                                + (run.errorMessage() == null ? "(no message)" : run.errorMessage()));
            }
            return DispatchResult.fired(run.id());
        } catch (Exception e) {
            log.error("Trigger {} dispatch failed for Dify workflow config {}: {}",
                    trigger.getId(), trigger.getTargetId(), e.getMessage(), e);
            return DispatchResult.failed("Dify workflow dispatch threw: " + e.getMessage());
        }
    }
}
