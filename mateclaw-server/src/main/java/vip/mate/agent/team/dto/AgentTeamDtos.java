package vip.mate.agent.team.dto;

import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.team.model.AgentTeamEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class AgentTeamDtos {
    private AgentTeamDtos() {}

    public record MemberInput(Long agentId, String roleLabel) {}

    public record CreateTeamRequest(
            String name,
            String description,
            Long coordinatorAgentId,
            List<MemberInput> members,
            Boolean enabled) {}

    public record UpdateTeamRequest(
            String name,
            String description,
            Long coordinatorAgentId,
            List<MemberInput> members,
            Boolean enabled) {}

    public record MemberVO(
            Long agentId,
            String agentName,
            String agentType,
            String acpEndpointName,
            String roleLabel,
            Boolean enabled,
            Integer sortOrder) {}

    public record TeamVO(
            Long id,
            Long workspaceId,
            String name,
            String description,
            Long coordinatorAgentId,
            String coordinatorAgentName,
            String coordinatorAgentType,
            Boolean enabled,
            List<MemberVO> members,
            LocalDateTime createTime,
            LocalDateTime updateTime) {

        public static TeamVO from(AgentTeamEntity team, AgentEntity coordinator, List<MemberVO> members) {
            return new TeamVO(
                    team.getId(),
                    team.getWorkspaceId(),
                    team.getName(),
                    team.getDescription(),
                    team.getCoordinatorAgentId(),
                    coordinator != null ? coordinator.getName() : null,
                    coordinator != null ? coordinator.getAgentType() : null,
                    team.getEnabled(),
                    members != null ? members : new ArrayList<>(),
                    team.getCreateTime(),
                    team.getUpdateTime()
            );
        }
    }
}
