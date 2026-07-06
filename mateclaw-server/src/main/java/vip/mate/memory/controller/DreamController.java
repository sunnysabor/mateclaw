package vip.mate.memory.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.model.DreamReportEntity;
import vip.mate.memory.model.MemoryRecallEntity;
import vip.mate.memory.repository.DreamReportMapper;
import vip.mate.memory.repository.MemoryRecallMapper;
import vip.mate.memory.service.MorningCardService;
import vip.mate.memory.service.MemoryHilService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dream report API — provides paginated access to dream history for the Memory Timeline UI.
 *
 * @author MateClaw Team
 */
@Tag(name = "Dream Reports")
@RestController
@RequestMapping("/api/v1/memory/{agentId}/dream")
@RequiredArgsConstructor
public class DreamController {

    private final DreamReportMapper dreamReportMapper;
    private final MemoryRecallMapper recallMapper;
    private final MorningCardService morningCardService;
    private final MemoryHilService hilService;
    private final DreamEventBroadcaster eventBroadcaster;
    private final AuthService authService;
    private final MemoryProperties memoryProperties;

    @Operation(summary = "List dream reports (paginated, newest first)")
    @GetMapping("/reports")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> listReports(
            @PathVariable Long agentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        String ownerKey = currentWebOwnerKey(auth);
        Page<DreamReportEntity> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<DreamReportEntity> query = new LambdaQueryWrapper<DreamReportEntity>()
                .eq(DreamReportEntity::getAgentId, agentId)
                .eq(DreamReportEntity::getDeleted, 0);
        applyDreamReportVisibility(query, ownerKey);
        Page<DreamReportEntity> result = dreamReportMapper.selectPage(pageParam,
                query.orderByDesc(DreamReportEntity::getStartedAt));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("size", result.getSize());
        return R.ok(data);
    }

    @Operation(summary = "Get a single dream report by ID")
    @GetMapping("/reports/{reportId}")
    @RequireWorkspaceRole("member")
    public R<DreamReportEntity> getReport(
            @PathVariable Long agentId,
            @PathVariable Long reportId,
            Authentication auth) {
        LambdaQueryWrapper<DreamReportEntity> query = new LambdaQueryWrapper<DreamReportEntity>()
                .eq(DreamReportEntity::getId, reportId)
                .eq(DreamReportEntity::getAgentId, agentId)
                .eq(DreamReportEntity::getDeleted, 0);
        applyDreamReportVisibility(query, currentWebOwnerKey(auth));
        DreamReportEntity entity = dreamReportMapper.selectOne(query);
        if (entity == null) {
            return R.fail("Report not found");
        }
        return R.ok(entity);
    }

    // ==================== SSE Events ====================

    @Operation(summary = "Subscribe to dream events (SSE)")
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequireWorkspaceRole("member")
    public SseEmitter subscribeDreamEvents(@PathVariable Long agentId, Authentication auth) {
        return eventBroadcaster.register(agentId, currentWebOwnerKey(auth));
    }

    // ==================== Morning Card ====================

    @Operation(summary = "Get morning card for current user + agent")
    @GetMapping("/morning-card")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> getMorningCard(@PathVariable Long agentId, Authentication auth) {
        Long userId = resolveUserId(auth);
        Map<String, Object> card = morningCardService.getCardFor(userId, agentId, currentWebOwnerKey(auth));
        return R.ok(card); // null = no card to show
    }

    @Operation(summary = "Mark morning card as seen")
    @PostMapping("/morning-card/seen")
    @RequireWorkspaceRole("member")
    public R<Void> markMorningCardSeen(@PathVariable Long agentId,
                                        @RequestBody Map<String, Object> body,
                                        Authentication auth) {
        Long userId = resolveUserId(auth);
        Long reportId = body.get("reportId") != null
                ? Long.valueOf(body.get("reportId").toString()) : null;
        morningCardService.markSeen(userId, agentId, reportId);
        return R.ok(null);
    }

    // ==================== HiL (Human-in-the-Loop) ====================

