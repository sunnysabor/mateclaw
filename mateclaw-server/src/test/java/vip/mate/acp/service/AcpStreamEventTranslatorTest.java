package vip.mate.acp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AcpStreamEventTranslatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("call_title emits a tool start without arguments")
    void callTitleEmitsToolStartWithoutArguments() throws Exception {
        var update = mapper.readTree("""
                {"sessionUpdate":"tool_call_started","toolName":"edit_file","arguments":{"path":"secret.txt"}}
                """);

        var delta = AcpStreamEventTranslator.toolDelta(update, "call_title");

        assertNotNull(delta);
        assertEquals("tool_call_started", delta.eventType());
        assertEquals("edit_file", delta.eventData().get("toolName"));
        assertEquals("", delta.eventData().get("arguments"));
    }

    @Test
    @DisplayName("call_detail includes upstream tool arguments")
    void callDetailIncludesArguments() throws Exception {
        var update = mapper.readTree("""
                {"type":"tool-call-started","name":"shell","args":{"cmd":"pwd"}}
                """);

        var delta = AcpStreamEventTranslator.toolDelta(update, "call_detail");

        assertNotNull(delta);
        assertEquals("tool_call_started", delta.eventType());
        assertEquals("{\"cmd\":\"pwd\"}", delta.eventData().get("arguments"));
    }

    @Test
    @DisplayName("update_detail includes upstream tool result")
    void updateDetailIncludesResult() throws Exception {
        var update = mapper.readTree("""
                {"type":"tool_result","name":"shell","result":"ok","status":"completed"}
                """);

        var delta = AcpStreamEventTranslator.toolDelta(update, "update_detail");

        assertNotNull(delta);
        assertEquals("tool_call_completed", delta.eventType());
        assertEquals("ok", delta.eventData().get("result"));
        assertEquals(true, delta.eventData().get("success"));
    }

    @Test
    @DisplayName("agent message chunk text is extracted from content blocks")
    void agentMessageChunkExtractsContentBlocks() throws Exception {
        var update = mapper.readTree("""
                {"sessionUpdate":"agent-message-chunk","content":[{"type":"text","text":"hello"},{"text":" world"}]}
                """);

        assertEquals("hello world", AcpStreamEventTranslator.messageText(update));
    }

    @Test
    @DisplayName("agent message delta text is extracted from delta field")
    void agentMessageDeltaExtractsDeltaField() throws Exception {
        var update = mapper.readTree("""
                {"type":"agent_message_delta","delta":{"text":"partial"}}
                """);

        assertEquals("partial", AcpStreamEventTranslator.messageText(update));
    }

    @Test
    @DisplayName("content_delta text is extracted from nested data content")
    void contentDeltaExtractsNestedDataContent() throws Exception {
        var update = mapper.readTree("""
                {"kind":"content_delta","data":{"content":{"text":"nested"}}}
                """);

        assertEquals("nested", AcpStreamEventTranslator.messageText(update));
    }
}
