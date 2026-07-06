package vip.mate.dify.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.dify.service.DifyWorkflowService;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;

import java.util.List;
import java.util.Map;

@Tag(name = "Dify Workflow")
@RestController
@RequestMapping("/api/v1/dify")
@RequiredArgsConstructor
public class DifyWorkflowController {

    private final DifyWorkflowService service;

    @Operation(summary = "Get global Dify workflow config")
    @GetMapping("/workflow/config")
    @RequireGlobalAdmin
    public R<DifyWorkflowConfigVO> getConfig() {
        return R.ok(service.getConfig());
    }

    @Operation(summary = "Save global Dify workflow config")
    @PutMapping("/workflow/config")
    @RequireGlobalAdmin
    public R<DifyWorkflowConfigVO> saveConfig(@RequestBody SaveDifyWorkflowConfigRequest request) {
        return R.ok(service.saveConfig(request));
    }

    @Operation(summary = "Test-run the configured Dify workflow")
    @PostMapping("/workflow/test")
    @RequireGlobalAdmin
    public R<DifyWorkflowRunVO> test(@RequestBody(required = false) RunDifyWorkflowRequest request,
                                     @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.testRun(requireWorkspace(workspaceId), inputs(request)));
    }

    @Operation(summary = "Run the configured Dify workflow manually")
    @PostMapping("/workflow/run")
    @RequireGlobalAdmin
    public R<DifyWorkflowRunVO> run(@RequestBody RunDifyWorkflowRequest request,
                                    @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.manualRun(requireWorkspace(workspaceId), inputs(request)));
    }

    @Operation(summary = "List recent Dify workflow runs")
    @GetMapping("/workflow/runs")
    @RequireGlobalAdmin
    public R<List<DifyWorkflowRunVO>> listRuns(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return R.ok(service.listRuns(limit));
    }

    @Operation(summary = "Get Dify workflow run detail")
    @GetMapping("/runs/{runId}")
    @RequireGlobalAdmin
    public R<DifyWorkflowRunVO> getRun(@PathVariable long runId) {
        return R.ok(service.getRun(runId));
    }

    private long requireWorkspace(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new MateClawException("err.dify.workspace_required", 400,
                    "X-Workspace-Id is required");
        }
        return workspaceId;
    }

    private Map<String, Object> inputs(RunDifyWorkflowRequest request) {
        if (request == null || request.inputs() == null) {
            throw new MateClawException("err.dify.input_invalid", 400,
                    "Dify inputs must be a JSON object");
        }
        return request.inputs();
    }
}
