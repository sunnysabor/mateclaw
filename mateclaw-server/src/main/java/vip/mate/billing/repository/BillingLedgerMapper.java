package vip.mate.billing.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.billing.model.BillingLedgerEntity;

@Mapper
public interface BillingLedgerMapper extends BaseMapper<BillingLedgerEntity> {
}
