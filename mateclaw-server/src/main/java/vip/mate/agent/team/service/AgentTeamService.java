package vip.mate.agent.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.agent.team.dto.AgentTeamDtos;
import vip.mate.agent.team.dto.AgentTeamDtos.MemberInput;
import vip.mate.agent.team.dto.AgentTeamDtos.MemberVO;
import vip.mate.agent.team.dto.AgentTeamDtos.TeamVO;
import vip.mate.agent.team.model.AgentTeamEntity;
import vip.mate.agent.team.model.AgentTeamMemberEntity;
import vip.mate.agent.team.repository.AgentTeamMapper;
import vip.mate.agent.team.repository.AgentTeamMemberMapper;
import vip.mate.exception.MateClawException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CRUD for user-defined teams. The hard rule is enforced here: a team must use
 * a native MateClaw coordinator Agent (react / plan_execute). ACP Agents may be
 * members, but cannot be the coordinator because ACP employees cannot call
 * MateClaw's delegateToAgent/delegateParallel tools in v1.
 */
@Service
@RequiredArgsConstructor
public class AgentTeamService {

    public static final int MAX_TEAM_MEMBERS = 12;

    private final AgentTeamMapper teamMapper;
    private final AgentTeamMemberMapper memberMapper;
    private final AgentMapper agentMapper;
    private final AgentService agentService;

    public List<TeamVO> list(long workspaceId) {
        List<AgentTeamEntity> teams = teamMapper.selectList(new LambdaQueryWrapper<AgentTeamEntity>()
                .eq(AgentTeamEntity::getWorkspaceId, workspaceId)
                .orderByDesc(AgentTeamEntity::getUpdateTime));
        return teams.stream().map(team -> toVO(team, workspaceId)).toList();
    }

    public TeamVO get(long id, long workspaceId) {
        AgentTeamEntity team = getOrThrow(id, workspaceId);
        return toVO(team, workspaceId);
    }

    @Transactional
    public TeamVO create(long workspaceId, AgentTeamDtos.CreateTeamRequest request) {
        if (request == null) {
            throw new MateClawException("err.agent_team.request_required", 400, "团队配置不能为空");
        }
        requireUniqueName(workspaceId, request.name(), null);
        AgentEntity coordinator = requireNativeCoordinator(request.coordinatorAgentId(), workspaceId);
        List<MemberInput> members = normalizeMembers(request.members(), coordinator.getId());
        validateMembers(workspaceId, members);

        AgentTeamEntity team = new AgentTeamEntity();
        team.setWorkspaceId(workspaceId);
        team.setName(request.name().trim());
        team.setDescription(blankToNull(request.description()));
        team.setCoordinatorAgentId(coordinator.getId());
        team.setEnabled(request.enabled() == null ? true : request.enabled());
        team.setDeleted(0);
        teamMapper.insert(team);
        replaceMembers(team.getId(), members);
        ensureCoordinatorPrompt(team, coordinator, members);
        return toVO(teamMapper.selectById(team.getId()), workspaceId);
    }

    @Transactional
    public TeamVO update(long id, long workspaceId, AgentTeamDtos.UpdateTeamRequest request) {
        if (request == null) {
            throw new MateClawException("err.agent_team.request_required", 400, "团队配置不能为空");
        }
        AgentTeamEntity team = getOrThrow(id, workspaceId);
        String nextName = request.name() != null ? request.name().trim() : team.getName();
        if (!Objects.equals(nextName, team.getName())) {
            requireUniqueName(workspaceId, nextName, id);
            team.setName(nextName);
        }
        if (request.description() != null) team.setDescription(blankToNull(request.description()));
        if (request.enabled() != null) team.setEnabled(request.enabled());

        Long nextCoordinatorId = request.coordinatorAgentId() != null
                ? request.coordinatorAgentId()
                : team.getCoordinatorAgentId();
        AgentEntity coordinator = requireNativeCoordinator(nextCoordinatorId, workspaceId);
        team.setCoordinatorAgentId(coordinator.getId());

        List<MemberInput> members = request.members() != null
                ? normalizeMembers(request.members(), coordinator.getId())
                : existingMembersAsInput(team.getId());
        validateMembers(workspaceId, members);

        teamMapper.updateById(team);
        if (request.members() != null) {
            replaceMembers(team.getId(), members);
        }
        ensureCoordinatorPrompt(team, coordinator, members);
        return toVO(teamMapper.selectById(id), workspaceId);
    }

    @Transactional
    public void delete(long id, long workspaceId) {
        getOrThrow(id, workspaceId);
        memberMapper.delete(new LambdaQueryWrapper<AgentTeamMemberEntity>()
                .eq(AgentTeamMemberEntity::getTeamId, id));
        teamMapper.deleteById(id);
    }

