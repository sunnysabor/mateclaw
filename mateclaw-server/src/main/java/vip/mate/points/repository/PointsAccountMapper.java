package vip.mate.points.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.mate.points.model.PointsAccountEntity;

@Mapper
public interface PointsAccountMapper extends BaseMapper<PointsAccountEntity> {
    @Update("UPDATE mate_points_account SET balance = balance + #{delta}, total_earned = total_earned + #{earnedDelta}, total_spent = total_spent + #{spentDelta}, update_time = CURRENT_TIMESTAMP WHERE id = #{accountId} AND deleted = 0 AND balance + #{delta} >= 0")
    int applyDelta(@Param("accountId") Long accountId,
                   @Param("delta") Long delta,
                   @Param("earnedDelta") Long earnedDelta,
                   @Param("spentDelta") Long spentDelta);
}
