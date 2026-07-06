package vip.mate.dify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.dify.api.DifyWorkflowConfigVO;
import vip.mate.dify.api.DifyWorkflowRunVO;
import vip.mate.dify.api.SaveDifyWorkflowConfigRequest;
import vip.mate.dify.client.DifyWorkflowClient;
import vip.mate.dify.model.DifyWorkflowConfigEntity;
import vip.mate.dify.model.ExternalWorkflowRunEntity;
import vip.mate.dify.repository.DifyWorkflowConfigMapper;
import vip.mate.dify.repository.ExternalWorkflowRunMapper;
import vip.mate.exception.MateClawException;
import vip.mate.trigger.model.TriggerEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class DifyWorkflowService {

    public static final String GLOBAL_CONFIG_KEY = "global";
    public static final String PROVIDER = "dify";

    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final DifyWorkflowConfigMapper configMapper;
    private final ExternalWorkflowRunMapper runMapper;
    private final DifyWorkflowClient client;
    private final DifySecretCipher cipher;
    private final ObjectMapper objectMapper;
    private final AuthService authService;

    public DifyWorkflowConfigEntity getConfigEntity() {
        return configMapper.selectOne(new LambdaQueryWrapper<DifyWorkflowConfigEntity>()
                .eq(DifyWorkflowConfigEntity::getConfigKey, GLOBAL_CONFIG_KEY)
                .last("LIMIT 1"));
    }

    public DifyWorkflowConfigVO getConfig() {
        return toConfigVO(ensureConfig(false));
    }

    @Transactional
    public DifyWorkflowConfigVO saveConfig(SaveDifyWorkflowConfigRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new MateClawException("err.dify.name_required", 400,
                    "Dify workflow name is required");
        }
        DifyWorkflowConfigEntity row = getConfigEntity();
        if (row == null) {
            row = new DifyWorkflowConfigEntity();
            row.setConfigKey(GLOBAL_CONFIG_KEY);
            row.setDeleted(0);
            row.setCreatedBy(currentUserId());
        }
        row.setName(request.name().trim());
        row.setDescription(blankToNull(request.description()));
        row.setEnabled(request.enabled() == null || Boolean.TRUE.equals(request.enabled()));
        row.setInputSchemaJson(normalizeOptionalJsonObject(request.inputSchemaJson()));
        row.setDefaultInputsJson(normalizeOptionalJsonObject(request.defaultInputsJson()));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            row.setApiKeyCipher(cipher.encrypt(request.apiKey()));
        }
        if (row.getId() == null) {
            configMapper.insert(row);
        } else {
            configMapper.updateById(row);
        }
        return toConfigVO(row);
    }

    public DifyWorkflowRunVO testRun(long workspaceId, Map<String, Object> inputs) {
        DifyWorkflowRunVO run = run(workspaceId, inputs, null, "test", currentUserId());
        DifyWorkflowConfigEntity config = getConfigEntity();
        if (config != null) {
            config.setLastTestStatus(run.state());
            config.setLastTestError(run.errorMessage());
            config.setLastTestAt(LocalDateTime.now());
            configMapper.updateById(config);
        }
        return run;
    }

    public DifyWorkflowRunVO manualRun(long workspaceId, Map<String, Object> inputs) {
        return run(workspaceId, inputs, null, "manual", currentUserId());
    }

    public DifyWorkflowRunVO runFromTrigger(TriggerEntity trigger, Map<String, Object> inputs) {
        if (trigger == null || trigger.getWorkspaceId() == null || trigger.getId() == null) {
            throw new MateClawException("err.dify.trigger_invalid", 400,
                    "Invalid Dify trigger context");
        }
        DifyWorkflowConfigEntity config = ensureConfig(true);
        if (!Objects.equals(config.getId(), trigger.getTargetId())) {
            throw new MateClawException("err.dify.config_missing", 400,
                    "Dify workflow trigger target does not match the global config");
        }
        return run(trigger.getWorkspaceId(), inputs, trigger.getId(),
                "trigger:" + trigger.getId(), null);
    }

    public List<DifyWorkflowRunVO> listRuns(int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        return runMapper.selectList(new LambdaQueryWrapper<ExternalWorkflowRunEntity>()
                        .eq(ExternalWorkflowRunEntity::getProvider, PROVIDER)
                        .eq(ExternalWorkflowRunEntity::getDeleted, 0)
                        .orderByDesc(ExternalWorkflowRunEntity::getCreateTime)
                        .last("LIMIT " + capped))
                .stream()
                .map(this::toRunVO)
                .toList();
    }

    public DifyWorkflowRunVO getRun(long runId) {
        ExternalWorkflowRunEntity row = runMapper.selectById(runId);
        if (row == null || (row.getDeleted() != null && row.getDeleted() != 0)) {
            throw new MateClawException("err.dify.run_not_found", 404,
                    "Dify workflow run not found: " + runId);
        }
        return toRunVO(row);
    }

    protected DifyWorkflowRunVO run(long workspaceId,
                                    Map<String, Object> inputs,
                                    Long triggerId,
                                    String triggeredBy,
                                    Long createdBy) {
        validateWorkspace(workspaceId);
        validateInputs(inputs);
        DifyWorkflowConfigEntity config = ensureRunnableConfig();
        String apiKey = cipher.decrypt(config.getApiKeyCipher(), config.getId());
        String difyUser = triggerId == null
                ? "ws-" + workspaceId + ":user-" + (createdBy == null ? "system" : createdBy)
                : "ws-" + workspaceId + ":trigger-" + triggerId;

        ExternalWorkflowRunEntity run = new ExternalWorkflowRunEntity();
        run.setWorkspaceId(workspaceId);
        run.setProvider(PROVIDER);
        run.setConfigId(config.getId());
        run.setTriggerId(triggerId);
        run.setState("running");
        run.setRequestInputsJson(writeJson(inputs));
        run.setTriggeredBy(triggeredBy);
        run.setCreatedBy(createdBy);
        run.setStartedAt(LocalDateTime.now());
        run.setDeleted(0);
        runMapper.insert(run);

        try {
            DifyWorkflowClient.RunResponse response = client.run(apiKey, inputs, difyUser);
            applySuccess(run, response);
        } catch (DifyWorkflowClient.DifyClientException e) {
            applyFailure(run, e.errorCode(), e.getMessage(), e.rawResponse());
        } catch (MateClawException e) {
            applyFailure(run, "mateclaw_error", e.getMessage(), null);
        } catch (Exception e) {
            applyFailure(run, "unexpected_error", e.getMessage(), null);
        }
        runMapper.updateById(run);
        return toRunVO(run);
    }

    private DifyWorkflowConfigEntity ensureConfig(boolean required) {
        DifyWorkflowConfigEntity row = getConfigEntity();
        if (row == null && required) {
            throw new MateClawException("err.dify.config_missing", 400,
                    "Dify workflow config is not configured");
        }
        if (row == null) {
            row = new DifyWorkflowConfigEntity();
            row.setConfigKey(GLOBAL_CONFIG_KEY);
            row.setName("Dify Workflow");
            row.setEnabled(false);
            row.setDeleted(0);
        }
        return row;
    }

    private DifyWorkflowConfigEntity ensureRunnableConfig() {
        DifyWorkflowConfigEntity config = ensureConfig(true);
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new MateClawException("err.dify.disabled", 400,
                    "Dify workflow config is disabled");
        }
        if (config.getApiKeyCipher() == null || config.getApiKeyCipher().isBlank()) {
            throw new MateClawException("err.dify.api_key_required", 400,
                    "Dify API key is not configured");
        }
        return config;
    }

    private void applySuccess(ExternalWorkflowRunEntity run, DifyWorkflowClient.RunResponse response) {
        JsonNode root = response.root();
        JsonNode data = root.path("data");
        String difyStatus = data.path("status").asText(root.path("status").asText("succeeded"));
        run.setState(mapState(difyStatus));
        run.setExternalTaskId(textOrNull(root.path("task_id")));
        run.setExternalRunId(textOrNull(root.path("workflow_run_id")));
        if (run.getExternalRunId() == null) run.setExternalRunId(textOrNull(data.path("id")));
        run.setExternalWorkflowId(textOrNull(data.path("workflow_id")));
        run.setResponseOutputsJson(data.has("outputs") && !data.get("outputs").isMissingNode()
                ? data.get("outputs").toString()
                : null);
        run.setResponseRawJson(response.rawJson());
        run.setErrorMessage(textOrNull(data.path("error")));
        run.setTotalTokens(intOrNull(data.path("total_tokens")));
        run.setTotalSteps(intOrNull(data.path("total_steps")));
        if (data.hasNonNull("elapsed_time")) {
            run.setElapsedTimeSeconds(BigDecimal.valueOf(data.get("elapsed_time").asDouble()));
        }
        run.setCompletedAt(resolveFinishedAt(data));
        if ("failed".equalsIgnoreCase(run.getState()) && run.getErrorCode() == null) {
            run.setErrorCode("dify_failed");
        }
    }

    private void applyFailure(ExternalWorkflowRunEntity run, String code, String message, String rawResponse) {
        run.setState("failed");
        run.setErrorCode(code == null ? "dify_error" : code);
        run.setErrorMessage(truncate(message, 2048));
        run.setResponseRawJson(rawResponse);
        run.setCompletedAt(LocalDateTime.now());
    }

    private LocalDateTime resolveFinishedAt(JsonNode data) {
        if (data.hasNonNull("finished_at")) {
            long epoch = data.get("finished_at").asLong();
            if (epoch > 0) return LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneId.systemDefault());
        }
        return LocalDateTime.now();
    }

    private String mapState(String difyStatus) {
        return switch (difyStatus == null ? "" : difyStatus) {
            case "succeeded" -> "succeeded";
            case "failed" -> "failed";
            case "stopped" -> "cancelled";
            case "partial-succeeded" -> "partial_succeeded";
            case "paused" -> "paused";
            default -> difyStatus == null || difyStatus.isBlank() ? "succeeded" : difyStatus;
        };
    }

    private DifyWorkflowConfigVO toConfigVO(DifyWorkflowConfigEntity row) {
        return new DifyWorkflowConfigVO(
                row.getId(),
                row.getName(),
                row.getDescription(),
                DifyWorkflowClient.BASE_URL,
                row.getEnabled(),
                row.getApiKeyCipher() != null && !row.getApiKeyCipher().isBlank(),
                row.getInputSchemaJson(),
                row.getDefaultInputsJson(),
                row.getLastTestStatus(),
                row.getLastTestError(),
                row.getLastTestAt(),
                row.getUpdateTime());
    }

    private DifyWorkflowRunVO toRunVO(ExternalWorkflowRunEntity row) {
        return new DifyWorkflowRunVO(
                row.getId(),
                row.getWorkspaceId(),
                row.getState(),
                readMap(row.getRequestInputsJson()),
                readMap(row.getResponseOutputsJson()),
                readRaw(row.getResponseRawJson()),
                row.getExternalTaskId(),
                row.getExternalRunId(),
                row.getExternalWorkflowId(),
                row.getErrorCode(),
                row.getErrorMessage(),
                row.getTotalTokens(),
                row.getTotalSteps(),
                row.getElapsedTimeSeconds(),
                row.getTriggeredBy(),
                row.getCreatedBy(),
                row.getCreateTime(),
                row.getCompletedAt());
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, MAP_REF);
        } catch (Exception e) {
            return Map.of("_raw", json);
        }
    }

    private Object readRaw(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String writeJson(Map<String, Object> inputs) {
        try {
            return objectMapper.writeValueAsString(inputs == null ? Map.of() : inputs);
        } catch (Exception e) {
            throw new MateClawException("err.dify.input_invalid", 400,
                    "Dify inputs must be a JSON object");
        }
    }

    private void validateInputs(Map<String, Object> inputs) {
        if (inputs == null) {
            throw new MateClawException("err.dify.input_invalid", 400,
                    "Dify inputs must be a JSON object");
        }
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw new MateClawException("err.dify.workspace_required", 400,
                    "X-Workspace-Id is required");
        }
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        UserEntity user = authService.findByUsername(auth.getName());
        return user == null ? null : user.getId();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String normalizeOptionalJsonObject(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw new MateClawException("err.dify.input_invalid", 400,
                        "Dify JSON settings must be JSON objects");
            }
            return objectMapper.writeValueAsString(node);
        } catch (MateClawException e) {
            throw e;
        } catch (Exception e) {
            throw new MateClawException("err.dify.input_invalid", 400,
                    "Dify JSON settings must be valid JSON objects");
        }
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private Integer intOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return node.asInt();
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
