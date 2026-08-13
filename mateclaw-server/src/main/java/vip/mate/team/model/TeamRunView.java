package vip.mate.team.model;

import java.time.LocalDateTime;
import java.util.List;

/** Stable read projection for a team run and its tasks. */
public record TeamRunView(
        Long id,
        Long teamId,
        Long workspaceId,
        Long leadAgentId,
        String leadConversationId,
        Long originMessageId,
        String title,
        String objective,
        String status,
        String finalSummary,
        String stopReason,
        String metadata,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        Progress progress,
        List<Task> tasks
) {

    public record Progress(int total, int done, int failed, int inReview, int percent) {
    }

    public record Task(
            Long id,
            Long teamId,
            Long runId,
            Integer taskNumber,
            String subject,
            String description,
            String status,
            Integer priority,
            String taskType,
            Long assigneeAgentId,
            Long ownerAgentId,
            String blockedBy,
            Boolean requireApproval,
            Integer progressPercent,
            String progressStep,
            String result,
            String reason,
            String conversationId,
            String metadata,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {

        public static Task from(TeamTaskEntity task) {
            return new Task(task.getId(), task.getTeamId(), task.getRunId(), task.getTaskNumber(),
                    task.getSubject(), task.getDescription(), task.getStatus(), task.getPriority(),
                    task.getTaskType(), task.getAssigneeAgentId(), task.getOwnerAgentId(),
                    task.getBlockedBy(), task.getRequireApproval(), task.getProgressPercent(),
                    task.getProgressStep(), task.getResult(), task.getReason(), task.getConversationId(),
                    task.getMetadata(), task.getCreateTime(), task.getUpdateTime());
        }
    }
}
