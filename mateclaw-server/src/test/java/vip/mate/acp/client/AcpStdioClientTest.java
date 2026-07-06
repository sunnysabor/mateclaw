package vip.mate.acp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-090 Phase 7 — connection-test smoke for {@link AcpStdioClient}.
 *
 * <p>Runs a tiny shell-script "agent" that mimics the {@code initialize}
 * handshake: reads one JSON-RPC request, replies with a matching id and
 * the expected protocol version. Locks in:
 * <ul>
 *   <li>spawn → request → response → close all happen cleanly,</li>
 *   <li>protocolVersion is parsed from the result,</li>
 *   <li>the reader thread doesn't leak past close.</li>
 * </ul>
 *
 * <p>POSIX-only: relies on {@code sh} + executable bit. Windows agents
 * are exercised via the real CLI integration smoke (manual). The
 * client itself is OS-neutral; the script harness is what's POSIXy.
 */
@DisabledOnOs(OS.WINDOWS)
class AcpStdioClientTest {

    @Test
    @DisplayName("initialize handshake completes against a scripted agent")
    void initializeHandshake() throws Exception {
        Path script = writeScriptedAgent();
        try (AcpStdioClient client = AcpStdioClient.spawn(
                new ObjectMapper(), "sh", List.of(script.toString()),
                AcpStdioClient.emptyEnv(), null)) {
            JsonNode result = client.initialize(5_000);
            assertNotNull(result);
            assertEquals(AcpStdioClient.PROTOCOL_VERSION,
                    result.path("protocolVersion").asInt());
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    @DisplayName("spawn fails fast for a missing command")
    void spawnFailsFastForMissingCommand() {
        assertThrows(IOException.class, () ->
                AcpStdioClient.spawn(new ObjectMapper(),
                        "/definitely/does/not/exist/acp-test-bin",
                        List.of(), AcpStdioClient.emptyEnv(), null));
    }

    @Test
    @DisplayName("bare command resolves from common user bin directories")
    void bareCommandResolvesFromCommonUserBinDirectories() throws Exception {
        Path fakeHome = Files.createTempDirectory("acp-home-");
        String previousHome = System.getProperty("user.home");
        try {
            Path bin = fakeHome.resolve(".local").resolve("bin");
            Files.createDirectories(bin);
            String binaryName = "acp-test-user-bin-command";
            Path hermes = bin.resolve(binaryName);
            Files.writeString(hermes, "#!/bin/sh\n", StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(hermes, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignore) {
                // Filesystem doesn't support POSIX perms.
            }
            System.setProperty("user.home", fakeHome.toString());

            assertEquals(hermes.toString(), AcpStdioClient.resolveCommand(binaryName));
        } finally {
            System.setProperty("user.home", previousHome);
            Files.deleteIfExists(fakeHome.resolve(".local").resolve("bin").resolve("acp-test-user-bin-command"));
            Files.deleteIfExists(fakeHome.resolve(".local").resolve("bin"));
            Files.deleteIfExists(fakeHome.resolve(".local"));
            Files.deleteIfExists(fakeHome);
        }
    }


    @Test
    @DisplayName("bare command resolves from nvm node bins")
    void bareCommandResolvesFromNvmNodeBins() throws Exception {
        Path fakeHome = Files.createTempDirectory("acp-home-nvm-");
        String previousHome = System.getProperty("user.home");
        String binaryName = "acp-test-nvm-command";
        Path binary = fakeHome.resolve(".nvm").resolve("versions").resolve("node")
                .resolve("v22.22.3").resolve("bin").resolve(binaryName);
        try {
            Files.createDirectories(binary.getParent());
            Files.writeString(binary, "#!/bin/sh\n", StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(binary, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignore) {
                // Filesystem doesn't support POSIX perms.
            }
            System.setProperty("user.home", fakeHome.toString());

            assertEquals(binary.toString(), AcpStdioClient.resolveCommand(binaryName));
        } finally {
            System.setProperty("user.home", previousHome);
            Files.deleteIfExists(binary);
            Files.deleteIfExists(binary.getParent());
            Files.deleteIfExists(binary.getParent().getParent());
            Files.deleteIfExists(binary.getParent().getParent().getParent());
            Files.deleteIfExists(binary.getParent().getParent().getParent().getParent());
            Files.deleteIfExists(fakeHome.resolve(".nvm"));
            Files.deleteIfExists(fakeHome);
        }
    }

    @Test
    @DisplayName("stdout buffer limit fails the pending ACP request")
    void stdoutBufferLimitFailsPendingRequest() throws Exception {
        Path script = writeLargeOutputAgent();
        try (AcpStdioClient client = AcpStdioClient.spawn(
                new ObjectMapper(), "sh", List.of(script.toString()),
                AcpStdioClient.emptyEnv(), null)) {
            client.setStdoutBufferLimitBytes(80);
            IOException ex = assertThrows(IOException.class, () -> client.initialize(5_000));
            assertTrue(ex.getMessage().contains("Subprocess output exceeded buffer"));
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    @DisplayName("session/new recovers Hermes session id from stderr when stdout response is missing")
    void newSessionRecoversHermesSessionIdFromStderr() throws Exception {
        Path script = writeHermesNewSessionWithoutStdoutAgent();
        try (AcpStdioClient client = AcpStdioClient.spawn(
                new ObjectMapper(), "sh", List.of(script.toString()),
                AcpStdioClient.emptyEnv(), null)) {
            assertNotNull(client.initialize(5_000));

            JsonNode result = client.newSession("/tmp", 300);

            assertEquals("11111111-2222-3333-4444-555555555555",
                    result.path("sessionId").asText());
        } finally {
            Files.deleteIfExists(script);
        }
    }

    /**
     * Tiny shell-script agent: read one JSON-RPC line on stdin and
     * write a response with a hard-coded result. Just enough surface
     * to exercise the framing path.
     */
    private Path writeScriptedAgent() throws IOException {
        Path script = Files.createTempFile("acp-fake-agent-", ".sh");
        String body = "" +
                "#!/bin/sh\n" +
                "read line\n" +
                // Pull the id; assume integer id at this position.
                "id=$(printf '%s' \"$line\" | sed -n 's/.*\"id\":\\([0-9]\\+\\).*/\\1/p')\n" +
                "if [ -z \"$id\" ]; then id=1; fi\n" +
                "printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{}}}\\n' \"$id\"\n";
        Files.writeString(script, body, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignore) {
            // Filesystem doesn't support POSIX perms — sh ... still works.
        }
        return script;
    }

    private Path writeLargeOutputAgent() throws IOException {
        Path script = Files.createTempFile("acp-large-agent-", ".sh");
        String body = "" +
                "#!/bin/sh\n" +
                "read line\n" +
                "id=$(printf '%s' \"$line\" | sed -n 's/.*\"id\":\\([0-9]\\+\\).*/\\1/p')\n" +
                "if [ -z \"$id\" ]; then id=1; fi\n" +
                "printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{\"padding\":\"%s\"}}}\\n' \"$id\" \"$(printf '%0200d' 0)\"\n";
        Files.writeString(script, body, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignore) {
            // Filesystem doesn't support POSIX perms — sh ... still works.
        }
        return script;
    }

    private Path writeHermesNewSessionWithoutStdoutAgent() throws IOException {
        Path script = Files.createTempFile("acp-hermes-session-agent-", ".sh");
        String body = "" +
                "#!/bin/sh\n" +
                "read line\n" +
                "id=$(printf '%s' \"$line\" | sed -n 's/.*\"id\":\\([0-9]\\+\\).*/\\1/p')\n" +
                "if [ -z \"$id\" ]; then id=1; fi\n" +
                "printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{}}}\\n' \"$id\"\n" +
                "read line\n" +
                "printf '2026-07-02 13:40:41 [INFO] acp_adapter.session: Created ACP session 11111111-2222-3333-4444-555555555555 (cwd=/tmp)\\n' >&2\n" +
                "sleep 1\n";
        Files.writeString(script, body, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignore) {
            // Filesystem doesn't support POSIX perms — sh ... still works.
        }
        return script;
    }
}
