package vip.mate.billing.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.mate.billing.model.BillingWalletEntity;

@Mapper
public interface BillingWalletMapper extends BaseMapper<BillingWalletEntity> {
    @Update("UPDATE mate_billing_wallet SET balance_cents = balance_cents + #{amountCents}, update_time = CURRENT_TIMESTAMP WHERE id = #{walletId} AND deleted = 0")
    int incrementBalance(@Param("walletId") Long walletId, @Param("amountCents") Long amountCents);
}
