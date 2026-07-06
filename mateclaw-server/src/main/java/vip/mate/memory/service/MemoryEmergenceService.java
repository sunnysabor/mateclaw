package vip.mate.memory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vip.mate.memory.event.DreamCompletedEvent;
import vip.mate.memory.event.DreamFailedEvent;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.model.DreamReportEntity;
import vip.mate.memory.model.MemoryRecallEntity;
import vip.mate.memory.repository.DreamReportMapper;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Memory emergence (dream) service.
 * <p>
 * Reads daily notes + scored recall candidates, invokes LLM to consolidate
 * recurring patterns into MEMORY.md, and produces a structured DreamReport.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryEmergenceService {

    private final WorkspaceFileService workspaceFileService;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final MemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final MemoryRecallService recallService;
    private final DreamReportMapper dreamReportMapper;
    private final vip.mate.memory.archive.MemoryArchiveService archiveService;
    private final ApplicationEventPublisher eventPublisher;
    private final vip.mate.memory.fact.contradiction.ContradictionDetector contradictionDetector;

    /**
     * Legacy signature — delegates to NIGHTLY mode for backward compatibility.
     */
    public DreamReport consolidate(Long agentId) {
        return consolidate(agentId, DreamMode.NIGHTLY, null);
    }

    /**
     * Execute memory consolidation with the specified mode and optional topic.
     *
     * @param agentId Agent ID
     * @param mode    NIGHTLY or FOCUSED
     * @param topic   topic hint for FOCUSED mode (null for NIGHTLY)
     * @return structured DreamReport (never null)
     */
    public DreamReport consolidate(Long agentId, DreamMode mode, String topic) {
        return consolidate(agentId, mode, topic, null);
    }

    /**
     * Owner-scoped consolidation. A real owner key consolidates only that
     * owner's PERSONAL daily notes / recall candidates into that owner's
     * PERSONAL MEMORY.md and DREAMS.md. A null/system owner preserves the
     * historical shared TEAM consolidation path.
     */
    public DreamReport consolidate(Long agentId, DreamMode mode, String topic, String ownerKey) {
        LocalDateTime startedAt = LocalDateTime.now();
        String triggerSource = mode == DreamMode.NIGHTLY ? "cron" : "user";
        String effectiveOwner = isPersonal(ownerKey) ? ownerKey : null;

        if (!properties.isEmergenceEnabled()) {
            log.debug("[Memory] Emergence is disabled, skipping for agent={}", agentId);
            return buildSkippedReport(agentId, mode, topic, triggerSource, startedAt, "emergence disabled", effectiveOwner);
        }

        // 1. Load daily notes
        List<WorkspaceFileEntity> allFiles = effectiveOwner != null
                ? workspaceFileService.listVisibleFiles(agentId, effectiveOwner)
                : workspaceFileService.listFiles(agentId);
        List<String> dailyFilenames = allFiles.stream()
                .map(WorkspaceFileEntity::getFilename)
                .filter(f -> f.startsWith("memory/") && f.endsWith(".md"))
                .sorted(Comparator.reverseOrder())
                .limit(properties.getEmergenceDayRange())
                .toList();

        if (dailyFilenames.isEmpty()) {
            log.info("[Memory] No daily notes found for agent={}, skipping emergence", agentId);
            return buildSkippedReport(agentId, mode, topic, triggerSource, startedAt, "no daily notes", effectiveOwner);
        }

        StringBuilder dailyNotesBuilder = new StringBuilder();
        for (String filename : dailyFilenames) {
            WorkspaceFileEntity file = effectiveOwner != null
                    ? workspaceFileService.getVisibleFile(agentId, filename, effectiveOwner)
                    : workspaceFileService.getFile(agentId, filename);
            if (file != null && file.getContent() != null && !file.getContent().isBlank()) {
                dailyNotesBuilder.append("### ").append(filename).append("\n");
                dailyNotesBuilder.append(file.getContent().trim()).append("\n\n");
            }
        }
        String dailyNotes = dailyNotesBuilder.toString().trim();

        if (dailyNotes.isEmpty()) {
            log.info("[Memory] All daily notes are empty for agent={}, skipping emergence", agentId);
            return buildSkippedReport(agentId, mode, topic, triggerSource, startedAt, "all daily notes empty", effectiveOwner);
        }

        // 2. Read existing MEMORY.md (for diff later)
        String oldMemoryContent = readFileContentSafe(agentId, "MEMORY.md", effectiveOwner);

        // 3. Score candidates (must happen before resetDailyCounts)
        // Keep the legacy no-owner path on the legacy method overload. Besides
        // preserving strict Mockito tests, this protects third-party spies /
        // decorators that only override the original one-argument contract.
        List<MemoryRecallEntity> scoredCandidates = effectiveOwner != null
                ? recallService.computeScores(agentId, effectiveOwner)
                : recallService.computeScores(agentId);
        boolean hasScoredCandidates = !scoredCandidates.isEmpty();

        // 4. Reset daily counts for next accumulation cycle
        if (effectiveOwner != null) {
            recallService.resetDailyCounts(agentId, effectiveOwner);
        } else {
            recallService.resetDailyCounts(agentId);
        }

        // 5. Build prompt based on mode
        String systemPrompt = PromptLoader.loadPrompt("memory/emergence-system");
        String userPrompt = buildUserPrompt(mode, topic, oldMemoryContent, scoredCandidates,
                hasScoredCandidates, dailyNotes);

        log.info("[Memory] Emergence {} with {} candidates for agent={}, topic={}",
                mode, scoredCandidates.size(), agentId, topic);

        // 6. Call LLM
        String llmResponse;
        try {
            ChatModel chatModel = buildChatModel();
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
            ));
            ChatResponse response = chatModel.call(prompt);
            llmResponse = response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[Memory] Emergence LLM call failed for agent={}: {}", agentId, e.getMessage());
            return buildFailedReport(agentId, mode, topic, triggerSource, startedAt,
                    scoredCandidates.size(), e.getMessage(), effectiveOwner);
        }

        // 7. Parse and apply
        try {
            JsonNode root = parseJsonResponse(llmResponse);
            if (root == null || !root.path("should_update").asBoolean(false)) {
                String reason = root != null ? root.path("reason").asText("") : "parse failed";
                log.info("[Memory] No emergence update needed for agent={}: {}", agentId, reason);
                return buildSkippedReport(agentId, mode, topic, triggerSource, startedAt, reason, effectiveOwner);
            }

            JsonNode memoryNode = root.path("memory_content");
            if (memoryNode.isNull() || !memoryNode.isTextual()) {
                return buildSkippedReport(agentId, mode, topic, triggerSource, startedAt, "no memory_content in response", effectiveOwner);
            }

            String newContent = memoryNode.asText().trim();
            if (newContent.isEmpty()) {
                return buildSkippedReport(agentId, mode, topic, triggerSource, startedAt, "empty memory_content", effectiveOwner);
            }

            // Deterministic backstop so the always-on MEMORY.md stays bounded even
            // if the LLM rewrite ignores the "keep concise" instruction.
            newContent = AlwaysOnFileBudget.enforce(newContent, properties.getMemoryMdMaxChars());
            saveVisibleMemory(agentId, "MEMORY.md", newContent, effectiveOwner);
            String llmReason = root.path("reason").asText("");
            log.info("[Memory] Emergence completed for agent={}: {}", agentId, llmReason);

            // Determine promoted vs rejected candidates
            List<PromotedEntry> promotedEntries = new ArrayList<>();
            List<RejectedEntry> rejectedEntries = new ArrayList<>();

            if (hasScoredCandidates) {
                Set<Long> promotedIds = new HashSet<>();
                for (MemoryRecallEntity c : scoredCandidates) {
                    if (candidateAdoptedInMemory(c, newContent)) {
                        promotedIds.add(c.getId());
                        promotedEntries.add(new PromotedEntry(
                                c.getId(), c.getFilename(), c.getSnippetPreview(), c.getScore()));
                    } else {
                        // Increment review_count for rejected candidates
                        int newReviewCount = (c.getReviewCount() != null ? c.getReviewCount() : 0) + 1;
                        rejectedEntries.add(new RejectedEntry(
                                c.getId(), c.getFilename(), c.getSnippetPreview(),
                                c.getScore(), newReviewCount));
                    }
                }

                if (!promotedIds.isEmpty()) {
                    recallService.markPromoted(new ArrayList<>(promotedIds));
                }
                // Update review_count / last_reviewed_at for rejected candidates
                recallService.incrementReviewCounts(
                        rejectedEntries.stream().map(RejectedEntry::recallId).toList());

                log.info("[Memory] Promoted {}/{} recall candidates for agent={}",
                        promotedIds.size(), scoredCandidates.size(), agentId);

                // Append dream diary
                appendDreamDiary(agentId, scoredCandidates, new ArrayList<>(promotedIds), mode, topic, effectiveOwner);
            }

            // Compute diff
            String memoryDiff = computeDiff(oldMemoryContent, newContent);

            // Build and persist report
            DreamReport report = buildSuccessReport(agentId, mode, topic, triggerSource, startedAt,
                    scoredCandidates.size(), promotedEntries, rejectedEntries, memoryDiff,
                    truncate(llmReason, 500), effectiveOwner);
            persistReport(report);

            // Contradiction detection — synchronous step after persist (D11).
            // Restrict to the same owner/scope as the dream report; otherwise a
            // personal dream can compare against another owner's private facts.
            try {
                contradictionDetector.detect(agentId, promotedEntries, effectiveOwner,
                        effectiveOwner != null
                                ? vip.mate.memory.identity.MemoryScope.PERSONAL
                                : vip.mate.memory.identity.MemoryScope.TEAM);
            } catch (Exception ce) {
                log.debug("[Memory] Contradiction detection failed (non-fatal): {}", ce.getMessage());
            }

            return report;

        } catch (Exception e) {
            log.warn("[Memory] Failed to parse/apply emergence result for agent={}: {}", agentId, e.getMessage());
            return buildFailedReport(agentId, mode, topic, triggerSource, startedAt,
                    scoredCandidates.size(), e.getMessage(), effectiveOwner);
        }
    }

    /**
     * Build user prompt based on dream mode.
     */
    private String buildUserPrompt(DreamMode mode, String topic, String memoryContent,
                                   List<MemoryRecallEntity> scoredCandidates,
                                   boolean hasScoredCandidates, String dailyNotes) {
        if (mode == DreamMode.FOCUSED && topic != null && !topic.isBlank()) {
            // FOCUSED mode: use topic-biased prompt
            String candidatesText = hasScoredCandidates ? formatScoredCandidates(scoredCandidates) : "(no scored candidates)";
            String userTemplate = PromptLoader.loadPrompt("memory/emergence-focused-user");
            return userTemplate
                    .replace("{memory}", memoryContent)
                    .replace("{topic}", topic)
                    .replace("{scored_candidates}", candidatesText)
                    .replace("{day_range}", String.valueOf(properties.getEmergenceDayRange()))
                    .replace("{daily_notes}", dailyNotes);
        }

        // NIGHTLY mode: existing scored or plain prompt
        if (hasScoredCandidates) {
            String candidatesText = formatScoredCandidates(scoredCandidates);
            String userTemplate = PromptLoader.loadPrompt("memory/emergence-scored-user");
            return userTemplate
                    .replace("{memory}", memoryContent)
                    .replace("{scored_candidates}", candidatesText)
                    .replace("{day_range}", String.valueOf(properties.getEmergenceDayRange()))
                    .replace("{daily_notes}", dailyNotes);
        } else {
            String userTemplate = PromptLoader.loadPrompt("memory/emergence-user");
            return userTemplate
                    .replace("{memory}", memoryContent)
                    .replace("{day_range}", String.valueOf(properties.getEmergenceDayRange()))
                    .replace("{daily_notes}", dailyNotes);
        }
    }

    /**
     * Append dream diary to DREAMS.md.
     */
    void appendDreamDiary(Long agentId, List<MemoryRecallEntity> allCandidates,
                          List<Long> promotedIds, DreamMode mode, String topic) {
        appendDreamDiary(agentId, allCandidates, promotedIds, mode, topic, null);
    }

    void appendDreamDiary(Long agentId, List<MemoryRecallEntity> allCandidates,
                          List<Long> promotedIds, DreamMode mode, String topic,
                          String ownerKey) {
        try {
            Set<Long> promotedSet = new HashSet<>(promotedIds);

            List<MemoryRecallEntity> promoted = allCandidates.stream()
                    .filter(c -> promotedSet.contains(c.getId()))
                    .sorted(Comparator.comparingDouble(MemoryRecallEntity::getScore).reversed())
                    .toList();
            List<MemoryRecallEntity> kept = allCandidates.stream()
                    .filter(c -> !promotedSet.contains(c.getId()))
                    .sorted(Comparator.comparingDouble(MemoryRecallEntity::getScore).reversed())
                    .toList();

            String timestamp = LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

            StringBuilder diary = new StringBuilder();
            diary.append("## ").append(timestamp).append(" Dreaming");
            if (mode == DreamMode.FOCUSED && topic != null) {
                diary.append(" [FOCUSED: ").append(topic).append("]");
            }
            diary.append("\n\n");
            diary.append(String.format("**评分候选**: %d 条（阈值 %.1f）\n",
                    allCandidates.size(), properties.getEmergenceScoreThreshold()));
            diary.append(String.format("**实际整合**: %d 条\n\n", promoted.size()));

            if (!promoted.isEmpty()) {
                diary.append("### 已整合\n");
                for (MemoryRecallEntity c : promoted) {
                    diary.append(String.format("- `%s` (score=%.2f, recalls=%d)\n",
                            c.getFilename(), c.getScore(), c.getRecallCount()));
                }
                diary.append("\n");
            }

            if (!kept.isEmpty()) {
                diary.append("### 未整合（保留下轮）\n");
                for (MemoryRecallEntity c : kept) {
                    diary.append(String.format("- `%s` (score=%.2f, recalls=%d)\n",
                            c.getFilename(), c.getScore(), c.getRecallCount()));
                }
                diary.append("\n");
            }

            // Read existing DREAMS.md, append new diary
            String existing = readFileContentSafe(agentId, "DREAMS.md", ownerKey);
            String newContent = existing.isBlank()
                    ? "# Dreaming 整合日记\n\n" + diary
                    : existing + "\n" + diary;

            // Fallback hard truncation at 20KB (Phase 0 behavior preserved when archive flag is off)
            if (newContent.length() > 20_000) {
                int cutPoint = newContent.length() - 16_000;
                int safePoint = newContent.indexOf("\n## ", cutPoint);
                if (safePoint > 0) {
                    newContent = "# Dreaming 整合日记\n\n> 早期记录已归档\n\n"
                            + newContent.substring(safePoint + 1);
                } else {
                    newContent = "# Dreaming 整合日记\n\n> 早期记录已归档\n\n"
                            + newContent.substring(cutPoint);
                }
            }

            saveVisibleMemory(agentId, "DREAMS.md", newContent, ownerKey);
            log.info("[Memory] Dream diary appended for agent={}", agentId);

            // Archive old entries or fall back to 20KB truncation
            if (properties.getDream().isArchiveEnabled()) {
                if (ownerKey != null && !ownerKey.isBlank()
                        && !vip.mate.memory.identity.MemoryOwnerResolver.SYSTEM_OWNER.equals(ownerKey)) {
                    archiveService.archiveOldDreams(agentId, ownerKey);
                } else {
                    archiveService.archiveOldDreams(agentId);
                }
            }
        } catch (Exception e) {
            log.warn("[Memory] Failed to write dream diary for agent={}: {}", agentId, e.getMessage());
        }
    }

    // ==================== Report builders ====================

    private DreamReport buildSuccessReport(Long agentId, DreamMode mode, String topic,
                                           String triggerSource, LocalDateTime startedAt,
                                           int candidateCount,
                                           List<PromotedEntry> promoted,
                                           List<RejectedEntry> rejected,
                                           String memoryDiff, String llmReason,
                                           String ownerKey) {
        return new DreamReport(null, agentId, mode, topic, triggerSource, "system",
                ownerKey,
                ownerKey != null ? vip.mate.memory.identity.MemoryScope.PERSONAL
                        : vip.mate.memory.identity.MemoryScope.TEAM,
                startedAt, LocalDateTime.now(), candidateCount,
                promoted.size(), rejected.size(), memoryDiff, llmReason,
                DreamStatus.SUCCESS, null, promoted, rejected);
    }

    private DreamReport buildSkippedReport(Long agentId, DreamMode mode, String topic,
                                           String triggerSource, LocalDateTime startedAt,
                                           String reason, String ownerKey) {
        DreamReport report = new DreamReport(null, agentId, mode, topic, triggerSource, "system",
                ownerKey,
                ownerKey != null ? vip.mate.memory.identity.MemoryScope.PERSONAL
                        : vip.mate.memory.identity.MemoryScope.TEAM,
                startedAt, LocalDateTime.now(), 0, 0, 0, null, reason,
                DreamStatus.SKIPPED, null, List.of(), List.of());
        persistReport(report);
        return report;
    }

    private DreamReport buildFailedReport(Long agentId, DreamMode mode, String topic,
                                          String triggerSource, LocalDateTime startedAt,
                                          int candidateCount, String errorMessage,
                                          String ownerKey) {
        DreamReport report = new DreamReport(null, agentId, mode, topic, triggerSource, "system",
                ownerKey,
                ownerKey != null ? vip.mate.memory.identity.MemoryScope.PERSONAL
                        : vip.mate.memory.identity.MemoryScope.TEAM,
                startedAt, LocalDateTime.now(), candidateCount, 0, 0, null, null,
                DreamStatus.FAILED, errorMessage, List.of(), List.of());
        persistReport(report);
        return report;
    }

    private void persistReport(DreamReport report) {
        try {
            DreamReportEntity entity = new DreamReportEntity();
            entity.setAgentId(report.agentId());
            entity.setMode(report.mode().name());
            entity.setTopic(report.topic());
            entity.setTriggerSource(report.triggerSource());
            entity.setTriggeredBy(report.triggeredBy());
            entity.setOwnerKey(report.ownerKey());
            entity.setScope(report.scope());
            entity.setStartedAt(report.startedAt());
            entity.setFinishedAt(report.finishedAt());
            entity.setCandidateCount(report.candidateCount());
            entity.setPromotedCount(report.promotedCount());
            entity.setRejectedCount(report.rejectedCount());
            entity.setMemoryDiff(report.memoryDiff());
            entity.setLlmReason(report.llmReason());
            entity.setStatus(report.status().name());
            entity.setErrorMessage(report.errorMessage());
            entity.setCreateTime(LocalDateTime.now());
            entity.setUpdateTime(LocalDateTime.now());
            entity.setDeleted(0);
            dreamReportMapper.insert(entity);
            log.debug("[Memory] DreamReport persisted: agent={}, mode={}, status={}",
                    report.agentId(), report.mode(), report.status());
            // Publish event for SSE broadcast
            if (report.status() == DreamStatus.SUCCESS) {
                eventPublisher.publishEvent(new DreamCompletedEvent(report));
            } else if (report.status() == DreamStatus.FAILED) {
                eventPublisher.publishEvent(new DreamFailedEvent(report));
            }
        } catch (Exception e) {
            log.warn("[Memory] Failed to persist DreamReport for agent={}: {}", report.agentId(), e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private String formatScoredCandidates(List<MemoryRecallEntity> candidates) {
        StringBuilder sb = new StringBuilder();
        for (MemoryRecallEntity entry : candidates) {
            sb.append(String.format("### %s (score=%.2f, recalls=%d)\n",
                    entry.getFilename(), entry.getScore(), entry.getRecallCount()));
            if (entry.getSnippetPreview() != null) {
                sb.append(entry.getSnippetPreview());
                sb.append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean candidateAdoptedInMemory(MemoryRecallEntity candidate, String newMemoryContent) {
        String preview = candidate.getSnippetPreview();
        if (preview == null || preview.isBlank() || newMemoryContent == null) {
            return false;
        }
        String[] lines = preview.split("\n");
        int matched = 0;
        int checked = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (checked >= 3) break;
            checked++;
            String key = trimmed.replaceAll("^[-*>]+\\s*", "");
            if (key.length() > 20) key = key.substring(0, 20);
            if (key.length() >= 5 && newMemoryContent.contains(key)) {
                matched++;
            }
        }
        return matched > 0;
    }

    private String computeDiff(String oldContent, String newContent) {
        if (oldContent == null || oldContent.isBlank()) return "(new file)";
        if (oldContent.equals(newContent)) return "(no change)";
        // Simple line-count diff for Phase 1
        int oldLines = oldContent.split("\n").length;
        int newLines = newContent.split("\n").length;
        return String.format("-%d/+%d lines", oldLines, newLines);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private ChatModel buildChatModel() {
        ModelConfigEntity defaultModel = modelConfigService.getDefaultModel();
        return agentGraphBuilder.buildRuntimeChatModel(defaultModel);
    }

    private JsonNode parseJsonResponse(String response) {
        if (response == null || response.isBlank()) return null;

        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("[Memory] Failed to parse emergence JSON response: {}", e.getMessage());
            return null;
        }
    }

    String readFileContentSafe(Long agentId, String filename) {
        return readFileContentSafe(agentId, filename, null);
    }

    String readFileContentSafe(Long agentId, String filename, String ownerKey) {
        try {
            WorkspaceFileEntity file = isPersonal(ownerKey)
                    ? workspaceFileService.getVisibleFile(agentId, filename, ownerKey)
                    : workspaceFileService.getFile(agentId, filename);
            return file != null && file.getContent() != null ? file.getContent() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void saveVisibleMemory(Long agentId, String filename, String content, String ownerKey) {
        if (isPersonal(ownerKey)) {
            workspaceFileService.saveMemoryFile(agentId, filename, content, ownerKey);
        } else {
            workspaceFileService.saveFile(agentId, filename, content);
        }
    }

    private boolean isPersonal(String ownerKey) {
        return ownerKey != null && !ownerKey.isBlank()
                && !vip.mate.memory.identity.MemoryOwnerResolver.SYSTEM_OWNER.equals(ownerKey);
    }
}
