package vip.mate.memory.fact.projection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.fact.extraction.CompositeEntityExtractor;
import vip.mate.memory.fact.extraction.ExtractedFact;
import vip.mate.memory.fact.model.FactEntity;
import vip.mate.memory.fact.repository.FactMapper;
import vip.mate.memory.identity.MemoryScope;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds the fact projection from canonical sources.
 * <p>
 * Derived columns are overwritten; accumulated columns (use_count, last_used_at)
 * are preserved via select-then-update keyed on (agent_id, source_ref).
 * <p>
 * Only this class may write derived columns to mate_fact (core invariant).
 * Uses MyBatis Plus CRUD (dialect-safe for both H2 and MySQL).
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactProjectionBuilder {

    private final FactMapper factMapper;
    private final WorkspaceFileService workspaceFileService;
    private final CompositeEntityExtractor extractor;
    private final MemoryProperties properties;

    /**
     * Full rebuild for an agent. Extracts facts from all canonical sources,
     * upserts derived columns, and soft-deletes stale entries.
     */
    public int rebuildAll(Long agentId) {
        if (!properties.getFact().isProjectionEnabled()) {
            log.debug("[FactProjection] Projection disabled, skipping rebuildAll for agent={}", agentId);
            return 0;
        }

        List<ExtractedFact> allFacts = new ArrayList<>();

        // Extract from structured/*.md files (shared + personal rows).
        List<WorkspaceFileEntity> files = workspaceFileService.listAllFilesForMaintenance(agentId);
        for (WorkspaceFileEntity file : files) {
            String filename = file.getFilename();
            if (filename == null) continue;
            if (filename.startsWith("structured/") && filename.endsWith(".md")) {
                addFactsFromFile(agentId, file, allFacts);
            }
        }

        // Extract from MEMORY.md (shared + personal rows).
        for (WorkspaceFileEntity file : files) {
            if ("MEMORY.md".equals(file.getFilename())) {
                addFactsFromFile(agentId, file, allFacts);
            }
        }

        // Upsert all extracted facts (dialect-safe)
        LocalDateTime now = LocalDateTime.now();
        for (ExtractedFact fact : allFacts) {
            upsertDerived(agentId, fact, now);
        }

        // Remove stale facts by the full logical projection key. source_ref is
        // not unique once per-owner memory is enabled: two owners can both have
        // "structured/user.md#preferred_language".
        softDeleteStaleFacts(agentId, allFacts, now);

        log.info("[FactProjection] rebuildAll: agent={}, facts={}", agentId, allFacts.size());
        return allFacts.size();
    }

    private void addFactsFromFile(Long agentId, WorkspaceFileEntity file, List<ExtractedFact> sink) {
        String filename = file.getFilename();
        WorkspaceFileEntity full = isPersonal(file)
                ? workspaceFileService.getMemoryFile(agentId, filename, file.getOwnerKey())
                : workspaceFileService.getFile(agentId, filename);
        if (full == null || full.getContent() == null || full.getContent().isBlank()) {
            return;
        }
        sink.addAll(withVisibility(
                extractor.extract(agentId, filename, full.getContent()),
                visibilityScope(full),
                visibilityOwner(full)));
    }

    /**
     * Incremental rebuild for a single file change.
     */
    public int rebuildOne(Long agentId, String filename, String content) {
        if (!properties.getFact().isProjectionEnabled()) return 0;

        List<ExtractedFact> facts = extractor.extract(agentId, filename, content);
        LocalDateTime now = LocalDateTime.now();
        for (ExtractedFact fact : facts) {
            upsertDerived(agentId, fact, now);
        }
        factMapper.softDeleteByAgentIdAndSourceRefPrefixNotIn(
                agentId, filename + "#", facts.stream().map(ExtractedFact::sourceRef).toList(), now);
        log.debug("[FactProjection] rebuildOne: agent={}, file={}, facts={}", agentId, filename, facts.size());
        return facts.size();
    }

    /**
     * Incremental rebuild for a changed owner-scoped canonical file. The owner
     * metadata is written into each derived fact so fact recall and the UI can
     * apply the same PERSONAL / TEAM visibility rules as memory files.
     */
    public int rebuildOne(Long agentId, String filename, String content, String ownerKey, String scope) {
        if (!properties.getFact().isProjectionEnabled()) return 0;

        String effectiveScope = normalizeScope(scope);
        String effectiveOwner = MemoryScope.PERSONAL.equals(effectiveScope) ? ownerKey : null;
        List<ExtractedFact> facts = withVisibility(
                extractor.extract(agentId, filename, content), effectiveScope, effectiveOwner);
        LocalDateTime now = LocalDateTime.now();
        for (ExtractedFact fact : facts) {
            upsertDerived(agentId, fact, now);
        }
        factMapper.softDeleteByAgentIdSourceRefPrefixAndVisibilityNotIn(
                agentId, filename + "#", effectiveScope, effectiveOwner,
                facts.stream().map(ExtractedFact::sourceRef).toList(), now);
        log.debug("[FactProjection] rebuildOne: agent={}, file={}, owner={}, scope={}, facts={}",
                agentId, filename, effectiveOwner, effectiveScope, facts.size());
        return facts.size();
    }

    /**
     * Dialect-safe upsert: select by (agent_id, source_ref), then insert or update.
     * Preserves accumulated columns (use_count, last_used_at) on update.
     */
    private void upsertDerived(Long agentId, ExtractedFact fact, LocalDateTime now) {
        FactEntity existing = factMapper.selectOne(
                new LambdaQueryWrapper<FactEntity>()
                        .eq(FactEntity::getAgentId, agentId)
                        .eq(FactEntity::getSourceRef, fact.sourceRef())
                        .eq(FactEntity::getScope, normalizeScope(fact.scope()))
                        .and(w -> {
                            if (fact.ownerKey() == null || fact.ownerKey().isBlank()) {
                                w.isNull(FactEntity::getOwnerKey).or().eq(FactEntity::getOwnerKey, "");
                            } else {
                                w.eq(FactEntity::getOwnerKey, fact.ownerKey());
                            }
                        })
                        .last("LIMIT 1"));

        if (existing != null) {
            // Update derived columns only; preserve accumulated columns
            existing.setCategory(fact.category());
            existing.setSubject(fact.subject());
            existing.setPredicate(fact.predicate());
            existing.setObjectValue(fact.objectValue());
            existing.setConfidence(fact.confidence());
            existing.setExtractedBy(fact.extractedBy());
            existing.setOwnerKey(fact.ownerKey());
            existing.setScope(normalizeScope(fact.scope()));
            // Trust derived from canonical feedback metadata, then time-decayed
            double baseTrust = fact.trust();
            existing.setTrust(applyTimeDecay(baseTrust, existing.getUpdateTime(), now));
            existing.setUpdateTime(now);
            existing.setDeleted(0); // un-delete if previously soft-deleted
            factMapper.updateById(existing);
        } else {
            FactEntity entity = new FactEntity();
            entity.setAgentId(agentId);
            entity.setSourceRef(fact.sourceRef());
            entity.setCategory(fact.category());
            entity.setSubject(fact.subject());
            entity.setPredicate(fact.predicate());
            entity.setObjectValue(fact.objectValue());
            entity.setConfidence(fact.confidence());
            entity.setTrust(fact.trust());
            entity.setUseCount(0);
            entity.setExtractedBy(fact.extractedBy());
            entity.setOwnerKey(fact.ownerKey());
            entity.setScope(normalizeScope(fact.scope()));
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            entity.setDeleted(0);
            factMapper.insert(entity);
        }
    }

    private List<ExtractedFact> withVisibility(List<ExtractedFact> facts, String scope, String ownerKey) {
        String effectiveScope = normalizeScope(scope);
        String effectiveOwner = MemoryScope.PERSONAL.equals(effectiveScope) ? ownerKey : null;
        return facts.stream()
                .map(f -> new ExtractedFact(
                        f.sourceRef(), f.category(), f.subject(), f.predicate(), f.objectValue(),
                        f.confidence(), f.trust(), f.extractedBy(), effectiveOwner, effectiveScope))
                .toList();
    }

    private void softDeleteStaleFacts(Long agentId, List<ExtractedFact> currentFacts, LocalDateTime now) {
        Set<String> keep = currentFacts.stream()
                .map(this::factKey)
                .collect(java.util.stream.Collectors.toSet());
        for (FactEntity existing : factMapper.selectList(
                new LambdaQueryWrapper<FactEntity>()
                        .eq(FactEntity::getAgentId, agentId)
                        .eq(FactEntity::getDeleted, 0))) {
            if (!keep.contains(factKey(existing))) {
                existing.setDeleted(1);
                existing.setUpdateTime(now);
                factMapper.updateById(existing);
            }
        }
    }

    private String factKey(ExtractedFact fact) {
        return fact.sourceRef() + "\u0000" + normalizeScope(fact.scope())
                + "\u0000" + (fact.ownerKey() == null ? "" : fact.ownerKey());
    }

    private String factKey(FactEntity fact) {
        return fact.getSourceRef() + "\u0000" + normalizeScope(fact.getScope())
                + "\u0000" + (fact.getOwnerKey() == null ? "" : fact.getOwnerKey());
    }

    private boolean isPersonal(WorkspaceFileEntity file) {
        return file != null && MemoryScope.PERSONAL.equals(file.getScope())
                && file.getOwnerKey() != null && !file.getOwnerKey().isBlank();
    }

    private String visibilityScope(WorkspaceFileEntity file) {
        return file != null ? normalizeScope(file.getScope()) : MemoryScope.TEAM;
    }

    private String visibilityOwner(WorkspaceFileEntity file) {
        return isPersonal(file) ? file.getOwnerKey() : null;
    }

    private String normalizeScope(String scope) {
        if (MemoryScope.PERSONAL.equals(scope)) return MemoryScope.PERSONAL;
        if (MemoryScope.GLOBAL.equals(scope)) return MemoryScope.GLOBAL;
        return MemoryScope.TEAM;
    }

    /**
     * Apply exponential time decay to trust score.
     * Formula: trust * 2^(-daysSinceLastUpdate / halfLifeDays)
     * Clamped to [0, 1].
     */
    private double applyTimeDecay(Double currentTrust, LocalDateTime lastUpdate, LocalDateTime now) {
        if (currentTrust == null) return 0.5;
        if (lastUpdate == null) return currentTrust;
        int halfLifeDays = properties.getFact().getTrustHalfLifeDays();
        if (halfLifeDays <= 0) return currentTrust; // decay disabled
        long daysDiff = java.time.Duration.between(lastUpdate, now).toDays();
        if (daysDiff <= 0) return currentTrust;
        double decayed = currentTrust * Math.pow(2.0, -(double) daysDiff / halfLifeDays);
        return Math.max(0.0, Math.min(1.0, decayed));
    }
}
