package vip.mate.agent.team.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.agent.team.dto.AgentTeamDtos;
import vip.mate.agent.team.dto.AgentTeamDtos.TeamVO;
import vip.mate.agent.team.service.AgentTeamService;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.result.R;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

@Tag(name = "Agent Team 管理")
@RestController
@RequestMapping("/api/v1/agent-teams")
@RequiredArgsConstructor
public class AgentTeamController {

    private final AgentTeamService teamService;
    private final AuditEventService auditEventService;

    @Operation(summary = "List agent teams")
    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<List<TeamVO>> list(@RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        return R.ok(teamService.list(wsId));
    }

    @Operation(summary = "Get agent team")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("viewer")
    public R<TeamVO> get(@PathVariable long id,
                         @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        return R.ok(teamService.get(id, wsId));
    }

    @Operation(summary = "Create agent team")
    @PostMapping
    @RequireWorkspaceRole("member")
    public R<TeamVO> create(@RequestBody AgentTeamDtos.CreateTeamRequest request,
                            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        TeamVO created = teamService.create(wsId, request);
        auditEventService.record("CREATE", "AGENT_TEAM", String.valueOf(created.id()), created.name(), null);
        return R.ok(created);
    }

    @Operation(summary = "Update agent team")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("member")
    public R<TeamVO> update(@PathVariable long id,
                            @RequestBody AgentTeamDtos.UpdateTeamRequest request,
                            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        TeamVO updated = teamService.update(id, wsId, request);
        auditEventService.record("UPDATE", "AGENT_TEAM", String.valueOf(updated.id()), updated.name(), null);
        return R.ok(updated);
    }

    @Operation(summary = "Delete agent team")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("member")
    public R<Void> delete(@PathVariable long id,
                          @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        teamService.delete(id, wsId);
        auditEventService.record("DELETE", "AGENT_TEAM", String.valueOf(id), null, null);
        return R.ok();
    }
}