    private AgentTeamEntity getOrThrow(long id, long workspaceId) {
        AgentTeamEntity team = teamMapper.selectById(id);
        if (team == null || team.getWorkspaceId() == null || team.getWorkspaceId() != workspaceId) {
            throw new MateClawException("err.agent_team.not_found", 404, "团队不存在: " + id);
        }
        return team;
    }

    private void requireUniqueName(long workspaceId, String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            throw new MateClawException("err.agent_team.name_required", 400, "团队名称不能为空");
        }
        LambdaQueryWrapper<AgentTeamEntity> q = new LambdaQueryWrapper<AgentTeamEntity>()
                .eq(AgentTeamEntity::getWorkspaceId, workspaceId)
                .eq(AgentTeamEntity::getName, name.trim());
        if (excludeId != null) q.ne(AgentTeamEntity::getId, excludeId);
        Long count = teamMapper.selectCount(q);
        if (count != null && count > 0) {
            throw new MateClawException("err.agent_team.duplicate_name", 409,
                    "工作区内已存在同名团队: " + name.trim());
        }
    }

    private AgentEntity requireNativeCoordinator(Long agentId, long workspaceId) {
        if (agentId == null) {
            throw new MateClawException("err.agent_team.coordinator_required", 400,
                    "组建团队必须选择一个原生 HHAIOS 员工作为团队协调官");
        }
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null || agent.getWorkspaceId() == null || agent.getWorkspaceId() != workspaceId) {
            throw new MateClawException("err.agent_team.coordinator_not_found", 400,
                    "团队协调官不属于当前工作区");
        }
        if (!Boolean.TRUE.equals(agent.getEnabled())) {
            throw new MateClawException("err.agent_team.coordinator_disabled", 400,
                    "团队协调官必须是已启用员工");
        }
        if ("acp".equalsIgnoreCase(agent.getAgentType())) {
            throw new MateClawException("err.agent_team.coordinator_must_be_native", 400,
                    "组建团队必须选择一个原生 HHAIOS 员工作为团队协调官；ACP 员工可以作为成员，但不能担任协调官");
        }
        String type = agent.getAgentType() == null ? "react" : agent.getAgentType().trim().toLowerCase();
        if (!"react".equals(type) && !"plan_execute".equals(type)) {
            throw new MateClawException("err.agent_team.coordinator_must_be_native", 400,
                    "团队协调官必须是 react 或 plan_execute 类型的原生 HHAIOS 员工");
        }
        return agent;
    }

    private List<MemberInput> normalizeMembers(List<MemberInput> raw, Long coordinatorId) {
        List<MemberInput> input = raw == null ? List.of() : raw;
        Map<Long, String> byAgent = new LinkedHashMap<>();
        for (MemberInput item : input) {
            if (item == null || item.agentId() == null) continue;
            byAgent.putIfAbsent(item.agentId(), blankToNull(item.roleLabel()));
        }
        if (coordinatorId != null) {
            byAgent.putIfAbsent(coordinatorId, "团队协调官");
        }
        if (byAgent.isEmpty()) {
            throw new MateClawException("err.agent_team.members_required", 400,
                    "团队至少需要一个成员");
        }
        if (byAgent.size() > MAX_TEAM_MEMBERS) {
            throw new MateClawException("err.agent_team.members_too_many", 400,
                    "团队成员最多 " + MAX_TEAM_MEMBERS + " 个");
        }
        List<MemberInput> out = new ArrayList<>();
        byAgent.forEach((agentId, role) -> out.add(new MemberInput(agentId, role)));
        return out;
    }

    private void validateMembers(long workspaceId, List<MemberInput> members) {
        Set<Long> seen = new HashSet<>();
        for (MemberInput member : members) {
            if (member.agentId() == null || !seen.add(member.agentId())) continue;
            AgentEntity agent = agentMapper.selectById(member.agentId());
            if (agent == null || agent.getWorkspaceId() == null || agent.getWorkspaceId() != workspaceId) {
                throw new MateClawException("err.agent_team.member_not_found", 400,
                        "团队成员不属于当前工作区: " + member.agentId());
            }
            if (!Boolean.TRUE.equals(agent.getEnabled())) {
                throw new MateClawException("err.agent_team.member_disabled", 400,
                        "团队成员必须是已启用员工: " + agent.getName());
            }
        }
    }

    private void replaceMembers(Long teamId, List<MemberInput> members) {
        memberMapper.delete(new LambdaQueryWrapper<AgentTeamMemberEntity>()
                .eq(AgentTeamMemberEntity::getTeamId, teamId));
        int order = 0;
        for (MemberInput member : members) {
            AgentTeamMemberEntity row = new AgentTeamMemberEntity();
            row.setTeamId(teamId);
            row.setAgentId(member.agentId());
            row.setRoleLabel(member.roleLabel());
            row.setSortOrder(order++);
            row.setDeleted(0);
            memberMapper.insert(row);
        }
    }

    private List<MemberInput> existingMembersAsInput(Long teamId) {
        return memberMapper.selectList(new LambdaQueryWrapper<AgentTeamMemberEntity>()
                        .eq(AgentTeamMemberEntity::getTeamId, teamId)
                        .orderByAsc(AgentTeamMemberEntity::getSortOrder))
                .stream()
                .map(m -> new MemberInput(m.getAgentId(), m.getRoleLabel()))
                .toList();
    }

    private TeamVO toVO(AgentTeamEntity team, long workspaceId) {
        AgentEntity coordinator = team.getCoordinatorAgentId() == null
                ? null
                : agentMapper.selectById(team.getCoordinatorAgentId());
        List<AgentTeamMemberEntity> rows = memberMapper.selectList(new LambdaQueryWrapper<AgentTeamMemberEntity>()
                .eq(AgentTeamMemberEntity::getTeamId, team.getId())
                .orderByAsc(AgentTeamMemberEntity::getSortOrder));
        List<MemberVO> members = rows.stream()
                .map(row -> {
                    AgentEntity agent = row.getAgentId() == null ? null : agentMapper.selectById(row.getAgentId());
                    if (agent == null || agent.getWorkspaceId() == null || agent.getWorkspaceId() != workspaceId) {
                        return new MemberVO(row.getAgentId(), null, null, null,
                                row.getRoleLabel(), false, row.getSortOrder());
                    }
                    return new MemberVO(agent.getId(), agent.getName(), agent.getAgentType(),
                            agent.getAcpEndpointName(), row.getRoleLabel(), agent.getEnabled(), row.getSortOrder());
                })
                .toList();
        return TeamVO.from(team, coordinator, members);
    }

    private void ensureCoordinatorPrompt(AgentTeamEntity team, AgentEntity coordinator, List<MemberInput> members) {
        if (team == null || coordinator == null || members == null) return;
        String block = buildCoordinatorTeamBlock(team, members);
        String current = coordinator.getSystemPrompt() == null ? "" : coordinator.getSystemPrompt();
        String next = upsertTeamBlock(current, block);
        if (!next.equals(current)) {
            coordinator.setSystemPrompt(next);
            // updateAgent invalidates runtime cache so the coordinator picks up
            // the team instructions on the next chat turn.
            agentService.updateAgent(coordinator);
        }
    }

    private String buildCoordinatorTeamBlock(AgentTeamEntity team, List<MemberInput> members) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- MATECLAW_TEAM_COORDINATOR_START -->\n");
        sb.append("## Team Coordination\n\n");
        sb.append("You are the native HHAIOS coordinator for team: ").append(team.getName()).append(".\n");
        if (team.getDescription() != null && !team.getDescription().isBlank()) {
            sb.append("Team purpose: ").append(team.getDescription().trim()).append("\n");
        }
        sb.append("\nHard rule: you must orchestrate work through HHAIOS delegation tools. ")
                .append("Use delegateToAgent for one specialist, delegateParallel for independent work that should run concurrently, ")
                .append("and delegateAsync/taskOutput for long-running specialist work.\n");
        sb.append("Do not pretend that you personally performed a specialist's work before the delegated result returns. ")
                .append("Summarize, reconcile conflicts, and deliver the final decision/action plan to the user.\n\n");
        sb.append("Team members:\n");
        int i = 1;
        for (MemberInput member : members) {
            AgentEntity agent = agentMapper.selectById(member.agentId());
            if (agent == null) continue;
            sb.append(i++).append(". ").append(agent.getName());
            String role = member.roleLabel();
            if (role != null && !role.isBlank()) sb.append(" — ").append(role.trim());
            if ("acp".equalsIgnoreCase(agent.getAgentType()) && agent.getAcpEndpointName() != null) {
                sb.append(" (ACP: ").append(agent.getAcpEndpointName()).append(")");
            }
            sb.append("\n");
        }
        sb.append("\nDefault routing:\n")
                .append("- Product requirements, PRD, user experience, and acceptance criteria -> product manager member.\n")
                .append("- Code implementation, debugging, architecture, and repository changes -> engineering member.\n")
                .append("- Strategy, priority, business trade-off, and final arbitration -> CEO/strategy member.\n")
                .append("- For complex tasks, ask multiple members in parallel, then synthesize one coherent answer.\n");
        sb.append("<!-- MATECLAW_TEAM_COORDINATOR_END -->");
        return sb.toString();
    }

    private static String upsertTeamBlock(String current, String block) {
        String start = "<!-- MATECLAW_TEAM_COORDINATOR_START -->";
        String end = "<!-- MATECLAW_TEAM_COORDINATOR_END -->";
        int s = current.indexOf(start);
        int e = current.indexOf(end);
        if (s >= 0 && e >= s) {
            int afterEnd = e + end.length();
            return (current.substring(0, s).stripTrailing()
                    + "\n\n" + block + "\n\n"
                    + current.substring(afterEnd).stripLeading()).trim();
        }
        if (current == null || current.isBlank()) return block;
        return current.stripTrailing() + "\n\n" + block;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
