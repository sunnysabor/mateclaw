package vip.mate.memory.fact.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.memory.fact.model.FactContradictionEntity;
import vip.mate.memory.fact.model.FactEntity;
import vip.mate.memory.fact.repository.FactMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Query service for the fact projection.
 * Read-only + bumpUseCount (the only accumulated column writer).
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactQueryService {

    private final FactMapper factMapper;
    private final vip.mate.memory.fact.repository.FactContradictionMapper contradictionMapper;
    private final vip.mate.memory.identity.MemoryOwnerResolver memoryOwnerResolver;

    /**
     * Probe facts by entity name (subject or object match).
     */
    public List<FactEntity> probe(Long agentId, String entity) {
        return probe(agentId, entity, (String) null);
    }

    /**
     * Owner-scoped probe: returns shared facts plus the current owner's
     * PERSONAL facts. Used by the agent-facing fact tool so one user's private
     * projected facts never leak to another user sharing the same agent.
     */
    public List<FactEntity> probe(Long agentId, String entity, String ownerKey) {
        return factMapper.selectList(
                new LambdaQueryWrapper<FactEntity>()
                        .eq(FactEntity::getAgentId, agentId)
                        .eq(FactEntity::getDeleted, 0)
                        .and(w -> w.like(FactEntity::getSubject, entity)
                                .or().like(FactEntity::getObjectValue, entity))
                        .and(s -> applyVisibility(s, ownerKey))
                        .orderByDesc(FactEntity::getTrust)
                        .last("LIMIT 20"));
    }

    /** Probe using the owner carried in Spring AI ToolContext. */
    public List<FactEntity> probe(Long agentId, String entity, ToolContext toolContext) {
        return probe(agentId, entity, memoryOwnerResolver.resolve(ChatOrigin.from(toolContext)));
    }

    /**
     * List unresolved contradictions for an agent.
     */
    public List<FactContradictionEntity> listContradictions(Long agentId) {
        return listContradictions(agentId, (String) null);
    }

    /** Contradiction list using the owner carried in Spring AI ToolContext. */
    public List<FactContradictionEntity> listContradictions(Long agentId, ToolContext toolContext) {
        return listContradictions(agentId, memoryOwnerResolver.resolve(ChatOrigin.from(toolContext)));
    }

    /**
     * Owner-scoped contradiction inbox. A null/system owner sees shared
     * contradictions only; a real owner sees shared + own personal pairs.
     */
    public List<FactContradictionEntity> listContradictions(Long agentId, String ownerKey) {
        return contradictionMapper.selectList(
                new LambdaQueryWrapper<FactContradictionEntity>()
                        .eq(FactContradictionEntity::getAgentId, agentId)
                        .isNull(FactContradictionEntity::getResolution)
                        .eq(FactContradictionEntity::getDeleted, 0)
                        .and(w -> applyContradictionVisibility(w, ownerKey))
                        .orderByDesc(FactContradictionEntity::getCreateTime)
                        .last("LIMIT 50"));
    }

    /**
     * Recall relevant facts for a query (used by FactMemoryProvider.prefetch).
     */
    public List<FactEntity> recallRelevant(Long agentId, String query) {
        return recallRelevant(agentId, query, null);
    }

    /**
     * Owner-scoped recall: returns facts visible to {@code ownerKey} — shared
     * (TEAM / GLOBAL) facts plus this owner's PERSONAL facts. A null ownerKey
     * means shared-only. Keeps one user's recalled facts out of another user's
     * prompt when a single agent is shared across end-users.
     */
    public List<FactEntity> recallRelevant(Long agentId, String query, String ownerKey) {
        return factMapper.selectList(
                new LambdaQueryWrapper<FactEntity>()
                        .eq(FactEntity::getAgentId, agentId)
                        .eq(FactEntity::getDeleted, 0)
                        .and(w -> w.like(FactEntity::getSubject, query)
                                .or().like(FactEntity::getObjectValue, query)
                                .or().like(FactEntity::getPredicate, query))
                        .and(s -> applyVisibility(s, ownerKey))
                        .orderByDesc(FactEntity::getTrust)
                        .last("LIMIT 10"));
    }

    private void applyVisibility(LambdaQueryWrapper<FactEntity> wrapper, String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()
                || vip.mate.memory.identity.MemoryOwnerResolver.SYSTEM_OWNER.equals(ownerKey)) {
            wrapper.in(FactEntity::getScope, vip.mate.memory.identity.MemoryScope.TEAM,
                    vip.mate.memory.identity.MemoryScope.GLOBAL);
            return;
        }
        wrapper.in(FactEntity::getScope, vip.mate.memory.identity.MemoryScope.TEAM,
                        vip.mate.memory.identity.MemoryScope.GLOBAL)
                .or(p -> p.eq(FactEntity::getScope,
                                vip.mate.memory.identity.MemoryScope.PERSONAL)
                        .eq(FactEntity::getOwnerKey, ownerKey));
    }

    private void applyContradictionVisibility(
            LambdaQueryWrapper<FactContradictionEntity> wrapper, String ownerKey) {
        String factA = "SELECT id FROM mate_fact WHERE deleted = 0 AND scope IN ('TEAM','GLOBAL')";
        String factB = "SELECT id FROM mate_fact WHERE deleted = 0 AND scope IN ('TEAM','GLOBAL')";
        if (ownerKey != null && !ownerKey.isBlank()
                && !vip.mate.memory.identity.MemoryOwnerResolver.SYSTEM_OWNER.equals(ownerKey)) {
            String escaped = ownerKey.replace("'", "''");
            factA = "SELECT id FROM mate_fact WHERE deleted = 0 AND (scope IN ('TEAM','GLOBAL') "
                    + "OR (scope = 'PERSONAL' AND owner_key = '" + escaped + "')";
            factA += ")";
            factB = factA;
        }
        wrapper.inSql(FactContradictionEntity::getFactAId, factA)
                .inSql(FactContradictionEntity::getFactBId, factB);
    }

    /**
     * Bump use_count for fact IDs (the ONLY writer of accumulated columns).
     */
    public void bumpUseCount(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        factMapper.bumpUseCount(ids, LocalDateTime.now());
    }
}
