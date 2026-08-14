package vip.mate.team.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamRunCreateCommand;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.repository.TeamRunMapper;
import vip.mate.team.repository.TeamTaskMapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Owns team run creation, lifecycle transitions, authorization, and reads. */
@Service
@Slf4j
public class TeamRunService {

    public record SealResult(TeamRunEntity run, boolean transitioned) {
    }

    public record CancelResult(TeamRunEntity run, boolean transitioned) {
    }

    private static final int MAX_TITLE_LENGTH = 255;
    private static final Set<String> FINAL_OUTCOMES = Set.of(
            TeamRunStatus.COMPLETED, TeamRunStatus.PARTIAL, TeamRunStatus.FAILED);

    private final TeamRunMapper runMapper;
    private final TeamTaskMapper taskMapper;
    private final TeamService teamService;
    private final TeamRunStateMachine stateMachine;

    public TeamRunService(TeamRunMapper runMapper, TeamTaskMapper taskMapper, TeamService teamService) {
        this.runMapper = runMapper;
        this.taskMapper = taskMapper;
        this.teamService = teamService;
        this.stateMachine = new TeamRunStateMachine();
    }

    public TeamRunEntity startRun(TeamRunCreateCommand command) {
        validateCreate(command);
        TeamRunEntity existing = findByOrigin(command.getWorkspaceId(), command.getLeadConversationId(),
                command.getOriginMessageId());
        if (existing != null) {
            return existing;
        }

        TeamRunEntity run = new TeamRunEntity();
        run.setTeamId(command.getTeamId());
        run.setWorkspaceId(command.getWorkspaceId());
        run.setLeadAgentId(command.getLeadAgentId());
        run.setLeadConversationId(command.getLeadConversationId());
        run.setOriginMessageId(command.getOriginMessageId());
        run.setTitle(deriveTitle(command));
        run.setObjective(command.getObjective().trim());
        run.setStatus(TeamRunStatus.PLANNING);
        run.setMetadata(command.getMetadata());
        try {
            runMapper.insert(run);
            return run;
        } catch (DuplicateKeyException duplicate) {
            TeamRunEntity winner = findByOrigin(command.getWorkspaceId(), command.getLeadConversationId(),
                    command.getOriginMessageId());
            if (winner != null) {
                return winner;
            }
            throw duplicate;
        }
    }

    public TeamRunEntity requireRun(Long runId, Long workspaceId) {
        TeamRunEntity run = runMapper.selectById(runId);
        if (run == null || workspaceId == null || !workspaceId.equals(run.getWorkspaceId())) {
            throw new IllegalArgumentException("team run not found in workspace: " + runId);
        }
        return run;
    }

