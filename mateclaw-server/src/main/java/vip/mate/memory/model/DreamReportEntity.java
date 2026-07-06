package vip.mate.memory.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Dream report entity — persists each dream consolidation run.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_dream_report")
public class DreamReportEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long agentId;

    /** NIGHTLY | FOCUSED */
    private String mode;

    /** Topic hint for FOCUSED mode; null for NIGHTLY */
    private String topic;

    /** cron | user | api */
    private String triggerSource;

    /** userId or "system" */
    private String triggeredBy;

    /** Memory subject this report belongs to (e.g. "user:jerry"); null/blank for shared reports. */
    private String ownerKey;

    /** Visibility scope: PERSONAL / TEAM / GLOBAL. Defaults to TEAM for legacy reports. */
    private String scope;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Integer candidateCount;

    private Integer promotedCount;

    private Integer rejectedCount;

    /** Diff between old and new MEMORY.md */
    private String memoryDiff;

    /** LLM explanation (first 500 chars) */
    private String llmReason;

    /** SUCCESS | FAILED | SKIPPED */
    private String status;

    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
