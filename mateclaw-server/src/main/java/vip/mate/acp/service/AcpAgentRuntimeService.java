package vip.mate.acp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import vip.mate.acp.client.AcpStdioClient;
import vip.mate.acp.model.AcpEndpointEntity;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Conversation-scoped runtime for first-class ACP Agents.
 *
 * <p>This differs from {@link AcpDelegationService}: it keeps one ACP process
 * and session alive per {@code agentId + conversationId}, so Hermes/Codex/
 * OpenClaw can maintain native conversational context while the user keeps
 * talking. Idle sessions are closed after 30 minutes; the next turn opens a
 * fresh ACP session and injects a compact recovery prompt built from persisted
 * MateClaw messages.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcpAgentRuntimeService implements DisposableBean {

    public static final Set<String> MANAGED_ENDPOINTS = Set.of("hermes", "codex", "openclaw");
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private static final long INITIALIZE_TIMEOUT_MS = 15_000L;
    private static final long SESSION_NEW_TIMEOUT_MS = 10_000L;
    private static final long PROMPT_TIMEOUT_MS = Duration.ofMinutes(10).toMillis();
    private static final int RECOVERY_RECENT_MESSAGES = 20;

    private final ObjectMapper objectMapper;
    private final AcpEndpointService endpointService;
    private final AcpRuntimeSupport runtimeSupport;
    private final ConversationService conversationService;

    private final Map<SessionKey, ManagedSession> sessions = new ConcurrentHashMap<>();

    public boolean isAcpAgent(AgentEntity agent) {
        return agent != null && "acp".equalsIgnoreCase(agent.getAgentType());
    }

    public String normalizeEndpointName(String endpointName) {
        if (endpointName == null) return null;
        String slug = endpointName.trim().toLowerCase(java.util.Locale.ROOT);
        return MANAGED_ENDPOINTS.contains(slug) ? slug : null;
    }

    public void validateAcpAgent(AgentEntity agent) {
        if (!isAcpAgent(agent)) return;
        String slug = normalizeEndpointName(agent.getAcpEndpointName());
        if (slug == null) {
            throw new MateClawException("err.agent.acp_endpoint_invalid", 400,
                    "ACP Agent 只能选择 Hermes、Codex 或 OpenClaw");
        }
        AcpEndpointEntity endpoint = endpointService.findByName(slug);
        if (endpoint == null) {
            throw new MateClawException("err.agent.acp_endpoint_missing", 400,
                    "ACP endpoint 不存在: " + slug);
        }
        ensureEndpointVisibleToAgent(endpoint, agent);
        if (!Boolean.TRUE.equals(endpoint.getEnabled())) {
            throw new MateClawException("err.agent.acp_endpoint_disabled", 400,
                    "ACP endpoint 未启用: " + slug + "，请先到设置 -> ACP 启用");
        }
        agent.setAgentType("acp");
        agent.setAcpEndpointName(slug);
        // First version: ACP employees are external runtimes, not MateClaw
        // graph agents, so local capabilities stay disabled and unbound.
        agent.setSkillsDisabled(true);
        agent.setToolsDisabled(true);
        agent.setWikiDisabled(true);
    }

    public Flux<AgentService.StreamDelta> chatStructuredStream(AgentEntity agent, String message,
                                                               String conversationId) {
        return Flux.create(sink -> {
            final java.util.concurrent.atomic.AtomicReference<ManagedSession> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicBoolean finished =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            sink.onCancel(() -> {
                if (!finished.get()) closeCancelled(agent, conversationId, ref.get());
            });
            ManagedSession session = null;
            try {
                session = acquire(agent, conversationId);
                ref.set(session);
                session.prompt(message, sink);
                finished.set(true);
                sink.complete();
            } catch (Throwable e) {
                finished.set(true);
                if (session != null) {
                    session.closeQuietly();
                    sessions.remove(new SessionKey(agent.getId(), conversationId), session);
                }
                sink.error(translate(agent, e));
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    public String chat(AgentEntity agent, String message, String conversationId) {
        StringBuilder sb = new StringBuilder();
        chatStructuredStream(agent, message, conversationId)
                .doOnNext(delta -> {
                    if (delta.content() != null) sb.append(delta.content());
                })
                .blockLast(Duration.ofMinutes(12));
        return sb.toString();
    }

    public AgentService.ChatResult chatWithUsage(AgentEntity agent, String message, String conversationId) {
        return AgentService.ChatResult.contentOnly(chat(agent, message, conversationId));
    }

    public void closeAgentSessions(Long agentId) {
        if (agentId == null) return;
        sessions.entrySet().removeIf(entry -> {
            if (!agentId.equals(entry.getKey().agentId())) return false;
            entry.getValue().closeQuietly();
            return true;
        });
    }

    @Scheduled(fixedDelay = 60_000L)
    public void reapIdleSessions() {
        Instant cutoff = Instant.now().minus(IDLE_TIMEOUT);
        sessions.entrySet().removeIf(entry -> {
            ManagedSession session = entry.getValue();
            if (session.lastUsed().isAfter(cutoff)) return false;
            session.closeQuietly();
            return true;
        });
    }

    @Override
    public void destroy() {
        for (ManagedSession session : sessions.values()) {
            session.closeQuietly();
        }
        sessions.clear();
    }

    private ManagedSession acquire(AgentEntity agent, String conversationId) throws Exception {
        SessionKey key = new SessionKey(agent.getId(), conversationId);
        ManagedSession session = sessions.get(key);
        if (session != null && !session.isExpired()) {
            return session;
        }
        boolean recovered = session != null || shouldRecoverFromHistory(conversationId);
        if (session != null) {
            session.closeQuietly();
            sessions.remove(key, session);
        }
        ManagedSession fresh = openSession(agent, conversationId, recovered);
        ManagedSession existing = sessions.putIfAbsent(key, fresh);
        if (existing != null && !existing.isExpired()) {
            fresh.closeQuietly();
            return existing;
        }
        if (existing != null) existing.closeQuietly();
        sessions.put(key, fresh);
        return fresh;
    }

    private boolean shouldRecoverFromHistory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return false;
        try {
            List<MessageEntity> recent = conversationService.listRecentMessages(conversationId, 8);
            return recent.stream().anyMatch(m -> "assistant".equalsIgnoreCase(m.getRole()));
        } catch (Exception e) {
            return false;
        }
    }

    private void closeCancelled(AgentEntity agent, String conversationId, ManagedSession session) {
        if (session == null) return;
        session.closeQuietly();
        sessions.remove(new SessionKey(agent.getId(), conversationId), session);
    }

    private ManagedSession openSession(AgentEntity agent, String conversationId, boolean recovered)
            throws Exception {
        String slug = normalizeEndpointName(agent.getAcpEndpointName());
        if (slug == null) {
            throw new MateClawException("err.agent.acp_endpoint_invalid",
                    "ACP Agent endpoint invalid: " + agent.getAcpEndpointName());
        }
        AcpEndpointEntity endpoint = endpointService.findByName(slug);
        if (endpoint == null || !Boolean.TRUE.equals(endpoint.getEnabled())) {
            throw new MateClawException("err.agent.acp_endpoint_disabled",
                    "ACP endpoint '" + slug + "' is disabled");
        }
        ensureEndpointVisibleToAgent(endpoint, agent);
        List<String> args = endpointService.parseArgs(endpoint);
        Map<String, String> env = endpointService.parseEnv(endpoint);
        String cwd = runtimeSupport.resolveCwd(endpoint, agent.getWorkspaceBasePath());
        AcpStdioClient client = AcpStdioClient.spawn(objectMapper, endpoint.getCommand(), args, env, cwd);
        client.setStdoutBufferLimitBytes(resolveBufferLimit(endpoint));
        ManagedSession session = new ManagedSession(client, endpoint, slug, cwd, recovered);
        try {
            JsonNode initResp = client.initialize(INITIALIZE_TIMEOUT_MS);
            if (initResp == null || initResp.path("protocolVersion").asInt(-1)
                    != AcpStdioClient.PROTOCOL_VERSION) {
                throw new MateClawException("err.acp.protocol_mismatch",
                        "ACP protocol mismatch with endpoint '" + slug + "'");
            }
            JsonNode created = client.newSession(cwd, SESSION_NEW_TIMEOUT_MS);
            String sessionId = created == null ? null : created.path("sessionId").asText("");
            if (sessionId == null || sessionId.isBlank()) {
                throw new MateClawException("err.acp.session_failed",
                        "ACP session/new returned no sessionId for '" + slug + "'");
            }
            session.sessionId = sessionId;
            String identityPrompt = buildIdentityPrompt(agent);
            if (identityPrompt != null) {
                session.prime(identityPrompt);
            }
            if (recovered) {
                session.prime(buildRecoveryPrompt(agent, conversationId));
            }
            return session;
        } catch (Exception e) {
            session.closeQuietly();
            throw e;
        }
    }

    private String buildIdentityPrompt(AgentEntity agent) {
        if (agent == null || agent.getSystemPrompt() == null || agent.getSystemPrompt().isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("System note from HHAIOS: this external ACP employee has a local identity card. "
                + "Treat the following as persistent instructions for your role, goals, background, and boundaries. "
                + "Do not answer this note directly; apply it to all subsequent user requests.\n");
        if (agent.getName() != null && !agent.getName().isBlank()) {
            sb.append("\nEmployee: ").append(agent.getName()).append("\n");
        }
        sb.append("\nIdentity card:\n");
        sb.append(agent.getSystemPrompt().trim());
        return sb.toString();
    }

    private String buildRecoveryPrompt(AgentEntity agent, String conversationId) {
        List<MessageEntity> recent = conversationService.listRecentMessages(
                conversationId, RECOVERY_RECENT_MESSAGES);
        StringBuilder sb = new StringBuilder();
        sb.append("System note from HHAIOS: your previous ACP process for this conversation expired. ");
        sb.append("Continue as the same external agent. Treat the following transcript as recovery context; ");
        sb.append("do not answer this note directly.\n\n");
        sb.append("Employee: ").append(agent.getName()).append("\n");
        if (agent.getWorkspaceBasePath() != null && !agent.getWorkspaceBasePath().isBlank()) {
            sb.append("Workspace: ").append(agent.getWorkspaceBasePath()).append("\n");
        }
        sb.append("\nRecent conversation:\n");
        int limit = recent.size();
        if (limit > 0) {
            MessageEntity last = recent.get(limit - 1);
            if (last != null && "user".equalsIgnoreCase(last.getRole())) {
                limit -= 1;
            }
        }
        for (int i = 0; i < limit; i++) {
            MessageEntity msg = recent.get(i);
            if (msg == null || msg.getContent() == null || msg.getContent().isBlank()) continue;
            String role = msg.getRole() == null ? "message" : msg.getRole();
            String text = msg.getContent();
            if (text.length() > 4000) text = text.substring(0, 4000) + "...";
            sb.append("\n[").append(role).append("]\n").append(text).append("\n");
        }
        return sb.toString();
    }

    private RuntimeException translate(AgentEntity agent, Throwable e) {
        if (e instanceof RuntimeException re) return re;
        String slug = agent == null ? "" : normalizeEndpointName(agent.getAcpEndpointName());
        AcpEndpointEntity endpoint = slug == null ? null : endpointService.findByName(slug);
        String authHint = runtimeSupport.translateAuthError(endpoint, e.getMessage());
        if (authHint != null) {
            return new MateClawException("err.acp.auth_failed", authHint);
        }
        return new MateClawException("err.acp.agent_failed",
                "ACP Agent failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
    }

    private void ensureEndpointVisibleToAgent(AcpEndpointEntity endpoint, AgentEntity agent) {
        if (endpoint == null || agent == null) return;
        if (Boolean.TRUE.equals(endpoint.getBuiltin())
                && MANAGED_ENDPOINTS.contains(endpoint.getName())) {
            return;
        }
        long agentWs = agent.getWorkspaceId() == null ? 1L : agent.getWorkspaceId();
        long endpointWs = endpoint.getWorkspaceId() == null ? 1L : endpoint.getWorkspaceId();
        if (agentWs != endpointWs) {
            throw new MateClawException("err.agent.acp_endpoint_wrong_workspace", 403,
                    "ACP endpoint '" + endpoint.getName()
                            + "' does not belong to agent workspace " + agentWs);
        }
    }

    private record SessionKey(Long agentId, String conversationId) {}

    private final class ManagedSession {
        private final AcpStdioClient client;
        private final AcpEndpointEntity endpoint;
        private final String endpointName;
        private final String cwd;
        private volatile boolean recoveryNoticePending;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile Instant lastUsed = Instant.now();
        private volatile String sessionId;

        private ManagedSession(AcpStdioClient client, AcpEndpointEntity endpoint,
                               String endpointName, String cwd, boolean recovered) {
            this.client = client;
            this.endpoint = endpoint;
            this.endpointName = endpointName;
            this.cwd = cwd;
            this.recoveryNoticePending = recovered;
            wireHandlers(client, endpointName, endpoint);
        }

        Instant lastUsed() {
            return lastUsed;
        }

        boolean isExpired() {
            return lastUsed.isBefore(Instant.now().minus(IDLE_TIMEOUT));
        }

        void prime(String prompt) throws IOException, InterruptedException {
            if (prompt == null || prompt.isBlank()) return;
            sendPrompt(prompt, null, true);
        }

        void prompt(String message, FluxSink<AgentService.StreamDelta> sink)
                throws IOException, InterruptedException {
            if (recoveryNoticePending) {
                recoveryNoticePending = false;
                sink.next(AgentService.StreamDelta.event("warning", Map.of(
                        "message", "ACP 会话已根据历史记录恢复。旧进程内上下文无法完全恢复。"
                )));
            }
            sendPrompt(message, sink, false);
        }

        private void sendPrompt(String message, FluxSink<AgentService.StreamDelta> sink, boolean silent)
                throws IOException, InterruptedException {
            lock.lock();
            try {
                lastUsed = Instant.now();
                client.setNotificationHandler(msg -> handleNotification(msg, sink, silent));
                ObjectNode promptParams = objectMapper.createObjectNode();
                promptParams.put("sessionId", sessionId);
                promptParams.set("prompt", buildPromptArray(message));
                client.sendRequest("session/prompt", promptParams, PROMPT_TIMEOUT_MS);
                lastUsed = Instant.now();
            } finally {
                client.setNotificationHandler(msg -> {});
                lock.unlock();
            }
        }

        private void handleNotification(JsonNode msg, FluxSink<AgentService.StreamDelta> sink, boolean silent) {
            if (silent || sink == null) return;
            String method = msg.path("method").asText("");
            if (!"session/update".equals(method)) return;
            JsonNode update = msg.path("params").path("update");
            AgentService.StreamDelta toolDelta = AcpStreamEventTranslator.toolDelta(
                    update, endpoint.getToolParseMode());
            if (toolDelta != null) {
                sink.next(toolDelta);
                return;
            }
            String text = AcpStreamEventTranslator.messageText(update);
            if (!text.isEmpty()) sink.next(new AgentService.StreamDelta(text, null));
        }

        void closeQuietly() {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("ACP session close failed for {}: {}", endpointName, e.getMessage());
            }
        }
    }

    private void wireHandlers(AcpStdioClient client, String endpointName, AcpEndpointEntity endpoint) {
        client.setRequestHandler(msg -> {
            String method = msg.path("method").asText("");
            if (!"session/request_permission".equals(method)) return null;
            boolean trusted = endpoint == null || !Boolean.FALSE.equals(endpoint.getTrusted());
            if (!trusted) {
                log.info("[ACP Agent] declining permission for untrusted endpoint '{}'", endpointName);
                return cancelledOutcome();
            }
            JsonNode options = msg.path("params").path("options");
            String optionId = "";
            if (options.isArray() && options.size() > 0) {
                JsonNode first = options.get(0);
                optionId = first.path("optionId").asText(first.path("id").asText(""));
            }
            if (optionId.isEmpty()) {
                return cancelledOutcome();
            }
            log.debug("[ACP Agent] auto-allowing permission for endpoint '{}'", endpointName);
            return selectedOutcome(optionId);
        });
    }

    private long resolveBufferLimit(AcpEndpointEntity endpoint) {
        Long configured = endpoint != null ? endpoint.getStdioBufferLimitBytes() : null;
        return configured != null && configured > 0 ? configured : 50L * 1024L * 1024L;
    }

    private JsonNode buildPromptArray(String text) {
        var arr = objectMapper.createArrayNode();
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "text");
        block.put("text", text != null ? text : "");
        arr.add(block);
        return arr;
    }

    private ObjectNode selectedOutcome(String optionId) {
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode outcome = objectMapper.createObjectNode();
        outcome.put("outcome", "selected");
        outcome.put("optionId", optionId);
        result.set("outcome", outcome);
        return result;
    }

    private ObjectNode cancelledOutcome() {
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode outcome = objectMapper.createObjectNode();
        outcome.put("outcome", "cancelled");
        result.set("outcome", outcome);
        return result;
    }
}
