package vip.mate.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.memory.identity.MemoryScope;
import vip.mate.memory.model.DreamReportEntity;
import vip.mate.memory.model.MorningCardSeenEntity;
import vip.mate.memory.repository.DreamReportMapper;
import vip.mate.memory.repository.MorningCardSeenMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Morning card service — determines whether to show a dream summary card
 * when a user enters an agent view. Scope is per (userId, agentId).
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MorningCardService {

    private final MorningCardSeenMapper seenMapper;
    private final DreamReportMapper dreamReportMapper;

    /**
     * Get the morning card for a user+agent. Returns null if no unseen dream exists.
     */
    public Map<String, Object> getCardFor(Long userId, Long agentId) {
        return getCardFor(userId, agentId, null);
    }

    /**
     * Get the latest unseen dream visible to the current owner. The card shows
     * memory diffs, so it must not surface another owner's PERSONAL dream.
     */
    public Map<String, Object> getCardFor(Long userId, Long agentId, String ownerKey) {
        if (userId == null || agentId == null) return null;
        // Find the latest successful dream report for this agent
        LambdaQueryWrapper<DreamReportEntity> query = new LambdaQueryWrapper<DreamReportEntity>()
                .eq(DreamReportEntity::getAgentId, agentId)
                .eq(DreamReportEntity::getStatus, "SUCCESS")
                .eq(DreamReportEntity::getDeleted, 0);
        applyVisibility(query, ownerKey);
        DreamReportEntity latestReport = dreamReportMapper.selectOne(
                query.orderByDesc(DreamReportEntity::getStartedAt)
                        .last("LIMIT 1"));

        if (latestReport == null) {
            return null; // No dream yet
        }

        // Check if user has already seen this report
        MorningCardSeenEntity seen = seenMapper.selectOne(
                new LambdaQueryWrapper<MorningCardSeenEntity>()
                        .eq(MorningCardSeenEntity::getUserId, userId)
                        .eq(MorningCardSeenEntity::getAgentId, agentId));

        if (seen != null && seen.getLastReportId() != null
                && seen.getLastReportId().equals(latestReport.getId())) {
            return null; // Already seen
        }

        // Build card data
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("reportId", latestReport.getId());
        card.put("mode", latestReport.getMode());
        card.put("topic", latestReport.getTopic());
        card.put("startedAt", latestReport.getStartedAt());
        card.put("promotedCount", latestReport.getPromotedCount());
        card.put("rejectedCount", latestReport.getRejectedCount());
        card.put("llmReason", latestReport.getLlmReason());
        card.put("memoryDiff", latestReport.getMemoryDiff());
        return card;
    }

    private void applyVisibility(LambdaQueryWrapper<DreamReportEntity> query, String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()
                || MemoryOwnerResolver.SYSTEM_OWNER.equals(ownerKey)) {
            query.in(DreamReportEntity::getScope, MemoryScope.TEAM, MemoryScope.GLOBAL);
            return;
        }
        query.and(s -> s.in(DreamReportEntity::getScope, MemoryScope.TEAM, MemoryScope.GLOBAL)
                .or(p -> p.eq(DreamReportEntity::getScope, MemoryScope.PERSONAL)
                        .eq(DreamReportEntity::getOwnerKey, ownerKey)));
    }

    /**
     * Mark the morning card as seen for a user+agent.
     */
    public void markSeen(Long userId, Long agentId, Long reportId) {
        MorningCardSeenEntity existing = seenMapper.selectOne(
                new LambdaQueryWrapper<MorningCardSeenEntity>()
                        .eq(MorningCardSeenEntity::getUserId, userId)
                        .eq(MorningCardSeenEntity::getAgentId, agentId));

        if (existing != null) {
            existing.setLastSeenAt(LocalDateTime.now());
            existing.setLastReportId(reportId);
            existing.setUpdateTime(LocalDateTime.now());
            seenMapper.updateById(existing);
        } else {
            MorningCardSeenEntity entity = new MorningCardSeenEntity();
            entity.setUserId(userId);
            entity.setAgentId(agentId);
            entity.setLastSeenAt(LocalDateTime.now());
            entity.setLastReportId(reportId);
            entity.setCreateTime(LocalDateTime.now());
            entity.setUpdateTime(LocalDateTime.now());
            seenMapper.insert(entity);
        }
    }
}
