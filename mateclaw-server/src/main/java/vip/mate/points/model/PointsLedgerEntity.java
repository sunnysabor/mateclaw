package vip.mate.points.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable points balance ledger entry. */
@Data
@TableName("mate_points_ledger")
public class PointsLedgerEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long accountId;
    private Long userId;
    /** credit / debit / adjust */
    private String direction;
    private Long amount;
    private Long balanceAfter;
    private String reason;
    private String bizType;
    private String bizId;
    private String remark;
    private Long operatorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
