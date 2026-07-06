package vip.mate.acp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.acp.client.AcpStdioClient;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AcpDelegationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("delegation accumulates tolerant ACP message update variants")
    @SuppressWarnings("unchecked")
    void delegationAccumulatesMessageUpdateVariants() throws Exception {
        AcpDelegationService service = new AcpDelegationService(
                mapper, mock(AcpEndpointService.class), mock(AcpRuntimeSupport.class));
        AcpStdioClient client = mock(AcpStdioClient.class);
        StringBuilder buf = new StringBuilder();

        Method wireHandlers = AcpDelegationService.class.getDeclaredMethod(
                "wireHandlers", AcpStdioClient.class, StringBuilder.class,
                boolean.class, String.class);
        wireHandlers.setAccessible(true);
        wireHandlers.invoke(service, client, buf, true, "codex");

        ArgumentCaptor<Consumer<com.fasterxml.jackson.databind.JsonNode>> captor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(client).setNotificationHandler(captor.capture());

        captor.getValue().accept(mapper.readTree("""
                {"method":"session/update","params":{"update":{"type":"agent_message_delta","delta":{"text":"hello"}}}}
                """));
        captor.getValue().accept(mapper.readTree("""
                {"method":"session/update","params":{"update":{"kind":"content_delta","data":{"content":{"text":" world"}}}}}
                """));

        assertEquals("hello world", buf.toString());
    }
}