    public Set<Long> findPlanningRunIds(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Set.of();
        }
        return runMapper.selectBatchIds(runIds).stream()
                .filter(run -> TeamRunStatus.PLANNING.equals(run.getStatus()))
                .map(TeamRunEntity::getId)
                .collect(Collectors.toSet());
    }

    public TeamRunView getRun(Long runId, Long workspaceId) {
        return buildView(requireRun(runId, workspaceId));
    }

    /** Reconciles runs stranded after the optional LLM summary step failed. */
    @Scheduled(fixedDelayString = "${mateclaw.team.finalizing-reconcile-ms:30000}", initialDelay = 30000)
    @Transactional
    public void reconcileFinalizingRuns() {
        List<TeamRunEntity> runs = runMapper.selectList(Wrappers.<TeamRunEntity>lambdaQuery()
                .eq(TeamRunEntity::getStatus, TeamRunStatus.FINALIZING));
        for (TeamRunEntity run : runs) {
            List<TeamTaskEntity> tasks = tasksForRun(run.getId());
            if (tasks.isEmpty() || tasks.stream().anyMatch(task -> !TeamTaskStatus.isTerminal(task.getStatus()))) {
                continue;
            }
            String outcome = tasks.stream().allMatch(task -> TeamTaskStatus.COMPLETED.equals(task.getStatus()))
                    ? TeamRunStatus.COMPLETED
                    : tasks.stream().anyMatch(task -> TeamTaskStatus.COMPLETED.equals(task.getStatus()))
                    ? TeamRunStatus.PARTIAL : TeamRunStatus.FAILED;
            String fallback = "执行摘要（汇总模型不可用，以下为步骤原始结果）：\n"
                    + tasks.stream().map(task -> {
                        String result = TeamTaskStatus.COMPLETED.equals(task.getStatus())
                                ? task.getResult() : task.getReason();
                        return "- #" + task.getTaskNumber() + " " + (result == null ? task.getStatus() : result);
                    }).collect(Collectors.joining("\n"));
            finalizeWithoutSummary(run, outcome, fallback);
        }
    }

    private void finalizeWithoutSummary(TeamRunEntity run, String outcome, String summary) {
        LocalDateTime completedAt = LocalDateTime.now();
        runMapper.update(null, Wrappers.<TeamRunEntity>lambdaUpdate()
                .eq(TeamRunEntity::getId, run.getId())
                .eq(TeamRunEntity::getStatus, TeamRunStatus.FINALIZING)
                .set(TeamRunEntity::getStatus, outcome)
                .set(TeamRunEntity::getFinalSummary, summary)
                .set(TeamRunEntity::getCompletedAt, completedAt));
        log.warn("Reconciled stranded team run {} from finalizing to {}", run.getId(), outcome);
    }

    public List<TeamRunView> listTeamRuns(Long teamId, Long workspaceId) {
        return listTeamRuns(teamId, workspaceId, false);
    }

    public List<TeamRunView> listTeamRuns(Long teamId, Long workspaceId, boolean activeOnly) {
        var query = Wrappers.<TeamRunEntity>lambdaQuery()
                        .eq(TeamRunEntity::getTeamId, teamId)
                        .eq(TeamRunEntity::getWorkspaceId, workspaceId)
                        .orderByDesc(TeamRunEntity::getCreateTime);
        if (activeOnly) {
            query.in(TeamRunEntity::getStatus, TeamRunStatus.PLANNING, TeamRunStatus.RUNNING,
                    TeamRunStatus.AWAITING_REVIEW, TeamRunStatus.FINALIZING);
        }
        return runMapper.selectList(query)
                .stream().map(this::buildView).toList();
    }

    public List<TeamRunView> listConversationRuns(String conversationId, Long workspaceId) {
        return runMapper.selectList(Wrappers.<TeamRunEntity>lambdaQuery()
                        .eq(TeamRunEntity::getLeadConversationId, conversationId)
                        .eq(TeamRunEntity::getWorkspaceId, workspaceId)
                        .orderByDesc(TeamRunEntity::getCreateTime))
                .stream().map(this::buildView).toList();
    }

    @Transactional
    public TeamRunEntity sealRun(Long runId, Long workspaceId) {
        return sealRunWithResult(runId, workspaceId).run();
    }

    @Transactional
    public SealResult sealRunWithResult(Long runId, Long workspaceId) {
        TeamRunEntity run = requireRun(runId, workspaceId);
        if (!TeamRunStatus.PLANNING.equals(run.getStatus())) {
            return new SealResult(run, false);
        }
        long taskCount = taskMapper.selectCount(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getRunId, runId));
        if (taskCount == 0) {
            throw new IllegalStateException("cannot seal a team run without tasks");
        }

        LocalDateTime startedAt = LocalDateTime.now();
        int changed = runMapper.update(null, Wrappers.<TeamRunEntity>lambdaUpdate()
                .eq(TeamRunEntity::getId, runId)
                .eq(TeamRunEntity::getStatus, TeamRunStatus.PLANNING)
                .set(TeamRunEntity::getStatus, TeamRunStatus.RUNNING)
                .set(TeamRunEntity::getStartedAt, startedAt));
        if (changed == 1) {
            run.setStatus(TeamRunStatus.RUNNING);
            run.setStartedAt(startedAt);
            return new SealResult(run, true);
        }
        TeamRunEntity current = requireRun(runId, workspaceId);
        if (!TeamRunStatus.PLANNING.equals(current.getStatus())) {
            return new SealResult(current, false);
        }
        throw new IllegalStateException("failed to seal team run: " + runId);
    }

    @Transactional
    public TeamRunEntity markFinalized(Long runId, Long workspaceId, String finalSummary) {
        TeamRunEntity run = requireRun(runId, workspaceId);
        if (TeamRunStatus.isTerminal(run.getStatus())) {
            return run;
        }
        if (!TeamRunStatus.FINALIZING.equals(run.getStatus())) {
            throw new IllegalStateException("team run is not finalizing: " + runId);
        }
        String outcome = metadata(run.getMetadata()).getStr("projectedOutcome");
        if (!FINAL_OUTCOMES.contains(outcome)) {
            throw new IllegalStateException("team run has no valid projected outcome: " + runId);
        }

        LocalDateTime completedAt = LocalDateTime.now();
        int changed = runMapper.update(null, Wrappers.<TeamRunEntity>lambdaUpdate()
                .eq(TeamRunEntity::getId, runId)
                .eq(TeamRunEntity::getStatus, TeamRunStatus.FINALIZING)
                .set(TeamRunEntity::getStatus, outcome)
                .set(TeamRunEntity::getFinalSummary, finalSummary)
                .set(TeamRunEntity::getCompletedAt, completedAt));
        if (changed == 1) {
            run.setStatus(outcome);
            run.setFinalSummary(finalSummary);
            run.setCompletedAt(completedAt);
            return run;
        }
        TeamRunEntity current = requireRun(runId, workspaceId);
        if (TeamRunStatus.isTerminal(current.getStatus())) {
            return current;
        }
        throw new IllegalStateException("failed to finalize team run: " + runId);
    }

    @Transactional
    public TeamRunEntity cancelRun(Long runId, Long workspaceId, String reason) {
        return cancelRunWithResult(runId, workspaceId, reason).run();
    }

    @Transactional
    public CancelResult cancelRunWithResult(Long runId, Long workspaceId, String reason) {
        TeamRunEntity run = requireRun(runId, workspaceId);
        if (TeamRunStatus.isTerminal(run.getStatus())) {
            return new CancelResult(run, false);
        }
        LocalDateTime completedAt = LocalDateTime.now();
        int changed = runMapper.update(null, Wrappers.<TeamRunEntity>lambdaUpdate()
                .eq(TeamRunEntity::getId, runId)
                .notIn(TeamRunEntity::getStatus, TeamRunStatus.TERMINAL)
                .set(TeamRunEntity::getStatus, TeamRunStatus.CANCELLED)
                .set(TeamRunEntity::getStopReason, reason)
                .set(TeamRunEntity::getCompletedAt, completedAt));
        if (changed == 1) {
            run.setStatus(TeamRunStatus.CANCELLED);
            run.setStopReason(reason);
            run.setCompletedAt(completedAt);
            return new CancelResult(run, true);
        }
        return new CancelResult(requireRun(runId, workspaceId), false);
    }

    public TeamRunView buildView(TeamRunEntity run) {
        List<TeamTaskEntity> tasks = tasksForRun(run.getId());
        TeamRunStateMachine.Projection projection = stateMachine.project(run, tasks);
        return new TeamRunView(run.getId(), run.getTeamId(), run.getWorkspaceId(), run.getLeadAgentId(),
                run.getLeadConversationId(), run.getOriginMessageId(), run.getTitle(), run.getObjective(),
                projection.status(), run.getFinalSummary(), run.getStopReason(), run.getMetadata(),
                run.getStartedAt(), run.getCompletedAt(), run.getCreateTime(), run.getUpdateTime(),
                projection.progress(), tasks.stream().map(TeamRunView.Task::from).toList());
    }

    private List<TeamTaskEntity> tasksForRun(Long runId) {
        return taskMapper.selectList(Wrappers.<TeamTaskEntity>lambdaQuery()
                .eq(TeamTaskEntity::getRunId, runId)
                .orderByAsc(TeamTaskEntity::getTaskNumber));
    }

    private void validateCreate(TeamRunCreateCommand command) {
        if (command == null || command.getTeamId() == null || command.getWorkspaceId() == null
                || command.getLeadAgentId() == null) {
            throw new IllegalArgumentException("team, workspace, and lead are required");
        }
        AgentTeamEntity team = teamService.getTeam(command.getTeamId());
        if (team == null || !TeamService.STATUS_ACTIVE.equals(team.getStatus())) {
            throw new IllegalArgumentException("team not found or not active: " + command.getTeamId());
        }
        if (!command.getWorkspaceId().equals(team.getWorkspaceId())) {
            throw new IllegalArgumentException("team is not in workspace: " + command.getWorkspaceId());
        }
        if (!command.getLeadAgentId().equals(team.getLeadAgentId())) {
            throw new IllegalArgumentException("agent is not the team lead: " + command.getLeadAgentId());
        }
        if (command.getLeadConversationId() == null || command.getLeadConversationId().isBlank()) {
            throw new IllegalArgumentException("lead conversation is required");
        }
        if (command.getObjective() == null || command.getObjective().isBlank()) {
            throw new IllegalArgumentException("objective is required");
        }
    }

    private TeamRunEntity findByOrigin(Long workspaceId, String conversationId, Long originMessageId) {
        if (originMessageId == null) {
            return null;
        }
        return runMapper.selectOne(Wrappers.<TeamRunEntity>lambdaQuery()
                .eq(TeamRunEntity::getWorkspaceId, workspaceId)
                .eq(TeamRunEntity::getLeadConversationId, conversationId)
                .eq(TeamRunEntity::getOriginMessageId, originMessageId));
    }

    private String deriveTitle(TeamRunCreateCommand command) {
        String title = command.getTitle() == null || command.getTitle().isBlank()
                ? command.getObjective().trim() : command.getTitle().trim();
        return title.length() <= MAX_TITLE_LENGTH ? title : title.substring(0, MAX_TITLE_LENGTH);
    }

    private JSONObject metadata(String value) {
        if (value == null || value.isBlank()) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(value);
        } catch (RuntimeException invalidJson) {
            return new JSONObject();
        }
    }
}
