package vip.mate.points.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.points.model.PointsLedgerEntity;

@Mapper
public interface PointsLedgerMapper extends BaseMapper<PointsLedgerEntity> {
}
