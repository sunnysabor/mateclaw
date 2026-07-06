package vip.mate.dify.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record DifyWorkflowRunVO(
        Long id,
        Long workspaceId,
        String state,
        Map<String, Object> requestInputs,
        Map<String, Object> responseOutputs,
        Object responseRaw,
        String externalTaskId,
        String externalRunId,
        String externalWorkflowId,
        String errorCode,
        String errorMessage,
        Integer totalTokens,
        Integer totalSteps,
        BigDecimal elapsedTimeSeconds,
        String triggeredBy,
        Long createdBy,
        LocalDateTime createTime,
        LocalDateTime completedAt
) {
}
