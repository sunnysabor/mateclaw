package vip.mate.agent.team.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * User-defined multi-agent team. A team is not a runtime by itself; the
 * coordinator Agent is the chat entry point and delegates to member Agents.
 */
@Data
@TableName("mate_agent_team")
public class AgentTeamEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    private String name;

    private String description;

    /** Native MateClaw coordinator Agent (react / plan_execute, never ACP). */
    private Long coordinatorAgentId;

    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
