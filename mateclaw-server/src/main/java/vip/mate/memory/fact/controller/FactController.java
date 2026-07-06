package vip.mate.memory.fact.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.memory.identity.MemoryScope;
import vip.mate.memory.fact.model.FactContradictionEntity;
import vip.mate.memory.fact.model.FactEntity;
import vip.mate.memory.fact.query.FactQueryService;
import vip.mate.memory.fact.repository.FactContradictionMapper;
import vip.mate.memory.fact.repository.FactMapper;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Fact management API — forget, feedback, contradiction resolution.
 *
 * @author MateClaw Team
 */
@Tag(name = "Facts")
@Slf4j
@RestController
@RequestMapping("/api/v1/memory/{agentId}/facts")
@RequiredArgsConstructor
public class FactController {

    private final FactMapper factMapper;
    private final FactContradictionMapper contradictionMapper;
    private final FactQueryService queryService;
    private final WorkspaceFileService workspaceFileService;
    private final MemoryProperties properties;

    @Operation(summary = "List facts for an agent")
    @GetMapping
    @RequireWorkspaceRole("member")
    public R<List<FactEntity>> listFacts(@PathVariable Long agentId,
                                          @RequestParam(required = false) String keyword,
                                          Authentication auth) {
        LambdaQueryWrapper<FactEntity> query = new LambdaQueryWrapper<FactEntity>()
                .eq(FactEntity::getAgentId, agentId)
                .eq(FactEntity::getDeleted, 0);
        applyFactVisibility(query, currentWebOwnerKey(auth));
        if (keyword != null && !keyword.isBlank()) {
            query.and(w -> w.like(FactEntity::getSubject, keyword)
                    .or().like(FactEntity::getObjectValue, keyword));
        }
        query.orderByDesc(FactEntity::getTrust).last("LIMIT 50");
        return R.ok(factMapper.selectList(query));
    }

