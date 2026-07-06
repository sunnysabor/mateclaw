package vip.mate.memory.scheduler;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.service.MemoryEmergenceService;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dreaming 定时调度器
 * <p>
 * 按配置的 cron 表达式定期执行记忆整合，
 * 遍历所有启用的 Agent，对每个 Agent 执行评分驱动的 emergence。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DreamingScheduler {

    private final AgentService agentService;
    private final MemoryEmergenceService emergenceService;
    private final MemoryProperties properties;
    private final WorkspaceFileService workspaceFileService;

    /** 上次 dreaming 执行时间（供状态 API 读取） */
    @Getter
    private volatile LocalDateTime lastRunTime;

    @Scheduled(cron = "${mate.memory.dreaming-cron:0 0 3 * * ?}")
    public void runDreaming() {
        if (!properties.isDreamingEnabled()) {
            log.debug("[Dreaming] Scheduled dreaming is disabled, skipping");
            return;
        }

        log.info("[Dreaming] Starting scheduled dreaming cycle");
        List<AgentEntity> agents = agentService.listAgents();

        int success = 0;
        int failed = 0;

        for (AgentEntity agent : agents) {
            if (!Boolean.TRUE.equals(agent.getEnabled())) {
                continue;
            }
            for (String ownerKey : nightlyBuckets(agent.getId())) {
                try {
                    emergenceService.consolidate(agent.getId(),
                            vip.mate.memory.service.DreamMode.NIGHTLY, null, ownerKey);
                    success++;
                } catch (Exception e) {
                    failed++;
                    log.warn("[Dreaming] Failed for agent={} ({}) owner={}: {}",
                            agent.getId(), agent.getName(), ownerKey, e.getMessage());
                }
            }
        }

        lastRunTime = LocalDateTime.now();
        log.info("[Dreaming] Cycle completed: {} succeeded, {} failed", success, failed);
    }

    /**
     * Nightly emergence must process every bucket that can accumulate daily
     * notes: shared TEAM memory plus each PERSONAL owner with memory/*.md rows.
     * Otherwise per-owner daily notes would never promote into that owner's
     * MEMORY.md unless the user manually triggered Dream.
     */
    private List<String> nightlyBuckets(Long agentId) {
        if (!properties.isLifecycleMediatorEnabled()) {
            return List.of((String) null);
        }
        Set<String> owners = new LinkedHashSet<>();
        owners.add(null); // shared bucket
        for (WorkspaceFileEntity file : workspaceFileService.listAllFilesForMaintenance(agentId)) {
            if (file.getFilename() != null
                    && file.getFilename().startsWith("memory/")
                    && file.getFilename().endsWith(".md")
                    && vip.mate.memory.identity.MemoryScope.PERSONAL.equals(file.getScope())
                    && file.getOwnerKey() != null
                    && !file.getOwnerKey().isBlank()) {
                owners.add(file.getOwnerKey());
            }
        }
        return List.copyOf(owners);
    }
}
