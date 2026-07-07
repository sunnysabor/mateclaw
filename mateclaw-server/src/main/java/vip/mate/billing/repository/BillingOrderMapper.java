package vip.mate.billing.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.mate.billing.model.BillingOrderEntity;

@Mapper
public interface BillingOrderMapper extends BaseMapper<BillingOrderEntity> {
    @Update("UPDATE mate_billing_order SET status = 'paid', paid_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP WHERE id = #{orderId} AND user_id = #{userId} AND status = 'pending' AND deleted = 0")
    int markOwnPendingPaid(@Param("orderId") Long orderId, @Param("userId") Long userId);

    @Update("UPDATE mate_billing_order SET status = 'cancelled', update_time = CURRENT_TIMESTAMP WHERE id = #{orderId} AND user_id = #{userId} AND status = 'pending' AND deleted = 0")
    int cancelOwnPending(@Param("orderId") Long orderId, @Param("userId") Long userId);
}
