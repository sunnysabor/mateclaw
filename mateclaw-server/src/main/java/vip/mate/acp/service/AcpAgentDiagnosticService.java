package vip.mate.acp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.acp.model.AcpEndpointEntity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Diagnostics for the small set of coding agents MateClaw manages first-class.
 *
 * <p>This deliberately does not execute user prompts. It only performs cheap,
 * bounded CLI probes so the settings UI can show whether Hermes / Codex /
 * OpenClaw are installed, whether an ACP entry point is available, and whether
 * the corresponding {@code mate_acp_endpoint} row is configured.
 */
@Slf4j
@Service
public class AcpAgentDiagnosticService {

    static final String CODEX_ACP_PACKAGE = "@agentclientprotocol/codex-acp";
    static final String LEGACY_CODEX_ACP_PACKAGE = "@zed-industries/codex-acp";

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final int OUTPUT_LIMIT_CHARS = 12_000;

    private final AcpEndpointService endpointService;
    private final CommandRunner commandRunner;

    @Autowired
    public AcpAgentDiagnosticService(AcpEndpointService endpointService) {
        this(endpointService, new ProcessCommandRunner());
    }

    AcpAgentDiagnosticService(AcpEndpointService endpointService, CommandRunner commandRunner) {
        this.endpointService = endpointService;
        this.commandRunner = commandRunner;
    }

    public List<AgentDiagnostic> diagnostics() {
        return List.of(
                diagnoseHermes(),
                diagnoseCodex(),
                diagnoseOpenClaw()
        );
    }

    private AgentDiagnostic diagnoseHermes() {
        AcpEndpointEntity endpoint = safeFind("hermes");
        List<String> endpointArgs = parseEndpointArgs(endpoint);
        CommandCheck cli = firstSuccessfulCommand(
                commandCandidates(endpoint, "hermes", true, true),
                List.of("--version"));
        CommandCheck acp = cli.installed()
                ? firstSuccessfulProbe(cli.command(), List.of(
                    List.of("acp", "--check"),
                    List.of("acp", "--help")
                ))
                : CommandCheck.missing(null, "Hermes CLI is not installed or not on PATH.");

        return build("hermes",
                "Hermes Agent",
                "hermes",
                "native_acp",
                "hermes",
                List.of("acp", "--accept-hooks"),
                endpoint,
                endpointArgs,
                cli,
                acp,
                nativeAcpMessage(acp, "Hermes exposes `hermes acp`."));
    }

    private AgentDiagnostic diagnoseCodex() {
        AcpEndpointEntity endpoint = safeFind("codex");
        List<String> endpointArgs = parseEndpointArgs(endpoint);
        CommandCheck cli = firstSuccessfulCommand(
                commandCandidates(endpoint, "codex", true, false),
                List.of("--version"));

        CommandCheck adapterRuntime = firstSuccessfulCommand(
                commandCandidates(endpoint, "npx", true, false),
                List.of("--version"));
        boolean configuredForAdapter = endpointArgs.stream()
                .anyMatch(AcpAgentDiagnosticService::isCodexAcpPackageArg);
        CommandCheck acp = cli.installed() && adapterRuntime.installed() && configuredForAdapter
                ? adapterRuntime.withMessage("Codex is exposed to ACP through " + CODEX_ACP_PACKAGE + ".")
                : CommandCheck.missing(adapterRuntime.command(), codexAdapterError(cli, adapterRuntime, configuredForAdapter));

        return build("codex",
                "OpenAI Codex CLI",
                "codex",
                "adapter_acp",
                "npx",
                List.of("-y", CODEX_ACP_PACKAGE),
                endpoint,
                endpointArgs,
                cli,
                acp,
                "Codex CLI does not need a native `codex acp` command here; HHAIOS uses the ACP adapter.");
    }

    private AgentDiagnostic diagnoseOpenClaw() {
        AcpEndpointEntity endpoint = safeFind("openclaw");
        List<String> endpointArgs = parseEndpointArgs(endpoint);
        CommandCheck cli = firstSuccessfulCommand(
                commandCandidates(endpoint, "openclaw", true, true),
                List.of("--version"));
        CommandCheck acp = cli.installed()
                ? firstSuccessfulProbe(cli.command(), List.of(List.of("acp", "--help")))
                : CommandCheck.missing(null, "OpenClaw CLI is not installed or not on PATH.");

        return build("openclaw",
                "OpenClaw",
                "openclaw",
                "native_acp",
                "openclaw",
                List.of("acp"),
                endpoint,
                endpointArgs,
                cli,
                acp,
                nativeAcpMessage(acp, "OpenClaw exposes `openclaw acp` as a Gateway-backed ACP bridge."));
    }

