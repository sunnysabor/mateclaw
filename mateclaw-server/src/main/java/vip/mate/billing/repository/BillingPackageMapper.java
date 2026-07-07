package vip.mate.billing.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.billing.model.BillingPackageEntity;

@Mapper
public interface BillingPackageMapper extends BaseMapper<BillingPackageEntity> {
}
