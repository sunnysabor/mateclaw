package vip.mate.acp.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.acp.model.AcpEndpointEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AcpAgentDiagnosticServiceTest {

    @Test
    @DisplayName("diagnostics classify Hermes/OpenClaw native ACP and Codex adapter ACP")
    void diagnosticsClassifyManagedAgents() {
        AcpEndpointService endpointService = mock(AcpEndpointService.class);
        when(endpointService.findByName("hermes")).thenReturn(endpoint(
                9100005L, "hermes", "hermes", "[\"acp\",\"--accept-hooks\"]", false));
        when(endpointService.findByName("codex")).thenReturn(endpoint(
                9100001L, "codex", "npx", "[\"-y\",\"@agentclientprotocol/codex-acp\"]", true));
        when(endpointService.findByName("openclaw")).thenReturn(endpoint(
                9100006L, "openclaw", "openclaw", "[\"acp\"]", false));
        when(endpointService.parseArgs(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            AcpEndpointEntity ep = invocation.getArgument(0);
            return switch (ep.getName()) {
                case "hermes" -> List.of("acp", "--accept-hooks");
                case "codex" -> List.of("-y", "@agentclientprotocol/codex-acp");
                case "openclaw" -> List.of("acp");
                default -> List.of();
            };
        });

        FakeRunner runner = new FakeRunner()
                .ok("hermes --version", "Hermes Agent v0.14.0")
                .ok("hermes acp --check", "Hermes ACP check OK")
                .ok("codex --version", "codex-cli 0.142.4")
                .ok("npx --version", "10.9.0")
                .ok("openclaw --version", "OpenClaw 2026.6.9")
                .ok("openclaw acp --help", "Run an ACP bridge backed by the Gateway");

        AcpAgentDiagnosticService service = new AcpAgentDiagnosticService(endpointService, runner);

        List<AcpAgentDiagnosticService.AgentDiagnostic> diagnostics = service.diagnostics();

        AcpAgentDiagnosticService.AgentDiagnostic hermes = byName(diagnostics, "hermes");
        assertEquals("native_acp", hermes.integrationMode());
        assertTrue(hermes.installed());
        assertTrue(hermes.acpAvailable());
        assertEquals("Hermes Agent v0.14.0", hermes.version());

        AcpAgentDiagnosticService.AgentDiagnostic codex = byName(diagnostics, "codex");
        assertEquals("adapter_acp", codex.integrationMode());
        assertTrue(codex.installed());
        assertTrue(codex.acpAvailable());
        assertEquals("codex", codex.detectedCommand());
        assertTrue(codex.acpMessage().contains("@agentclientprotocol/codex-acp"));
        assertEquals(List.of("-y", "@agentclientprotocol/codex-acp"), codex.recommendedArgs());

        AcpAgentDiagnosticService.AgentDiagnostic openclaw = byName(diagnostics, "openclaw");
        assertEquals("native_acp", openclaw.integrationMode());
        assertTrue(openclaw.acpAvailable());
        assertEquals(List.of("acp"), openclaw.endpointArgs());
    }

    @Test
    @DisplayName("Codex adapter detection does not treat npx as the Codex binary")
    void codexAdapterDetectionStillRequiresCodexCli() {
        AcpEndpointService endpointService = mock(AcpEndpointService.class);
        when(endpointService.findByName("hermes")).thenReturn(null);
        when(endpointService.findByName("openclaw")).thenReturn(null);
        when(endpointService.findByName("codex")).thenReturn(endpoint(
                9100001L, "codex", "npx", "[\"-y\",\"@agentclientprotocol/codex-acp\"]", false));
        when(endpointService.parseArgs(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("-y", "@agentclientprotocol/codex-acp"));

        FakeRunner runner = new FakeRunner()
                .ok("npx --version", "10.9.0");

        AcpAgentDiagnosticService service = new AcpAgentDiagnosticService(endpointService, runner);

        AcpAgentDiagnosticService.AgentDiagnostic codex = byName(service.diagnostics(), "codex");

        assertFalse(codex.installed(), "codex CLI itself should not be marked installed from npx");
        assertFalse(codex.acpAvailable(), "adapter ACP is not ready without the Codex CLI");
        assertTrue(codex.acpMessage().contains("Codex CLI is required"));
        assertEquals("npx", codex.endpointCommand());
    }

    @Test
    @DisplayName("Codex diagnostics still accept legacy Zed adapter args")
    void codexAdapterDetectionAcceptsLegacyPackageName() {
        AcpEndpointService endpointService = mock(AcpEndpointService.class);
        when(endpointService.findByName("hermes")).thenReturn(null);
        when(endpointService.findByName("openclaw")).thenReturn(null);
        when(endpointService.findByName("codex")).thenReturn(endpoint(
                9100001L, "codex", "npx", "[\"-y\",\"@zed-industries/codex-acp\"]", true));
        when(endpointService.parseArgs(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("-y", "@zed-industries/codex-acp"));

        FakeRunner runner = new FakeRunner()
                .ok("codex --version", "codex-cli 0.142.4")
                .ok("npx --version", "10.9.0");

        AcpAgentDiagnosticService service = new AcpAgentDiagnosticService(endpointService, runner);

        AcpAgentDiagnosticService.AgentDiagnostic codex = byName(service.diagnostics(), "codex");

        assertTrue(codex.acpAvailable());
        assertEquals(List.of("-y", "@agentclientprotocol/codex-acp"), codex.recommendedArgs());
    }

    private static AcpEndpointEntity endpoint(Long id,
                                              String name,
                                              String command,
                                              String argsJson,
                                              boolean enabled) {
        AcpEndpointEntity ep = new AcpEndpointEntity();
        ep.setId(id);
        ep.setName(name);
        ep.setCommand(command);
        ep.setArgsJson(argsJson);
        ep.setEnabled(enabled);
        return ep;
    }

    private static AcpAgentDiagnosticService.AgentDiagnostic byName(
            List<AcpAgentDiagnosticService.AgentDiagnostic> diagnostics,
            String name) {
        return diagnostics.stream()
                .filter(diagnostic -> name.equals(diagnostic.name()))
                .findFirst()
                .orElseThrow();
    }

    private static final class FakeRunner implements AcpAgentDiagnosticService.CommandRunner {
        private final Map<String, AcpAgentDiagnosticService.ProbeResult> responses = new HashMap<>();

        FakeRunner ok(String command, String output) {
            responses.put(command, new AcpAgentDiagnosticService.ProbeResult(0, output, null, false));
            return this;
        }

        @Override
        public AcpAgentDiagnosticService.ProbeResult run(List<String> command, Duration timeout) {
            String key = String.join(" ", command);
            return responses.getOrDefault(key,
                    new AcpAgentDiagnosticService.ProbeResult(-1, "", "not found", false));
        }
    }
}
