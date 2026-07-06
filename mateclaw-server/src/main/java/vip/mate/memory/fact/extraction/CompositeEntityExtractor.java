package vip.mate.memory.fact.extraction;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Composite extractor — combines pattern + LLM extractors.
 * Deduplicates by sourceRef.
 *
 * @author MateClaw Team
 */
@Component
@RequiredArgsConstructor
public class CompositeEntityExtractor implements EntityExtractor {

    private final PatternEntityExtractor patternExtractor;
    private final LlmEntityExtractor llmExtractor;

    @Override
    public List<ExtractedFact> extract(Long agentId, String filename, String content) {
        List<ExtractedFact> results = new ArrayList<>(patternExtractor.extract(agentId, filename, content));

        // Add LLM-extracted facts that don't duplicate deterministic pattern
        // facts. Pattern refs are filename#key while LLM refs are
        // filename#llm_key, so also dedupe by semantic triple to avoid showing
        // the same fact twice when both extractors see it.
        List<ExtractedFact> llmFacts = llmExtractor.extract(agentId, filename, content);
        for (ExtractedFact f : llmFacts) {
            if (results.stream().noneMatch(r -> sameFact(r, f))) {
                results.add(f);
            }
        }

        return results;
    }

    private boolean sameFact(ExtractedFact a, ExtractedFact b) {
        return a.sourceRef().equals(b.sourceRef())
                || (norm(a.subject()).equals(norm(b.subject()))
                    && norm(a.predicate()).equals(norm(b.predicate()))
                    && norm(a.objectValue()).equals(norm(b.objectValue())));
    }

    private String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
