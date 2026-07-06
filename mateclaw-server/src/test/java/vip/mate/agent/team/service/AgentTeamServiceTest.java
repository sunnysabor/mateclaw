package vip.mate.agent.team.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.team.dto.AgentTeamDtos.CreateTeamRequest;
import vip.mate.agent.team.dto.AgentTeamDtos.MemberInput;
import vip.mate.exception.MateClawException;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_team_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "spring.profiles.active=test"
})
class AgentTeamServiceTest {

    private static final AtomicLong WS_SEQ = new AtomicLong(80_000L);

    @Autowired private AgentService agentService;
    @Autowired private AgentTeamService teamService;
    @Autowired private JdbcTemplate jdbc;

    private long workspaceId;

    @BeforeEach
    void setUp() {
        workspaceId = WS_SEQ.getAndIncrement();
        jdbc.update("UPDATE mate_acp_endpoint SET enabled = TRUE WHERE name IN ('hermes','codex','openclaw')");
    }

    private AgentEntity newAgent(String name, String type) {
        AgentEntity a = new AgentEntity();
        a.setName(name);
        a.setDescription(name + " desc");
        a.setAgentType(type);
        a.setAcpEndpointName("acp".equals(type) ? name.toLowerCase() : null);
        a.setSystemPrompt("## Role\n" + name);
        a.setMaxIterations(10);
        a.setWorkspaceId(workspaceId);
        return agentService.createAgent(a);
    }

    @Test
    @DisplayName("团队必须使用原生 MateClaw 员工作为协调官，ACP 只能当成员")
    void rejectsAcpCoordinator() {
        AgentEntity acp = newAgent("codex", "acp");

        MateClawException ex = assertThrows(MateClawException.class, () -> teamService.create(workspaceId,
                new CreateTeamRequest("创业团队", "desc", acp.getId(),
                        List.of(new MemberInput(acp.getId(), "开发工程师")), true)));

        assertEquals(400, ex.getCode());
        assertEquals("err.agent_team.coordinator_must_be_native", ex.getMsgKey());
    }

    @Test
    @DisplayName("创建团队会保存成员，并把团队编排强提示写入协调官身份卡")
    void createsTeamAndInjectsCoordinatorPrompt() {
        AgentEntity coordinator = newAgent("团队协调官", "plan_execute");
        AgentEntity codex = newAgent("codex", "acp");
        AgentEntity openclaw = newAgent("openclaw", "acp");

        var team = teamService.create(workspaceId,
                new CreateTeamRequest("创业团队", "CEO + 产品 + 开发", coordinator.getId(),
                        List.of(
                                new MemberInput(codex.getId(), "开发工程师"),
                                new MemberInput(openclaw.getId(), "产品经理")
                        ), true));

        assertNotNull(team.id());
        assertEquals(coordinator.getId(), team.coordinatorAgentId());
        assertEquals(3, team.members().size(), "协调官应自动加入成员列表");
        AgentEntity reloaded = agentService.getAgent(coordinator.getId());
        assertTrue(reloaded.getSystemPrompt().contains("MATECLAW_TEAM_COORDINATOR_START"));
        assertTrue(reloaded.getSystemPrompt().contains("delegateParallel"));
        assertTrue(reloaded.getSystemPrompt().contains("codex"));
        assertTrue(reloaded.getSystemPrompt().contains("openclaw"));
    }
}
