package vip.mate.dify.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("mate_external_workflow_run")
public class ExternalWorkflowRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    private String provider;

    private Long configId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long triggerId;

    private String state;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String requestInputsJson;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String responseOutputsJson;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String responseRawJson;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String externalTaskId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String externalRunId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String externalWorkflowId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorCode;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer totalTokens;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer totalSteps;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal elapsedTimeSeconds;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime startedAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime completedAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String triggeredBy;

    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    private Integer deleted;
}
