package vip.mate.acp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.acp.model.AcpEndpointEntity;
import vip.mate.acp.repository.AcpEndpointMapper;
import vip.mate.exception.MateClawException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;

class AcpEndpointServiceManagedAgentsTest {

    @Test
    @DisplayName("managed builtin command can be updated but unsupported builtin command remains locked")
    void managedBuiltinCommandCanBeUpdated() {
        AcpEndpointMapper mapper = mock(AcpEndpointMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AcpEndpointService service = new AcpEndpointService(mapper, new ObjectMapper(), publisher);

        AcpEndpointEntity hermes = endpoint(1L, "hermes", "hermes", true);
        when(mapper.selectById(1L)).thenReturn(hermes);
        AcpEndpointEntity patch = new AcpEndpointEntity();
        patch.setCommand("/Users/jerry/.local/bin/hermes");

        AcpEndpointEntity updated = service.update(1L, patch);

        assertEquals("/Users/jerry/.local/bin/hermes", updated.getCommand());
        verify(mapper).updateById(hermes);

        AcpEndpointEntity claude = endpoint(2L, "claude-code", "npx", true);
        when(mapper.selectById(2L)).thenReturn(claude);
        AcpEndpointEntity lockedPatch = new AcpEndpointEntity();
        lockedPatch.setCommand("/tmp/claude");

        assertThrows(MateClawException.class, () -> service.update(2L, lockedPatch));
    }

    @Test
    @DisplayName("workspace-scoped list returns managed builtins and current workspace custom endpoints only")
    void listIsWorkspaceScopedButManagedBuiltinsAreGlobal() {
        AcpEndpointMapper mapper = mock(AcpEndpointMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AcpEndpointService service = new AcpEndpointService(mapper, new ObjectMapper(), publisher);

        when(mapper.selectList(any())).thenReturn(List.of(
                endpoint(9100005L, "hermes", "hermes", true, 1L),
                endpoint(20L, "custom-ws2", "custom", false, 2L)
        ));

        List<AcpEndpointEntity> rows = service.list(2L);

        assertEquals(2, rows.size());
        verify(mapper).selectList(any());
    }

    @Test
    @DisplayName("custom endpoint mutations are rejected across workspaces")
    void customEndpointMutationRequiresOwningWorkspace() {
        AcpEndpointMapper mapper = mock(AcpEndpointMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AcpEndpointService service = new AcpEndpointService(mapper, new ObjectMapper(), publisher);

        AcpEndpointEntity custom = endpoint(10L, "team-coder", "coder", false, 2L);
        when(mapper.selectById(10L)).thenReturn(custom);

        AcpEndpointEntity patch = new AcpEndpointEntity();
        patch.setDescription("x");

        assertThrows(MateClawException.class, () -> service.get(10L, 1L));
        assertThrows(MateClawException.class, () -> service.update(10L, patch, 1L));
        assertThrows(MateClawException.class, () -> service.toggle(10L, true, 1L));
        assertThrows(MateClawException.class, () -> service.delete(10L, 1L));
    }

    @Test
    @DisplayName("wrapper tools for custom endpoints are hidden outside their workspace")
    void wrapperToolNamesHiddenOutsideWorkspace() {
        AcpEndpointMapper mapper = mock(AcpEndpointMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AcpEndpointService service = new AcpEndpointService(mapper, new ObjectMapper(), publisher);

        AcpEndpointEntity hermes = endpoint(9100005L, "hermes", "hermes", true, 1L);
        hermes.setEnabled(true);
        AcpEndpointEntity custom = endpoint(10L, "team coder", "coder", false, 2L);
        custom.setEnabled(true);
        when(mapper.selectList(any())).thenReturn(List.of(hermes, custom));

        var hiddenFromWs1 = service.wrapperToolNamesNotVisibleInWorkspace(1L);
        var hiddenFromWs2 = service.wrapperToolNamesNotVisibleInWorkspace(2L);

        assertTrue(hiddenFromWs1.contains("acp_team-coder_prompt"));
        assertFalse(hiddenFromWs1.contains("acp_hermes_prompt"));
        assertFalse(hiddenFromWs2.contains("acp_team-coder_prompt"));
    }

    private static AcpEndpointEntity endpoint(Long id, String name, String command, boolean builtin) {
        AcpEndpointEntity ep = new AcpEndpointEntity();
        ep.setId(id);
        ep.setName(name);
        ep.setCommand(command);
        ep.setBuiltin(builtin);
        return ep;
    }

    private static AcpEndpointEntity endpoint(Long id, String name, String command, boolean builtin, Long workspaceId) {
        AcpEndpointEntity ep = endpoint(id, name, command, builtin);
        ep.setWorkspaceId(workspaceId);
        return ep;
    }
}
