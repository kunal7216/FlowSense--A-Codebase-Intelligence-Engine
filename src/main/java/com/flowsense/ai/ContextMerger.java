package com.flowsense.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Merges graph traversal context + semantic search context
 * into a single, well-structured LLM prompt context.
 *
 * INTERVIEW TALKING POINT:
 * "The context merger solves a real problem — graph context and
 * semantic context have different formats and importance levels.
 * For dependency queries, graph context is primary. For 'find me
 * code that does X' queries, semantic context is primary. The merger
 * weights them appropriately based on the detected intent."
 */
@Slf4j
@Service
public class ContextMerger {

    private static final int MAX_CONTEXT_CHARS = 6000; // Stay within Ollama context window

    public String merge(String graphContext,
            String semanticContext,
            QueryDecomposer.DecomposedQuery decomposed) {

        StringBuilder merged = new StringBuilder();

        // Weight context based on intent
        boolean graphPrimary = isGraphPrimaryIntent(decomposed.getIntent());

        if (graphPrimary) {
            // Graph context first — it's the ground truth for structural questions
            appendWithLimit(merged, "STRUCTURAL CONTEXT (from knowledge graph):\n" + graphContext, 3500);
            appendWithLimit(merged, "\nSEMANTIC CONTEXT (related code):\n" + semanticContext, 2000);
        } else {
            // Semantic context first — for "find code that does X" questions
            appendWithLimit(merged, "RELEVANT CODE (semantic search):\n" + semanticContext, 3500);
            appendWithLimit(merged, "\nSTRUCTURAL CONTEXT:\n" + graphContext, 2000);
        }

        // Always add query metadata at the end
        merged.append("\n\nQUERY INTENT: ").append(decomposed.getIntent().name());
        merged.append("\nKEY ENTITIES: ").append(decomposed.getEntities());

        String result = merged.toString();
        log.debug("Merged context: {} chars (graphPrimary={})", result.length(), graphPrimary);
        return result;
    }

    private boolean isGraphPrimaryIntent(QueryDecomposer.QueryIntent intent) {
        return switch (intent) {
            case DEPENDENCY_QUERY,
                    CALL_CHAIN_QUERY,
                    CALLER_QUERY,
                    CIRCULAR_DEP_QUERY,
                    DEAD_CODE_QUERY ->
                true;
            default -> false;
        };
    }

    private void appendWithLimit(StringBuilder sb, String content, int maxChars) {
        if (content == null || content.isBlank())
            return;
        if (sb.length() + content.length() > MAX_CONTEXT_CHARS) {
            // Truncate to fit
            int remaining = MAX_CONTEXT_CHARS - sb.length();
            if (remaining > 100) {
                sb.append(content, 0, remaining).append("\n[...truncated for context limit]");
            }
        } else {
            sb.append(content);
        }
    }
}
