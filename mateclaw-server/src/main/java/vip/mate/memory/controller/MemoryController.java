package vip.mate.memory.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.service.*;
import vip.mate.memory.scheduler.DreamingScheduler;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.HandlerMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆管理接口
 * <p>
 * 提供记忆整合的手动触发和状态查询。
 *
 * @author MateClaw Team
 */
@Tag(name = "记忆管理")
@Slf4j
@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryEmergenceService emergenceService;
    private final MemorySummarizationService summarizationService;
    private final MemoryRecallService recallService;
    private final MemoryProperties memoryProperties;
    private final DreamingScheduler dreamingScheduler;
    private final WorkspaceFileService workspaceFileService;
    private final StructuredMemoryConsolidationService structuredConsolidationService;

    @Operation(summary = "列出当前用户可见的记忆文件（共享 + 当前 owner 的个人记忆，不含内容）")
    @GetMapping("/{agentId}/files")
    @RequireWorkspaceRole("viewer")
    public R<List<WorkspaceFileEntity>> listVisibleMemoryFiles(@PathVariable Long agentId,
                                                               Authentication auth) {
        return R.ok(dedupeByFilenamePreferPersonal(workspaceFileService
                .listVisibleFiles(agentId, currentWebOwnerKey(auth)).stream()
                .filter(this::isMemoryBrowserFile)
                .toList()));
    }

    @Operation(summary = "读取当前用户可见的记忆文件内容")
    @GetMapping("/{agentId}/files/**")
    @RequireWorkspaceRole("viewer")
    public R<WorkspaceFileEntity> getVisibleMemoryFile(@PathVariable Long agentId,
                                                       HttpServletRequest request,
                                                       Authentication auth) {
        String filename = extractMemoryFilename(request);
        if (!isMemoryFile(filename)) {
            return R.fail("Unsupported memory file: " + filename);
        }
        WorkspaceFileEntity file = workspaceFileService.getVisibleFile(agentId, filename, currentWebOwnerKey(auth));
        if (file == null) {
            return R.fail("文件不存在: " + filename);
        }
        return R.ok(file);
    }

    @Operation(summary = "保存当前用户可见的记忆文件内容")
    @PutMapping("/{agentId}/files/**")
    @RequireWorkspaceRole("member")
    public R<WorkspaceFileEntity> saveVisibleMemoryFile(@PathVariable Long agentId,
                                                        HttpServletRequest request,
                                                        @RequestBody Map<String, String> body,
                                                        Authentication auth) {
        String filename = extractMemoryFilename(request);
        if (!isMemoryFile(filename)) {
            return R.fail("Unsupported memory file: " + filename);
        }
        String content = body != null ? body.getOrDefault("content", "") : "";
        return R.ok(workspaceFileService.saveVisibleFile(agentId, filename, content, currentWebOwnerKey(auth)));
    }

    @Operation(summary = "手动触发 always-on 结构化记忆整合（user/feedback，合并去重过时条目）")
    @PostMapping("/{agentId}/structured-consolidation")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> triggerStructuredConsolidation(@PathVariable Long agentId) {
        try {
            StructuredMemoryConsolidationService.ConsolidationStats s =
                    structuredConsolidationService.consolidateAgent(agentId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ownersConsolidated", s.ownersConsolidated);
            out.put("updated", s.updated);
            out.put("skippedSmall", s.skippedSmall);
            out.put("skippedOverCap", s.skippedOverCap);
            out.put("failed", s.failed);
            out.put("entriesBefore", s.entriesBefore);
            out.put("entriesAfter", s.entriesAfter);
            return R.ok(out);
        } catch (Exception e) {
            log.error("[Memory] Manual structured consolidation failed for agent={}: {}",
                    agentId, e.getMessage(), e);
            return R.fail("结构化记忆整合失败: " + e.getMessage());
        }
    }

    @Operation(summary = "手动触发记忆整合（daily notes → MEMORY.md，NIGHTLY 模式）")
    @PostMapping("/{agentId}/emergence")
    @RequireWorkspaceRole("member")
    public R<DreamReport> triggerEmergence(@PathVariable Long agentId,
                                           Authentication auth) {
        try {
            DreamReport report = emergenceService.consolidate(agentId, DreamMode.NIGHTLY, null,
                    currentWebOwnerKey(auth));
            return R.ok(report);
        } catch (Exception e) {
            log.error("[Memory] Manual emergence failed for agent={}: {}", agentId, e.getMessage(), e);
            return R.fail("记忆整合失败: " + e.getMessage());
        }
    }

    @Operation(summary = "Focused Dream — 围绕指定主题触发记忆整合")
    @PostMapping("/{agentId}/dreaming/focused")
    @RequireWorkspaceRole("member")
    public R<DreamReport> triggerFocusedDream(@PathVariable Long agentId,
                                              @RequestBody Map<String, String> body,
                                              Authentication auth) {
        if (!memoryProperties.getDream().isFocusedEnabled()) {
            return R.fail(410, "Focused dream is disabled");
        }
        String topic = body != null ? body.get("topic") : null;
        if (topic == null || topic.isBlank()) {
            return R.fail("topic is required");
        }
        try {
            DreamReport report = emergenceService.consolidate(agentId, DreamMode.FOCUSED, topic,
                    currentWebOwnerKey(auth));
            return R.ok(report);
        } catch (Exception e) {
            log.error("[Memory] Focused dream failed for agent={}: {}", agentId, e.getMessage(), e);
            return R.fail("Focused dream failed: " + e.getMessage());
        }
    }

    @Operation(summary = "手动触发对话记忆提取")
    @PostMapping("/{agentId}/summarize/{conversationId}")
    @RequireWorkspaceRole("member")
    public R<Map<String, String>> triggerSummarize(
            @PathVariable Long agentId,
            @PathVariable String conversationId) {
        try {
            summarizationService.analyzeAndUpdateMemory(agentId, conversationId);
            return R.ok(Map.of("status", "completed"));
        } catch (Exception e) {
            log.error("[Memory] Manual summarization failed for agent={}, conv={}: {}",
                    agentId, conversationId, e.getMessage(), e);
            return R.fail("记忆提取失败: " + e.getMessage());
        }
    }

    // ==================== Dreaming 状态 API ====================

    @Operation(summary = "查询 Dreaming 状态（配置、统计、上次运行时间）")
    @GetMapping("/{agentId}/dreaming/status")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> getDreamingStatus(@PathVariable Long agentId,
                                                    Authentication auth) {
        Map<String, Object> status = recallService.getDreamingStatus(agentId, currentWebOwnerKey(auth));
        status.put("lastRunTime", dreamingScheduler.getLastRunTime());
        return R.ok(status);
    }

    @Operation(summary = "查询召回候选列表（含评分详情）")
    @GetMapping("/{agentId}/dreaming/candidates")
    @RequireWorkspaceRole("member")
    public R<List<Map<String, Object>>> getDreamingCandidates(@PathVariable Long agentId,
                                                              Authentication auth) {
        return R.ok(recallService.listCandidatesWithDetails(agentId, currentWebOwnerKey(auth)));
    }

    @Operation(summary = "查询 DREAMS.md 整合日记")
    @GetMapping("/{agentId}/dreaming/dreams")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> getDreams(@PathVariable Long agentId,
                                            Authentication auth) {
        WorkspaceFileEntity file = workspaceFileService.getVisibleFile(agentId, "DREAMS.md",
                currentWebOwnerKey(auth));
        Map<String, Object> result = new LinkedHashMap<>();
        if (file != null && file.getContent() != null) {
            result.put("content", file.getContent());
            result.put("updateTime", file.getUpdateTime());
        } else {
            result.put("content", null);
            result.put("message", "尚未生成 DREAMS.md（需先运行一次 emergence）");
        }
        return R.ok(result);
    }

    private boolean isMemoryBrowserFile(WorkspaceFileEntity file) {
        return file != null && isMemoryFile(file.getFilename());
    }

    /**
     * listVisibleFiles returns shared rows plus matching PERSONAL rows. For the
     * Memory UI those rows represent a single logical filename, with PERSONAL
     * taking precedence exactly like getVisibleFile().
     */
    private List<WorkspaceFileEntity> dedupeByFilenamePreferPersonal(List<WorkspaceFileEntity> files) {
        Map<String, WorkspaceFileEntity> byName = new LinkedHashMap<>();
        for (WorkspaceFileEntity file : files) {
            if (file == null || file.getFilename() == null) {
                continue;
            }
            WorkspaceFileEntity existing = byName.get(file.getFilename());
            if (existing == null || isPersonal(file)) {
                byName.put(file.getFilename(), file);
            }
        }
        return List.copyOf(byName.values());
    }

    private boolean isPersonal(WorkspaceFileEntity file) {
        return file != null && "PERSONAL".equalsIgnoreCase(file.getScope());
    }

    /**
     * Whitelist files exposed by the Memory page. This endpoint is owner-aware,
     * but still intentionally narrow: it is not a general workspace file API.
     */
    private boolean isMemoryFile(String filename) {
        if (filename == null || filename.isBlank() || filename.contains("..")) {
            return false;
        }
        return "MEMORY.md".equals(filename)
                || "PROFILE.md".equals(filename)
                || "SOUL.md".equals(filename)
                || (filename.startsWith("structured/") && filename.endsWith(".md"));
    }

    private String extractMemoryFilename(HttpServletRequest request) {
        String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        int filesIdx = fullPath.indexOf("/files/");
        if (filesIdx < 0) {
            return "";
        }
        return fullPath.substring(filesIdx + "/files/".length());
    }

    /**
     * Match web-console chat ownership: ChatOrigin.web(..., username, ...)
     * resolves to {@code user:<username>}. Unknown/system auth falls back to
     * shared-only visibility.
     */
    private String currentWebOwnerKey(Authentication auth) {
        if (!memoryProperties.isLifecycleMediatorEnabled() || auth == null) {
            return vip.mate.memory.identity.MemoryOwnerResolver.SYSTEM_OWNER;
        }
        String username = auth.getName();
        if (username == null || username.isBlank()) {
            return vip.mate.memory.identity.MemoryOwnerResolver.SYSTEM_OWNER;
        }
        return "user:" + username;
    }

}
