package vip.mate.memory.fact.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.memory.MemoryProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM-based fact extractor — identifies facts that simple Markdown patterns
 * cannot see (free-form bullets, prose inside MEMORY.md, mixed Chinese/English
 * notes). Disabled by default and fault-isolated: if the model is unavailable or
 * returns bad JSON, the projection falls back to deterministic pattern facts.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmEntityExtractor implements EntityExtractor {

    private static final int MAX_CONTENT_CHARS = 12_000;
    private static final int MAX_FACTS = 40;

    private final MemoryProperties properties;
    private final ModelConfigService modelConfigService;
    /**
     * Lazy provider is intentional: AgentGraphBuilder owns MemoryManager, while
     * MemoryManager discovers FactMemoryProvider -> FactProjectionBuilder -> this
     * extractor. Injecting AgentGraphBuilder eagerly creates a startup cycle even
     * when LLM extraction is disabled. Resolve it only at extraction time.
     */
    private final ObjectProvider<AgentGraphBuilder> agentGraphBuilderProvider;
    private final ObjectMapper objectMapper;

    @Override
    public List<ExtractedFact> extract(Long agentId, String filename, String content) {
        if (!properties.getFact().isLlmExtractionEnabled()) {
            return List.of();
        }
        if (filename == null || content == null || content.isBlank()) {
            return List.of();
        }

        try {
            String systemPrompt = PromptLoader.loadPrompt("memory/fact-extract-system");
            String userPrompt = PromptLoader.loadPrompt("memory/fact-extract-user")
                    .replace("{filename}", filename)
                    .replace("{content}", clip(content));
            ChatModel chatModel = buildChatModel();
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
            )));
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                return List.of();
            }
            return parseFacts(filename, response.getResult().getOutput().getText());
        } catch (Exception e) {
            // Projection is a derived optimization. A bad LLM response or a
            // transient model failure must not block canonical memory writes.
            log.debug("[FactExtract] LLM extraction failed for agent={}, file={}: {}",
                    agentId, filename, e.getMessage());
            return List.of();
        }
    }

    private ChatModel buildChatModel() {
        ModelConfigEntity defaultModel = modelConfigService.getDefaultModel();
        AgentGraphBuilder builder = agentGraphBuilderProvider.getIfAvailable();
        if (builder == null) {
            throw new IllegalStateException("AgentGraphBuilder is not available for LLM fact extraction");
        }
        if (defaultModel == null) {
            throw new IllegalStateException("No default model configured for LLM fact extraction");
        }
        return builder.buildRuntimeChatModel(defaultModel);
    }

    private String clip(String content) {
        String trimmed = content.trim();
        return trimmed.length() <= MAX_CONTENT_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_CONTENT_CHARS) + "\n...[truncated]";
    }

    public List<ExtractedFact> parseFacts(String filename, String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFence(response));
        } catch (Exception e) {
            log.debug("[FactExtract] Failed to parse LLM fact JSON: {}", e.getMessage());
            return List.of();
        }

        JsonNode factsNode = root.isArray() ? root : root.path("facts");
        if (!factsNode.isArray() || factsNode.isEmpty()) {
            return List.of();
        }

        List<ExtractedFact> facts = new ArrayList<>();
        for (JsonNode node : factsNode) {
            if (facts.size() >= MAX_FACTS) {
                break;
            }
            String subject = clean(node.path("subject").asText(""));
            String predicate = clean(node.path("predicate").asText(""));
            String object = clean(node.path("object").asText(""));
            if (object.isBlank()) {
                object = clean(node.path("objectValue").asText(""));
            }
            if (subject.isBlank() || predicate.isBlank() || object.isBlank()) {
                continue;
            }

            String category = clean(node.path("category").asText(""));
            if (category.isBlank()) {
                category = inferCategory(filename);
            }
            double confidence = clamp(node.path("confidence").asDouble(0.7), 0.0, 1.0);
            if (confidence < 0.35) {
                continue;
            }
            String key = clean(node.path("key").asText(""));
            if (key.isBlank()) {
                key = subject + "_" + predicate + "_" + object;
            }
            String sourceRef = filename + "#llm_" + toSlug(key);
            if (facts.stream().noneMatch(f -> f.sourceRef().equals(sourceRef))) {
                facts.add(new ExtractedFact(sourceRef, category, subject, predicate, object,
                        confidence, 0.5, "llm"));
            }
        }
        return facts;
    }

    private String stripCodeFence(String text) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    private String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String inferCategory(String filename) {
        if (filename == null) return "general";
        if (filename.contains("user")) return "user_pref";
        if (filename.contains("project")) return "project";
        if (filename.contains("reference")) return "reference";
        if (filename.contains("feedback")) return "feedback";
        return "general";
    }

    private String toSlug(String s) {
        String slug = s.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "_")
                .replaceAll("^_|_$", "");
        if (slug.length() > 80) {
            slug = slug.substring(0, 80).replaceAll("_+$", "");
        }
        return slug.isBlank() ? "fact" : slug;
    }
}
