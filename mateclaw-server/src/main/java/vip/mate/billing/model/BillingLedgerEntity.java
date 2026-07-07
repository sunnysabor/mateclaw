package vip.mate.billing.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable wallet ledger entry. */
@Data
@TableName("mate_billing_ledger")
public class BillingLedgerEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long walletId;
    private Long userId;
    private Long orderId;
    private String direction;
    private Long amountCents;
    private Long balanceAfterCents;
    private String reason;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