    private String codexAdapterError(CommandCheck cli,
                                     CommandCheck adapterRuntime,
                                     boolean configuredForAdapter) {
        if (!cli.installed()) return "Codex CLI is required by the ACP adapter.";
        if (!adapterRuntime.installed()) return "npx is required for the Codex ACP adapter.";
        if (!configuredForAdapter) return "Codex endpoint is not configured to use " + CODEX_ACP_PACKAGE + ".";
        return "Codex ACP adapter is not ready.";
    }

    private static boolean isCodexAcpPackageArg(String arg) {
        return arg != null
                && (arg.contains(CODEX_ACP_PACKAGE) || arg.contains(LEGACY_CODEX_ACP_PACKAGE));
    }

    private AgentDiagnostic build(String name,
                                  String displayName,
                                  String endpointName,
                                  String integrationMode,
                                  String recommendedCommand,
                                  List<String> recommendedArgs,
                                  AcpEndpointEntity endpoint,
                                  List<String> endpointArgs,
                                  CommandCheck cli,
                                  CommandCheck acp,
                                  String message) {
        return new AgentDiagnostic(
                name,
                displayName,
                endpointName,
                integrationMode,
                recommendedCommand,
                recommendedArgs,
                cli.installed(),
                cli.version(),
                cli.command(),
                acp.installed(),
                acp.installed() ? "OK" : "ERROR",
                acp.message(),
                endpoint != null,
                endpoint == null ? null : endpoint.getId(),
                endpoint != null && Boolean.TRUE.equals(endpoint.getEnabled()),
                endpoint == null ? null : endpoint.getCommand(),
                endpointArgs,
                endpoint == null ? null : endpoint.getLastStatus(),
                endpoint == null ? null : endpoint.getLastError(),
                message,
                suggestedActions(name, endpoint, cli, acp)
        );
    }

    private List<String> suggestedActions(String name,
                                          AcpEndpointEntity endpoint,
                                          CommandCheck cli,
                                          CommandCheck acp) {
        List<String> actions = new ArrayList<>();
        if (!cli.installed()) {
            actions.add("Install the CLI or set the endpoint command to the full binary path.");
        }
        if (!"codex".equals(name)
                && cli.installed()
                && endpoint != null
                && endpoint.getCommand() != null
                && cli.command() != null
                && !endpoint.getCommand().equals(cli.command())
                && looksLikePath(cli.command())) {
            actions.add("Use the detected command path for this endpoint.");
        }
        if (!acp.installed()) {
            actions.add("Fix the ACP entry point before enabling this endpoint.");
        }
        if (endpoint != null && !Boolean.TRUE.equals(endpoint.getEnabled())) {
            actions.add("Enable the endpoint after the connection test passes.");
        }
        return actions;
    }

    private String nativeAcpMessage(CommandCheck acp, String okMessage) {
        return acp.installed() ? okMessage : acp.message();
    }

