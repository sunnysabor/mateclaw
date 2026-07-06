package vip.mate.dify.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mate_dify_workflow_config")
public class DifyWorkflowConfigEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String configKey;

    private String name;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    @TableField(value = "api_key_cipher", updateStrategy = FieldStrategy.ALWAYS)
    private String apiKeyCipher;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String inputSchemaJson;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String defaultInputsJson;

    private Boolean enabled;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastTestStatus;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastTestError;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastTestAt;

    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