    @Operation(summary = "Confirm a memory entry (no-op acknowledgment)")
    @PostMapping("/reports/{reportId}/entries/{key}/confirm")
    @RequireWorkspaceRole("member")
    public R<Void> confirmEntry(@PathVariable Long agentId,
                                 @PathVariable Long reportId,
                                 @PathVariable String key) {
        // Confirm is a no-op in Phase 2 — just logs the action
        return R.ok(null);
    }

    public R<Void> editEntry(@PathVariable Long agentId,
                              @PathVariable Long reportId,
                              @PathVariable String key,
                              @RequestBody Map<String, String> body) {
        return editEntry(agentId, reportId, key, body, null);
    }

    @Operation(summary = "Edit a memory entry — writes back to the target memory file with user-edited metadata")
    @PostMapping("/reports/{reportId}/entries/{key}/edit")
    @RequireWorkspaceRole("member")
    public R<Void> editEntry(@PathVariable Long agentId,
                              @PathVariable Long reportId,
                              @PathVariable String key,
                              @RequestBody Map<String, String> body,
                              Authentication auth) {
        String decodedKey = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8);

        String newContent = body.get("content");
        if (newContent == null || newContent.isBlank()) {
            return R.fail("content is required");
        }

        String filename;
        if (reportId != 0L) {
            // Report-scoped edit: dream report entries are always MEMORY.md sections.
            filename = "MEMORY.md";
            // Validate report belongs to agent and is visible to the current owner.
            LambdaQueryWrapper<DreamReportEntity> reportQuery = new LambdaQueryWrapper<DreamReportEntity>()
                    .eq(DreamReportEntity::getId, reportId)
                    .eq(DreamReportEntity::getAgentId, agentId)
                    .eq(DreamReportEntity::getDeleted, 0);
            applyDreamReportVisibility(reportQuery, currentWebOwnerKey(auth));
            DreamReportEntity report = dreamReportMapper.selectOne(reportQuery);
            if (report == null) {
                return R.fail("Report not found or does not belong to this agent");
            }
            // Key must match a recall entry that was a candidate during this dream run
            // (lastRecalledAt between report.startedAt and report.finishedAt) and in
            // the same owner/scope bucket as the report; otherwise one user could
            // edit another user's private MEMORY.md through a guessed report id.
            LambdaQueryWrapper<MemoryRecallEntity> candidateQuery = new LambdaQueryWrapper<MemoryRecallEntity>()
                    .eq(MemoryRecallEntity::getAgentId, agentId)
                    .ge(MemoryRecallEntity::getLastRecalledAt, report.getStartedAt())
                    .le(MemoryRecallEntity::getLastRecalledAt, report.getFinishedAt())
                    .eq(MemoryRecallEntity::getDeleted, 0);
            applyRecallVisibility(candidateQuery, reportOwnerKey(report), reportScope(report));
            List<MemoryRecallEntity> candidates = recallMapper.selectList(candidateQuery);
            // Exact match on the section key (part after # in filename)
            boolean keyBelongsToReport = candidates.stream()
                    .anyMatch(c -> {
                        if (c.getFilename() == null) return false;
                        int hash = c.getFilename().indexOf('#');
                        String entryKey = hash >= 0 ? c.getFilename().substring(hash + 1) : c.getFilename();
                        return entryKey.equals(decodedKey);
                    });
            if (!keyBelongsToReport) {
                return R.fail("Entry '" + decodedKey + "' does not belong to report " + reportId);
            }
            String reportOwner = reportOwnerKey(report);
            if (reportOwner != null) {
                hilService.editMemoryEntry(agentId, filename, decodedKey, newContent, reportOwner);
            } else {
                hilService.editMemoryEntry(agentId, filename, decodedKey, newContent);
            }
            return R.ok(null);
        } else {
            // Direct edit (reportId=0, from MemoryBrowser): the target file comes
            // from the request body and must be an editable memory file.
            filename = body.getOrDefault("filename", "MEMORY.md");
            if (!isMemoryFile(filename)) {
                return R.fail("Unsupported memory file: " + filename);
            }
            if (auth == null) {
                if (!hilService.sectionExists(agentId, filename, decodedKey)) {
                    return R.fail("Section '" + decodedKey + "' not found in " + filename);
                }
                hilService.editMemoryEntry(agentId, filename, decodedKey, newContent);
                return R.ok(null);
            }
            String ownerKey = currentWebOwnerKey(auth);
            if (!hilService.sectionExists(agentId, filename, decodedKey, ownerKey)) {
                return R.fail("Section '" + decodedKey + "' not found in " + filename);
            }
            hilService.editMemoryEntry(agentId, filename, decodedKey, newContent, ownerKey);
            return R.ok(null);
        }

    }

    /**
     * Whitelist of workspace files the memory browser may edit. Keeps this
     * memory-scoped endpoint from becoming a general-purpose file-write vector.
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

    private String reportScope(DreamReportEntity report) {
        if (report != null && vip.mate.memory.identity.MemoryScope.PERSONAL.equals(report.getScope())) {
            return vip.mate.memory.identity.MemoryScope.PERSONAL;
        }
        if (report != null && vip.mate.memory.identity.MemoryScope.GLOBAL.equals(report.getScope())) {
            return vip.mate.memory.identity.MemoryScope.GLOBAL;
        }
        return vip.mate.memory.identity.MemoryScope.TEAM;
    }

    private String reportOwnerKey(DreamReportEntity report) {
        return vip.mate.memory.identity.MemoryScope.PERSONAL.equals(reportScope(report))
                ? report.getOwnerKey()
                : null;
    }

    private void applyRecallVisibility(LambdaQueryWrapper<MemoryRecallEntity> query,
                                       String ownerKey, String scope) {
        String effectiveScope = vip.mate.memory.identity.MemoryScope.PERSONAL.equals(scope)
                ? vip.mate.memory.identity.MemoryScope.PERSONAL
                : (vip.mate.memory.identity.MemoryScope.GLOBAL.equals(scope)
                    ? vip.mate.memory.identity.MemoryScope.GLOBAL
                    : vip.mate.memory.identity.MemoryScope.TEAM);
        query.eq(MemoryRecallEntity::getScope, effectiveScope);
        if (vip.mate.memory.identity.MemoryScope.PERSONAL.equals(effectiveScope)) {
            query.eq(MemoryRecallEntity::getOwnerKey, ownerKey);
        } else {
            query.and(w -> w.isNull(MemoryRecallEntity::getOwnerKey)
                    .or().eq(MemoryRecallEntity::getOwnerKey, ""));
        }
    }

    private void applyDreamReportVisibility(LambdaQueryWrapper<DreamReportEntity> query, String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()
                || vip.mate.memory.identity.MemoryOwnerResolver.SYSTEM_OWNER.equals(ownerKey)) {
            query.in(DreamReportEntity::getScope,
                    vip.mate.memory.identity.MemoryScope.TEAM,
                    vip.mate.memory.identity.MemoryScope.GLOBAL);
            return;
        }
        query.and(s -> s.in(DreamReportEntity::getScope,
                        vip.mate.memory.identity.MemoryScope.TEAM,
                        vip.mate.memory.identity.MemoryScope.GLOBAL)
                .or(p -> p.eq(DreamReportEntity::getScope,
                                vip.mate.memory.identity.MemoryScope.PERSONAL)
                        .eq(DreamReportEntity::getOwnerKey, ownerKey)));
    }

    /**
     * Resolve the authenticated user's id from the JWT principal. The auth
     * filter exposes the username via {@link Authentication#getName()}, so the
     * id is looked up by username — matching AgentController / WorkspaceController.
     */
    private Long resolveUserId(Authentication auth) {
        if (auth == null) {
            throw new MateClawException("err.auth.unauthenticated", 401, "Not authenticated");
        }
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found", 401, "User not found: " + auth.getName());
        }
        return user.getId();
    }
}
