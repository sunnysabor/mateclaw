package vip.mate.dify.api;

import java.time.LocalDateTime;

public record DifyWorkflowConfigVO(
        Long id,
        String name,
        String description,
        String baseUrl,
        Boolean enabled,
        Boolean apiKeyConfigured,
        String inputSchemaJson,
        String defaultInputsJson,
        String lastTestStatus,
        String lastTestError,
        LocalDateTime lastTestAt,
        LocalDateTime updateTime
) {
}
