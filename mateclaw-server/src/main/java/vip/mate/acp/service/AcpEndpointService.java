package vip.mate.acp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vip.mate.acp.event.AcpEndpointChangedEvent;
import vip.mate.acp.model.AcpEndpointEntity;
import vip.mate.acp.repository.AcpEndpointMapper;
import vip.mate.exception.MateClawException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RFC-090 Phase 7 — CRUD layer for {@link AcpEndpointEntity}.
 *
 * <p>Keeps three guarantees:
 * <ol>
 *   <li>Builtin rows ({@code builtin=true}) cannot be hard-deleted —
 *       the user can only disable them. Mirrors {@code SkillService}.</li>
 *   <li>Names are unique; {@code create} validates against the live
 *       (non-deleted) set.</li>
 *   <li>{@code argsJson} / {@code envJson} round-trip through Jackson
 *       so the controller can hand structured data to the UI without
 *       leaking string-encoded JSON.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcpEndpointService {

    private static final Set<String> MANAGED_CODING_AGENT_NAMES = Set.of("hermes", "codex", "openclaw");
    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final AcpEndpointMapper mapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public List<AcpEndpointEntity> list() {
        return mapper.selectList(new LambdaQueryWrapper<AcpEndpointEntity>()
                .and(q -> q.eq(AcpEndpointEntity::getBuiltin, false)
                        .or()
                        .in(AcpEndpointEntity::getName, MANAGED_CODING_AGENT_NAMES))
                .orderByDesc(AcpEndpointEntity::getBuiltin)
                .orderByAsc(AcpEndpointEntity::getName));
    }

    /**
     * Workspace-scoped admin listing. Managed Hermes/Codex/OpenClaw rows are
     * global built-ins and are visible in every workspace; custom endpoints are
     * visible only inside the workspace that created them.
     */
    public List<AcpEndpointEntity> list(Long workspaceId) {
        LambdaQueryWrapper<AcpEndpointEntity> wrapper = new LambdaQueryWrapper<>();
        applyVisibleScope(wrapper, workspaceId);
        return mapper.selectList(wrapper
                .orderByDesc(AcpEndpointEntity::getBuiltin)
                .orderByAsc(AcpEndpointEntity::getName));
    }

    /**
     * Subset of {@link #list()} that returns only enabled rows.
     * Used by {@code AcpSkillBridge} to enumerate virtual skill cards
     * (one per enabled endpoint).
     */
    public List<AcpEndpointEntity> listEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<AcpEndpointEntity>()
                .eq(AcpEndpointEntity::getEnabled, true)
                .and(q -> q.eq(AcpEndpointEntity::getBuiltin, false)
                        .or()
                        .in(AcpEndpointEntity::getName, MANAGED_CODING_AGENT_NAMES))
                .orderByAsc(AcpEndpointEntity::getName));
    }

    public List<AcpEndpointEntity> listEnabled(Long workspaceId) {
        LambdaQueryWrapper<AcpEndpointEntity> wrapper = new LambdaQueryWrapper<AcpEndpointEntity>()
                .eq(AcpEndpointEntity::getEnabled, true);
        applyVisibleScope(wrapper, workspaceId);
        return mapper.selectList(wrapper.orderByAsc(AcpEndpointEntity::getName));
    }

    public AcpEndpointEntity get(Long id) {
        AcpEndpointEntity ep = mapper.selectById(id);
        if (ep == null) throw new MateClawException("err.acp.endpoint_not_found",
                "ACP endpoint not found: " + id);
        return ep;
    }

    public AcpEndpointEntity get(Long id, Long workspaceId) {
        AcpEndpointEntity ep = get(id);
        requireVisibleInWorkspace(ep, workspaceId);
        return ep;
    }

    public AcpEndpointEntity findByName(String name) {
        return mapper.selectOne(new LambdaQueryWrapper<AcpEndpointEntity>()
                .eq(AcpEndpointEntity::getName, name));
    }

    public AcpEndpointEntity create(AcpEndpointEntity input) {
        return create(input, DEFAULT_WORKSPACE_ID);
    }

    public AcpEndpointEntity create(AcpEndpointEntity input, Long workspaceId) {
        if (input.getName() == null || input.getName().isBlank()) {
            throw new MateClawException("err.acp.name_required", "ACP endpoint name is required");
        }
        if (input.getCommand() == null || input.getCommand().isBlank()) {
            throw new MateClawException("err.acp.command_required", "ACP endpoint command is required");
        }
        if (findByName(input.getName()) != null) {
            throw new MateClawException("err.acp.name_exists",
                    "ACP endpoint name already exists: " + input.getName());
        }
        // User-created rows are never builtin; default-enable false so a
        // misconfigured row can't auto-spawn a process at startup.
        input.setBuiltin(false);
        if (input.getEnabled() == null) input.setEnabled(false);
        if (input.getTrusted() == null) input.setTrusted(true);
        if (input.getToolParseMode() == null || input.getToolParseMode().isBlank()) {
            input.setToolParseMode("call_title");
        }
        if (input.getStdioBufferLimitBytes() == null || input.getStdioBufferLimitBytes() <= 0) {
            input.setStdioBufferLimitBytes(50L * 1024L * 1024L);
        }
        input.setWorkspaceId(normalizeWorkspaceId(workspaceId));
        mapper.insert(input);
        log.info("Created ACP endpoint: {}", input.getName());
        publish(input, AcpEndpointChangedEvent.Type.CREATED);
        return input;
    }

    public AcpEndpointEntity update(Long id, AcpEndpointEntity patch) {
        return update(id, patch, null);
    }

    public AcpEndpointEntity update(Long id, AcpEndpointEntity patch, Long workspaceId) {
        AcpEndpointEntity existing = get(id);
        if (workspaceId != null) requireVisibleInWorkspace(existing, workspaceId);
        if (Boolean.TRUE.equals(existing.getBuiltin())
                && patch.getCommand() != null
                && !patch.getCommand().equals(existing.getCommand())
                && !isManagedCodingAgent(existing.getName())) {
            throw new MateClawException("err.acp.builtin_command_locked",
                    "Builtin ACP endpoint command cannot be changed: " + existing.getName());
        }
        // Allow surgical updates: only fields the caller actually set.
        if (patch.getDisplayName() != null) existing.setDisplayName(patch.getDisplayName());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getCommand() != null) existing.setCommand(patch.getCommand());
        if (patch.getArgsJson() != null) existing.setArgsJson(patch.getArgsJson());
        if (patch.getEnvJson() != null) existing.setEnvJson(patch.getEnvJson());
        if (patch.getToolParseMode() != null) existing.setToolParseMode(patch.getToolParseMode());
        if (patch.getTrusted() != null) existing.setTrusted(patch.getTrusted());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        if (patch.getStdioBufferLimitBytes() != null && patch.getStdioBufferLimitBytes() > 0) {
            existing.setStdioBufferLimitBytes(patch.getStdioBufferLimitBytes());
        }
        mapper.updateById(existing);
        publish(existing, AcpEndpointChangedEvent.Type.UPDATED);
        return existing;
    }

    public void delete(Long id) {
        delete(id, null);
    }

    public void delete(Long id, Long workspaceId) {
        AcpEndpointEntity existing = get(id);
        if (workspaceId != null) requireVisibleInWorkspace(existing, workspaceId);
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new MateClawException("err.acp.builtin_readonly",
                    "Builtin ACP endpoint cannot be deleted: " + existing.getName());
        }
        mapper.deleteById(id);
        log.info("Deleted ACP endpoint: {}", existing.getName());
        publish(existing, AcpEndpointChangedEvent.Type.DELETED);
    }

    public AcpEndpointEntity toggle(Long id, boolean enabled) {
        return toggle(id, enabled, null);
    }

    public AcpEndpointEntity toggle(Long id, boolean enabled, Long workspaceId) {
        AcpEndpointEntity existing = get(id);
        if (workspaceId != null) requireVisibleInWorkspace(existing, workspaceId);
        existing.setEnabled(enabled);
        mapper.updateById(existing);
        publish(existing, AcpEndpointChangedEvent.Type.TOGGLED);
        return existing;
    }

    private void publish(AcpEndpointEntity ep, AcpEndpointChangedEvent.Type type) {
        try {
            eventPublisher.publishEvent(new AcpEndpointChangedEvent(
                    ep.getId(), ep.getName(), type));
        } catch (Exception e) {
            // Listener failures must not break the CRUD path. The bridge
            // will resync on the next ApplicationReady tick anyway.
            log.warn("Failed to publish AcpEndpointChangedEvent for '{}': {}",
                    ep.getName(), e.getMessage());
        }
    }

    /** Persist a connection-test outcome on the row. */
    public void recordTestResult(Long id, String status, String error) {
        AcpEndpointEntity existing = mapper.selectById(id);
        if (existing == null) return;
        existing.setLastStatus(status);
        existing.setLastTestedAt(LocalDateTime.now());
        existing.setLastError(error);
        mapper.updateById(existing);
    }

    public List<String> parseArgs(AcpEndpointEntity ep) {
        return parseStringList(ep.getArgsJson());
    }

    public Map<String, String> parseEnv(AcpEndpointEntity ep) {
        if (ep.getEnvJson() == null || ep.getEnvJson().isBlank()) return Map.of();
        try {
            return objectMapper.readValue(ep.getEnvJson(),
                    new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse env_json for ACP endpoint '{}': {}",
                    ep.getName(), e.getMessage());
            return Map.of();
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse args_json: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean isManagedCodingAgent(String name) {
        return MANAGED_CODING_AGENT_NAMES.contains(name);
    }

    /**
     * Wrapper tools are registered in the global Spring tool registry, but
     * custom ACP endpoints are workspace-owned. Agent graph construction calls
     * this to hide wrapper names whose backing endpoint is not visible in the
     * agent's workspace. Managed Hermes/Codex/OpenClaw rows remain global.
     */
    public Set<String> wrapperToolNamesNotVisibleInWorkspace(Long workspaceId) {
        long wsId = normalizeWorkspaceId(workspaceId);
        return listEnabled().stream()
                .filter(ep -> !isVisibleInWorkspace(ep, wsId))
                .map(this::wrapperToolName)
                .collect(Collectors.toSet());
    }

    private void applyVisibleScope(LambdaQueryWrapper<AcpEndpointEntity> wrapper, Long workspaceId) {
        long wsId = normalizeWorkspaceId(workspaceId);
        wrapper.and(q -> q
                .and(m -> m.eq(AcpEndpointEntity::getBuiltin, true)
                        .in(AcpEndpointEntity::getName, MANAGED_CODING_AGENT_NAMES))
                .or(m -> m.eq(AcpEndpointEntity::getBuiltin, false)
                        .eq(AcpEndpointEntity::getWorkspaceId, wsId)));
    }

    private void requireVisibleInWorkspace(AcpEndpointEntity endpoint, Long workspaceId) {
        if (endpoint == null) return;
        long requested = normalizeWorkspaceId(workspaceId);
        if (isVisibleInWorkspace(endpoint, requested)) {
            return;
        }
        throw new MateClawException("err.acp.endpoint_wrong_workspace", 403,
                "ACP endpoint does not belong to workspace " + requested + ": " + endpoint.getId());
    }

    private boolean isVisibleInWorkspace(AcpEndpointEntity endpoint, long workspaceId) {
        if (Boolean.TRUE.equals(endpoint.getBuiltin()) && isManagedCodingAgent(endpoint.getName())) {
            return true;
        }
        long owner = endpoint.getWorkspaceId() == null ? DEFAULT_WORKSPACE_ID : endpoint.getWorkspaceId();
        return owner == workspaceId;
    }

    private long normalizeWorkspaceId(Long workspaceId) {
        return workspaceId != null ? workspaceId : DEFAULT_WORKSPACE_ID;
    }

    private String wrapperToolName(AcpEndpointEntity endpoint) {
        return "acp_" + slugForEndpoint(endpoint) + "_prompt";
    }

    private String slugForEndpoint(AcpEndpointEntity endpoint) {
        String slug = slugify(endpoint != null ? endpoint.getName() : null);
        return hasAsciiAlphaNumeric(slug) ? slug : "acp-" + (endpoint != null ? endpoint.getId() : "unknown");
    }

    private String slugify(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    private static boolean hasAsciiAlphaNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) return true;
        }
        return false;
    }
}
