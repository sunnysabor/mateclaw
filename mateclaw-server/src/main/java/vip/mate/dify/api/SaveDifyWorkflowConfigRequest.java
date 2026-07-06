package vip.mate.dify.api;

public record SaveDifyWorkflowConfigRequest(
        String name,
        String description,
        String apiKey,
        Boolean enabled,
        String inputSchemaJson,
        String defaultInputsJson
) {
}
