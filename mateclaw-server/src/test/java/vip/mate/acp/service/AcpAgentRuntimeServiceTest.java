package vip.mate.acp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.acp.client.AcpStdioClient;
import vip.mate.acp.model.AcpEndpointEntity;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.conversation.ConversationService;

import java.lang.reflect.Method;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcpAgentRuntimeServiceTest {

    @Test
    @DisplayName("ACP Agent validation normalizes endpoint and disables MateClaw capabilities")
    void validateAcpAgentNormalizesEndpointAndDisablesLocalCapabilities() {
        AcpEndpointService endpointService = mock(AcpEndpointService.class);
        AcpRuntimeSupport runtimeSupport = mock(AcpRuntimeSupport.class);
        ConversationService conversationService = mock(ConversationService.class);
        AcpAgentRuntimeService service = new AcpAgentRuntimeService(
                new ObjectMapper(), endpointService, runtimeSupport, conversationService);

        AcpEndpointEntity codex = new AcpEndpointEntity();
        codex.setName("codex");
        codex.setEnabled(true);
        when(endpointService.findByName("codex")).thenReturn(codex);

        AgentEntity agent = new AgentEntity();
        agent.setAgentType("acp");
        agent.setAcpEndpointName(" Codex ");
        agent.setSkillsDisabled(false);
        agent.setToolsDisabled(false);
        agent.setWikiDisabled(false);

        service.validateAcpAgent(agent);

        assertEquals("acp", agent.getAgentType());
        assertEquals("codex", agent.getAcpEndpointName());
        assertTrue(agent.getSkillsDisabled());
        assertTrue(agent.getToolsDisabled());
        assertTrue(agent.getWikiDisabled());
    }

    @Test
    @DisplayName("ACP identity card is kept during validation")
    void validateAcpAgentPreservesIdentityCard() {
        AcpEndpointService endpointService = mock(AcpEndpointService.class);
        AcpAgentRuntimeService service = new AcpAgentRuntimeService(
                new ObjectMapper(), endpointService,
                mock(AcpRuntimeSupport.class), mock(ConversationService.class));

        AcpEndpointEntity codex = new AcpEndpointEntity();
        codex.setName("codex");
        codex.setEnabled(true);
        when(endpointService.findByName("codex")).thenReturn(codex);

        AgentEntity agent = new AgentEntity();
        agent.setAgentType("acp");
        agent.setAcpEndpointName("codex");
        agent.setSystemPrompt("## Backstory\n这是一张很长的员工身份卡");

        service.validateAcpAgent(agent);

        assertEquals("## Backstory\n这是一张很长的员工身份卡", agent.getSystemPrompt());
    }

    @Test
    @DisplayName("ACP runtime wraps local identity card as a silent instruction prompt")
    void buildIdentityPromptWrapsLocalIdentityCard() throws Exception {
        AcpAgentRuntimeService service = new AcpAgentRuntimeService(
                new ObjectMapper(), mock(AcpEndpointService.class),
                mock(AcpRuntimeSupport.class), mock(ConversationService.class));
        AgentEntity agent = new AgentEntity();
        agent.setName("Codex 员工");
        agent.setSystemPrompt("## Role\n代码助手\n\n## Backstory\n严格遵守项目身份卡");

        Method method = AcpAgentRuntimeService.class.getDeclaredMethod(
                "buildIdentityPrompt", AgentEntity.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(service, agent);

        assertNotNull(prompt);
        assertTrue(prompt.contains("System note from HHAIOS"));
        assertTrue(prompt.contains("Employee: Codex 员工"));
        assertTrue(prompt.contains("## Backstory\n严格遵守项目身份卡"));
    }

    @Test
    @DisplayName("ACP Agent validation rejects unsupported managed endpoint")
    void validateAcpAgentRejectsUnsupportedEndpoint() {
        AcpAgentRuntimeService service = new AcpAgentRuntimeService(
                new ObjectMapper(), mock(AcpEndpointService.class),
                mock(AcpRuntimeSupport.class), mock(ConversationService.class));

        AgentEntity agent = new AgentEntity();
        agent.setAgentType("acp");
        agent.setAcpEndpointName("claude");

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.validateAcpAgent(agent));
        assertTrue(ex.getMessage().contains("Hermes"));
    }

    @Test
    @DisplayName("first-class ACP Agent declines permission requests when endpoint is untrusted")
    @SuppressWarnings("unchecked")
    void firstClassAcpAgentHonorsUntrustedPermissionPolicy() throws Exception {
        AcpAgentRuntimeService service = new AcpAgentRuntimeService(
                new ObjectMapper(), mock(AcpEndpointService.class),
                mock(AcpRuntimeSupport.class), mock(ConversationService.class));
        AcpStdioClient client = mock(AcpStdioClient.class);
        AcpEndpointEntity endpoint = new AcpEndpointEntity();
        endpoint.setTrusted(false);

        Method wireHandlers = AcpAgentRuntimeService.class.getDeclaredMethod(
                "wireHandlers", AcpStdioClient.class, String.class, AcpEndpointEntity.class);
        wireHandlers.setAccessible(true);
        wireHandlers.invoke(service, client, "codex", endpoint);

        ArgumentCaptor<Function<com.fasterxml.jackson.databind.JsonNode, com.fasterxml.jackson.databind.JsonNode>> captor =
                ArgumentCaptor.forClass(Function.class);
        verify(client).setRequestHandler(captor.capture());

        var request = new ObjectMapper().readTree("""
                {"method":"session/request_permission","params":{"options":[{"optionId":"allow"}]}}
                """);
        var result = captor.getValue().apply(request);

        assertEquals("cancelled", result.path("outcome").path("outcome").asText());
    }
}
