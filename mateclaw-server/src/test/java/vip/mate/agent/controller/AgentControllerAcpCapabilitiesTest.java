package vip.mate.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.vo.AgentCapabilitiesVO;
import vip.mate.common.result.R;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentControllerAcpCapabilitiesTest {

    @Test
    @DisplayName("ACP Agent capabilities do not resolve MateClaw model config")
    void acpCapabilitiesReturnRuntimeSnapshot() {
        AgentService agentService = mock(AgentService.class);
        vip.mate.llm.service.ModelConfigService modelConfigService =
                mock(vip.mate.llm.service.ModelConfigService.class);
        AgentController controller = new AgentController(
                agentService,
                mock(vip.mate.audit.service.AuditEventService.class),
                mock(vip.mate.auth.service.AuthService.class),
                mock(vip.mate.workspace.core.service.WorkspaceService.class),
                modelConfigService,
                mock(vip.mate.llm.service.ModelCapabilityService.class),
                mock(vip.mate.system.service.SystemSettingService.class),
                mock(vip.mate.agent.service.AgentGenerationService.class),
                new ObjectMapper());

        AgentEntity agent = new AgentEntity();
        agent.setId(42L);
        agent.setWorkspaceId(1L);
        agent.setAgentType("acp");
        agent.setAcpEndpointName("hermes");
        when(agentService.getAgent(42L)).thenReturn(agent);
        when(agentService.isAcpAgent(42L)).thenReturn(true);

        R<AgentCapabilitiesVO> response = controller.capabilities(42L, 1L);

        assertEquals(200, response.getCode());
        assertNotNull(response.getData());
        assertEquals("acp", response.getData().getProviderId());
        assertEquals("hermes", response.getData().getModelName());
        assertEquals(List.of("TEXT"), response.getData().getModalities());
        verifyNoInteractions(modelConfigService);
    }
}
