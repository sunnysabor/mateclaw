package vip.mate.acp.service;

import com.fasterxml.jackson.databind.JsonNode;
import vip.mate.agent.AgentService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts ACP {@code session/update} notifications into MateClaw's existing
 * stream events. The ACP ecosystem is still uneven about field names, so this
 * translator is intentionally tolerant: it recognizes the common
 * snake_case/kebab-case variants and falls back to compact JSON strings when
 * a structured value is not obvious.
 */
final class AcpStreamEventTranslator {

    private AcpStreamEventTranslator() {}

    static AgentService.StreamDelta toolDelta(JsonNode update, String parseMode) {
        String type = updateType(update);
        if (type.isBlank()) return null;

        boolean start = containsAny(type, "tool_call_start", "tool-call-start",
                "tool_call_started", "tool-call-started", "tool_call_begin",
                "tool-call-begin", "tool_call", "tool-call");
        boolean complete = containsAny(type, "tool_call_end", "tool-call-end",
                "tool_call_completed", "tool-call-completed", "tool_call_finish",
                "tool-call-finish", "tool_result", "tool-result");
        if (!start && !complete) return null;

        String toolName = firstText(update,
                "toolName", "tool_name", "name", "title", "callTitle", "call_title");
        String toolCallId = firstText(update,
                "toolCallId", "tool_call_id", "callId", "call_id", "id");
        String arguments = shouldShowArguments(parseMode)
                ? compact(first(update, "arguments", "args", "input", "callDetail", "call_detail", "detail"))
                : "";
        String result = shouldShowResult(parseMode)
                ? compact(first(update, "result", "output", "updateDetail", "update_detail", "content", "detail"))
                : "";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolCallId", toolCallId);
        data.put("toolName", !toolName.isBlank() ? toolName : "ACP tool");
        data.put("arguments", arguments);
        data.put("source", "acp");
        if (complete) {
            data.put("result", result);
            data.put("success", !looksFailed(update));
            return AgentService.StreamDelta.event("tool_call_completed", data);
        }
        return AgentService.StreamDelta.event("tool_call_started", data);
    }

    static String updateType(JsonNode update) {
        return firstText(update, "sessionUpdate", "type", "kind", "updateType", "update_type");
    }

    static String messageText(JsonNode update) {
        String type = updateType(update);
        if (!isMessageUpdateType(type)) return "";
        JsonNode value = first(update,
                "content", "delta", "text", "message", "chunk",
                "contentDelta", "content_delta", "messageDelta", "message_delta");
        String text = extractText(value);
        if (!text.isBlank()) return text;

        JsonNode agentMessage = first(update,
                "agentMessage", "agent_message", "assistantMessage", "assistant_message");
        text = extractText(agentMessage);
        if (!text.isBlank()) return text;

        JsonNode data = update.get("data");
        return extractText(data);
    }

    static String extractText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return "";
        if (content.isTextual()) return content.asText("");
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) sb.append(extractText(item));
            return sb.toString();
        }
        JsonNode text = content.get("text");
        if (text != null && text.isTextual()) return text.asText("");
        JsonNode resource = content.get("resource");
        if (resource != null) {
            JsonNode rt = resource.get("text");
            if (rt != null && rt.isTextual()) return rt.asText("");
        }
        JsonNode nestedContent = content.get("content");
        if (nestedContent != null) return extractText(nestedContent);
        JsonNode delta = content.get("delta");
        if (delta != null) return extractText(delta);
        return "";
    }

    private static boolean isMessageUpdateType(String type) {
        if (type == null || type.isBlank()) return false;
        String normalized = type.toLowerCase(java.util.Locale.ROOT)
                .replace('-', '_');
        return normalized.equals("agent_message_chunk")
                || normalized.equals("agent_message_delta")
                || normalized.equals("agent_message")
                || normalized.equals("assistant_message_chunk")
                || normalized.equals("assistant_message_delta")
                || normalized.equals("assistant_message")
                || normalized.equals("message_chunk")
                || normalized.equals("message_delta")
                || normalized.equals("content_chunk")
                || normalized.equals("content_delta");
    }

    private static boolean shouldShowArguments(String parseMode) {
        return "call_detail".equalsIgnoreCase(parseMode)
                || "update_detail".equalsIgnoreCase(parseMode);
    }

    private static boolean shouldShowResult(String parseMode) {
        return "update_detail".equalsIgnoreCase(parseMode);
    }

    private static boolean looksFailed(JsonNode update) {
        String status = firstText(update, "status", "state", "outcome");
        return "failed".equalsIgnoreCase(status)
                || "error".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status);
    }

    private static boolean containsAny(String value, String... needles) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle)) return true;
        }
        return false;
    }

    private static JsonNode first(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        JsonNode call = node.get("call");
        if (call != null && !call.isNull()) {
            JsonNode nested = first(call, names);
            if (nested != null) return nested;
        }
        JsonNode toolCall = node.get("toolCall");
        if (toolCall != null && !toolCall.isNull()) {
            JsonNode nested = first(toolCall, names);
            if (nested != null) return nested;
        }
        JsonNode toolCallSnake = node.get("tool_call");
        if (toolCallSnake != null && !toolCallSnake.isNull()) {
            JsonNode nested = first(toolCallSnake, names);
            if (nested != null) return nested;
        }
        return null;
    }

    private static String firstText(JsonNode node, String... names) {
        JsonNode value = first(node, names);
        if (value == null) return "";
        if (value.isTextual()) return value.asText("");
        if (value.isNumber() || value.isBoolean()) return value.asText("");
        return compact(value);
    }

    private static String compact(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asText("");
        return node.toString();
    }
}
