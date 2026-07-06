package vip.mate.agent.team.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.agent.team.model.AgentTeamMemberEntity;

@Mapper
public interface AgentTeamMemberMapper extends BaseMapper<AgentTeamMemberEntity> {
}