    private AcpEndpointEntity safeFind(String name) {
        try {
            return endpointService.findByName(name);
        } catch (Exception e) {
            log.debug("ACP endpoint lookup failed for '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private List<String> parseEndpointArgs(AcpEndpointEntity endpoint) {
        if (endpoint == null) return List.of();
        try {
            return endpointService.parseArgs(endpoint);
        } catch (Exception e) {
            log.debug("Failed to parse ACP endpoint args for '{}': {}",
                    endpoint.getName(), e.getMessage());
            return List.of();
        }
    }

    private List<String> commandCandidates(AcpEndpointEntity endpoint,
                                           String binaryName,
                                           boolean includeNodeBins,
                                           boolean includeEndpointCommand) {
        Set<String> candidates = new LinkedHashSet<>();
        if (includeEndpointCommand
                && endpoint != null
                && endpoint.getCommand() != null
                && !endpoint.getCommand().isBlank()) {
            candidates.add(endpoint.getCommand().trim());
        }
        candidates.add(binaryName);

        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            if ("hermes".equals(binaryName)) {
                candidates.add(Path.of(home, ".local", "bin", "hermes").toString());
                candidates.add(Path.of(home, ".hermes", "bin", "hermes").toString());
            }
            if (includeNodeBins) {
                candidates.addAll(nodeBinCandidates(home, binaryName));
            }
            candidates.add(Path.of(home, ".bun", "bin", binaryName).toString());
            candidates.add(Path.of(home, ".npm-global", "bin", binaryName).toString());
        }
        candidates.add(Path.of("/opt/homebrew/bin", binaryName).toString());
        candidates.add(Path.of("/usr/local/bin", binaryName).toString());
        return candidates.stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    private List<String> nodeBinCandidates(String home, String binaryName) {
        Path nodeVersions = Path.of(home, ".nvm", "versions", "node");
        if (!Files.isDirectory(nodeVersions)) return List.of();
        List<String> out = new ArrayList<>();
        try (var stream = Files.list(nodeVersions)) {
            stream.filter(Files::isDirectory)
                    .map(path -> path.resolve("bin").resolve(binaryName))
                    .filter(Files::exists)
                    .map(Path::toString)
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            log.debug("Failed to inspect nvm node bins: {}", e.getMessage());
        }
        return out;
    }

    private CommandCheck firstSuccessfulCommand(List<String> commands, List<String> args) {
        CommandCheck firstFailure = null;
        for (String command : commands) {
            if (looksLikePath(command) && !Files.isExecutable(Path.of(command))) {
                continue;
            }
            ProbeResult result = commandRunner.run(commandLine(command, args), PROBE_TIMEOUT);
            if (result.exitCode() == 0) {
                return CommandCheck.installed(command, firstLine(result.output()), result.output());
            }
            if (firstFailure == null) {
                firstFailure = CommandCheck.missing(command, failureMessage(result));
            }
        }
        return firstFailure != null ? firstFailure
                : CommandCheck.missing(null, "Command not found.");
    }

    private CommandCheck firstSuccessfulProbe(String command, List<List<String>> argSets) {
        CommandCheck firstFailure = null;
        for (List<String> args : argSets) {
            ProbeResult result = commandRunner.run(commandLine(command, args), PROBE_TIMEOUT);
            if (result.exitCode() == 0) {
                return CommandCheck.installed(command, firstLine(result.output()), result.output());
            }
            if (firstFailure == null) {
                firstFailure = CommandCheck.missing(command, failureMessage(result));
            }
        }
        return firstFailure != null ? firstFailure
                : CommandCheck.missing(command, "ACP probe failed.");
    }

    private List<String> commandLine(String command, List<String> args) {
        List<String> line = new ArrayList<>();
        line.add(command);
        if (args != null) line.addAll(args);
        return line;
    }

    private String failureMessage(ProbeResult result) {
        if (result.timedOut()) return "Command timed out after " + PROBE_TIMEOUT.toSeconds() + "s.";
        if (result.error() != null && !result.error().isBlank()) return result.error();
        String output = firstLine(result.output());
        if (output != null && !output.isBlank()) return output;
        return "Command exited with code " + result.exitCode() + ".";
    }

    private String firstLine(String output) {
        if (output == null || output.isBlank()) return "";
        String normalized = output.replace("\r\n", "\n").replace('\r', '\n').trim();
        int idx = normalized.indexOf('\n');
        return idx >= 0 ? normalized.substring(0, idx).trim() : normalized;
    }

    private boolean looksLikePath(String command) {
        return command != null && (command.contains("/") || command.contains("\\"));
    }

    public record AgentDiagnostic(
            String name,
            String displayName,
            String endpointName,
            String integrationMode,
            String recommendedCommand,
            List<String> recommendedArgs,
            boolean installed,
            String version,
            String detectedCommand,
            boolean acpAvailable,
            String acpStatus,
            String acpMessage,
            boolean endpointConfigured,
            Long endpointId,
            boolean endpointEnabled,
            String endpointCommand,
            List<String> endpointArgs,
            String endpointLastStatus,
            String endpointLastError,
            String message,
            List<String> actions
    ) {}

    record ProbeResult(int exitCode, String output, String error, boolean timedOut) {}

    interface CommandRunner {
        ProbeResult run(List<String> command, Duration timeout);
    }

    private record CommandCheck(boolean installed, String command, String version, String message) {
        static CommandCheck installed(String command, String version, String output) {
            return new CommandCheck(true, command, version, output == null ? "" : output.trim());
        }

        static CommandCheck missing(String command, String message) {
            return new CommandCheck(false, command, null, message);
        }

        CommandCheck withMessage(String message) {
            return new CommandCheck(installed, command, version, message);
        }
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public ProbeResult run(List<String> command, Duration timeout) {
            Process process;
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                process = pb.start();
            } catch (IOException e) {
                return new ProbeResult(-1, "", e.getMessage(), false);
            }

            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> readOutput(process, output), "acp-agent-diagnostic-output");
            reader.setDaemon(true);
            reader.start();

            boolean finished;
            try {
                finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return new ProbeResult(-1, output.toString(), "Interrupted", true);
            }

            if (!finished) {
                process.destroyForcibly();
                joinQuietly(reader);
                return new ProbeResult(-1, output.toString(), null, true);
            }
            joinQuietly(reader);
            return new ProbeResult(process.exitValue(), output.toString(), null, false);
        }

        private static void readOutput(Process process, StringBuilder output) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                int ch;
                while ((ch = reader.read()) != -1) {
                    if (output.length() < OUTPUT_LIMIT_CHARS) {
                        output.append((char) ch);
                    }
                }
            } catch (IOException ignore) {
                // Process exited or stream closed.
            }
        }

        private static void joinQuietly(Thread thread) {
            try {
                thread.join(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
