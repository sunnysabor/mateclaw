package vip.mate.trigger.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.workflow.runtime.WorkflowRunRequest;
import vip.mate.workflow.runtime.WorkflowRunResult;
import vip.mate.workflow.runtime.WorkflowRunner;

import java.util.Map;

@Slf4j
@Component
public class LocalWorkflowTriggerTargetDispatcher implements TriggerTargetDispatcher {

    private final WorkflowGraphLoader graphLoader;
    private final WorkflowRunner runner;
    private final TriggerPayloadRenderer payloadRenderer;

    public LocalWorkflowTriggerTargetDispatcher(WorkflowGraphLoader graphLoader,
                                                WorkflowRunner runner,
                                                TriggerPayloadRenderer payloadRenderer) {
        this.graphLoader = graphLoader;
        this.runner = runner;
        this.payloadRenderer = payloadRenderer;
    }

    @Override
    public String targetType() {
        return "workflow";
    }

    @Override
    public DispatchResult dispatch(TriggerEntity trigger, Map<String, Object> event) {
        long workspaceId = trigger.getWorkspaceId() == null ? 0L : trigger.getWorkspaceId();
        WorkflowGraphLoader.Loaded loaded = graphLoader.load(trigger.getTargetId(), workspaceId);
        if (loaded.graph() == null) {
            log.info("Trigger {} dispatch skipped: no published revision for workflow {} in workspace {}",
                    trigger.getId(), trigger.getTargetId(), workspaceId);
            return DispatchResult.skipped(
                    "no published revision for workflow " + trigger.getTargetId());
        }

        Map<String, Object> inputs;
        try {
            inputs = payloadRenderer.renderInputs(trigger, event);
        } catch (Exception e) {
            return DispatchResult.failed("payload render failed: " + e.getMessage());
        }
        WorkflowRunRequest req = new WorkflowRunRequest(
                trigger.getTargetId(),
                loaded.revisionId(),
                trigger.getWorkspaceId(),
                "trigger:" + trigger.getId(),
                inputs);
        try {
            WorkflowRunResult result = runner.run(loaded.graph(), req);
            if (result == null) {
                return DispatchResult.failed("runner returned null result");
            }
            if ("failed".equalsIgnoreCase(result.state())) {
                return DispatchResult.failed(result.runId(),
                        "workflow run failed: "
                                + (result.errorMessage() == null ? "(no message)" : result.errorMessage()));
            }
            return DispatchResult.fired(result.runId());
        } catch (Exception e) {
            log.error("Trigger {} dispatch failed for workflow {}: {}",
                    trigger.getId(), trigger.getTargetId(), e.getMessage(), e);
            return DispatchResult.failed("runner threw: " + e.getMessage());
        }
    }
}
