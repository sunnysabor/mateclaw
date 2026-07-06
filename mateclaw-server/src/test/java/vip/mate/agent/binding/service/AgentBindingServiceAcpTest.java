package vip.mate.agent.binding.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.binding.repository.AgentProviderPreferenceMapper;
import vip.mate.agent.binding.repository.AgentSkillBindingMapper;
import vip.mate.agent.binding.repository.AgentToolBindingMapper;
import vip.mate.agent.binding.repository.AgentWikiKbBindingMapper;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;
import vip.mate.skill.acp.AcpSkillBridge;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.tool.service.AvailableToolService;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AgentBindingServiceAcpTest {

    @Test
    @DisplayName("ACP Agents cannot bind MateClaw skills, tools, or KBs")
    void acpAgentsRejectLocalCapabilityBindings() {
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentEntity agent = new AgentEntity();
        agent.setId(10L);
        agent.setAgentType("acp");
        when(agentMapper.selectById(10L)).thenReturn(agent);

        AgentBindingService service = new AgentBindingService(
                mock(AgentSkillBindingMapper.class),
                mock(AgentToolBindingMapper.class),
                mock(AgentProviderPreferenceMapper.class),
                mock(AgentWikiKbBindingMapper.class),
                mock(WikiKnowledgeBaseMapper.class),
                mock(SkillRuntimeService.class),
                mock(AvailableToolService.class),
                agentMapper,
                mock(SkillMapper.class),
                mock(AcpSkillBridge.class));

        assertThrows(MateClawException.class, () -> service.setSkillBindings(10L, List.of(1L)));
        assertThrows(MateClawException.class, () -> service.setToolBindings(10L, List.of("web_search")));
        assertThrows(MateClawException.class, () -> service.setKbBindings(10L, List.of(2L)));
        assertThrows(MateClawException.class, () -> service.setProviderModelPreferences(10L,
                List.of(new vip.mate.llm.routing.ProviderModelRef("openai", null))));
    }
}