    private void applyFactVisibility(LambdaQueryWrapper<FactEntity> query, String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()
                || MemoryOwnerResolver.SYSTEM_OWNER.equals(ownerKey)) {
            query.in(FactEntity::getScope, MemoryScope.TEAM, MemoryScope.GLOBAL);
            return;
        }
        query.and(s -> s.in(FactEntity::getScope, MemoryScope.TEAM, MemoryScope.GLOBAL)
                .or(p -> p.eq(FactEntity::getScope, MemoryScope.PERSONAL)
                        .eq(FactEntity::getOwnerKey, ownerKey)));
    }

    private String currentWebOwnerKey(Authentication auth) {
        if (auth == null) {
            return MemoryOwnerResolver.SYSTEM_OWNER;
        }
        String username = auth.getName();
        if (username == null || username.isBlank()) {
            return MemoryOwnerResolver.SYSTEM_OWNER;
        }
        return "user:" + username;
    }

    private String canonicalScopeFor(FactEntity fact) {
        return fact != null && MemoryScope.PERSONAL.equals(fact.getScope())
                ? MemoryScope.PERSONAL
                : (fact != null && MemoryScope.GLOBAL.equals(fact.getScope()) ? MemoryScope.GLOBAL : MemoryScope.TEAM);
    }

    private String canonicalOwnerFor(FactEntity fact) {
        return fact != null && MemoryScope.PERSONAL.equals(fact.getScope()) ? fact.getOwnerKey() : null;
    }

    @Operation(summary = "Forget a fact — writes canonical metadata; canonical memory-write event refreshes projection")
    @PostMapping("/{factId}/forget")
    @RequireWorkspaceRole("member")
    public R<Void> forgetFact(@PathVariable Long agentId,
                               @PathVariable Long factId,
                               Authentication auth) {
        if (!properties.getFact().isForgetEnabled()) {
            return R.fail(410, "Forget is disabled");
        }

        FactEntity fact = factMapper.selectById(factId);
        if (fact == null || !fact.getAgentId().equals(agentId) || fact.getDeleted() == 1) {
            return R.fail("Fact not found");
        }

        // Write forget metadata to canonical source
        String sourceRef = fact.getSourceRef();
        String[] parts = sourceRef.split("#", 2);
        String filename = parts[0];
        String userId = auth != null ? auth.getName() : "unknown";

        String ownerKey = canonicalOwnerFor(fact);
        String scope = canonicalScopeFor(fact);
        WorkspaceFileEntity file = MemoryScope.PERSONAL.equals(scope)
                ? workspaceFileService.getMemoryFile(agentId, filename, ownerKey)
                : workspaceFileService.getFile(agentId, filename);
        if (file != null && file.getContent() != null) {
            String marker = "> Forgotten: " + LocalDate.now() + " by " + userId;
            String sectionKey = parts.length > 1 ? parts[1] : null;

            String content = file.getContent();
            if (sectionKey != null) {
                // Append marker after the matching section
                String sectionHeader = "## " + sectionKey;
                int idx = content.indexOf(sectionHeader);
                if (idx >= 0) {
                    int nextSection = content.indexOf("\n## ", idx + sectionHeader.length());
                    int insertAt = nextSection > 0 ? nextSection : content.length();
                    content = content.substring(0, insertAt) + "\n" + marker + "\n" + content.substring(insertAt);
                } else {
                    content = content + "\n" + marker + "\n";
                }
            } else {
                content = content + "\n" + marker + "\n";
            }
            if (MemoryScope.PERSONAL.equals(scope)) {
                workspaceFileService.saveMemoryFile(agentId, filename, content, ownerKey);
            } else {
                workspaceFileService.saveFile(agentId, filename, content);
            }
            // WorkspaceFileService is the single source of MemoryWriteEvent publication;
            // Fact projection is refreshed by MemoryWriteEventDispatcher from the canonical content.
        }

        // Do NOT directly write mate_fact — let projection rebuild handle visibility.
        // The rebuild will either skip the Forgotten section (removing the fact)
        // or soft-delete it via deleteByAgentIdAndSourceRefNotIn.

        log.info("[Fact] Forgotten fact {} for agent={} by {}", factId, agentId, userId);
        return R.ok(null);
    }

    @Operation(summary = "Submit feedback on a fact (HELPFUL/UNHELPFUL)")
    @PostMapping("/{factId}/feedback")
    @RequireWorkspaceRole("member")
    public R<Void> feedbackFact(@PathVariable Long agentId,
                                 @PathVariable Long factId,
                                 @RequestBody Map<String, String> body) {
        String kind = body.get("kind"); // HELPFUL or UNHELPFUL
        if (kind == null || (!kind.equals("HELPFUL") && !kind.equals("UNHELPFUL"))) {
            return R.fail("kind must be HELPFUL or UNHELPFUL");
        }

        FactEntity fact = factMapper.selectById(factId);
        if (fact == null || !fact.getAgentId().equals(agentId) || fact.getDeleted() == 1) {
            return R.fail("Fact not found");
        }

        // Write feedback metadata to the specific canonical section (not file tail)
        String sourceRef = fact.getSourceRef();
        String[] parts = sourceRef.split("#", 2);
        String filename = parts[0];
        String sectionKey = parts.length > 1 ? parts[1] : null;
        String ownerKey = canonicalOwnerFor(fact);
        String scope = canonicalScopeFor(fact);
        WorkspaceFileEntity file = MemoryScope.PERSONAL.equals(scope)
                ? workspaceFileService.getMemoryFile(agentId, filename, ownerKey)
                : workspaceFileService.getFile(agentId, filename);
        if (file != null && file.getContent() != null) {
            String marker = "> UserFeedback: " + kind + " " + LocalDate.now();
            String content = file.getContent();
            if (sectionKey != null) {
                String sectionHeader = "## " + sectionKey;
                int idx = content.indexOf(sectionHeader);
                if (idx >= 0) {
                    int nextSection = content.indexOf("\n## ", idx + sectionHeader.length());
                    int insertAt = nextSection > 0 ? nextSection : content.length();
                    content = content.substring(0, insertAt) + "\n" + marker + "\n" + content.substring(insertAt);
                } else {
                    content = content + "\n" + marker + "\n";
                }
            } else {
                content = content + "\n" + marker + "\n";
            }
            if (MemoryScope.PERSONAL.equals(scope)) {
                workspaceFileService.saveMemoryFile(agentId, filename, content, ownerKey);
            } else {
                workspaceFileService.saveFile(agentId, filename, content);
            }
            // WorkspaceFileService is the single source of MemoryWriteEvent publication;
            // Fact projection is refreshed by MemoryWriteEventDispatcher from the canonical content.
        }

        // Do NOT directly write mate_fact.trust — let projection rebuild derive it from canonical metadata.
        log.info("[Fact] Feedback {} on fact {} for agent={}", kind, factId, agentId);
        return R.ok(null);
    }

    // ==================== Contradictions ====================

    @Operation(summary = "List unresolved contradictions")
    @GetMapping("/contradictions")
    @RequireWorkspaceRole("member")
    public R<List<FactContradictionEntity>> listContradictions(@PathVariable Long agentId,
                                                               Authentication auth) {
        return R.ok(queryService.listContradictions(agentId, currentWebOwnerKey(auth)));
    }

    @Operation(summary = "Resolve a contradiction (KEEP_A / KEEP_B / MERGE / IGNORE)")
    @PostMapping("/contradictions/{contradictionId}/resolve")
    @RequireWorkspaceRole("member")
    public R<Void> resolveContradiction(@PathVariable Long agentId,
                                         @PathVariable Long contradictionId,
                                         @RequestBody Map<String, String> body,
                                         Authentication auth) {
        String resolution = body.get("resolution");
        if (resolution == null || !List.of("KEEP_A", "KEEP_B", "MERGE", "IGNORE").contains(resolution)) {
            return R.fail("resolution must be KEEP_A, KEEP_B, MERGE, or IGNORE");
        }

        FactContradictionEntity c = contradictionMapper.selectById(contradictionId);
        if (c == null || !c.getAgentId().equals(agentId)) {
            return R.fail("Contradiction not found");
        }

        c.setResolution(resolution);
        c.setResolvedAt(LocalDateTime.now());
        c.setResolvedBy(auth != null ? auth.getName() : "unknown");
        c.setUpdateTime(LocalDateTime.now());
        contradictionMapper.updateById(c);

        log.info("[Fact] Contradiction {} resolved as {} for agent={}", contradictionId, resolution, agentId);
        return R.ok(null);
    }
}
