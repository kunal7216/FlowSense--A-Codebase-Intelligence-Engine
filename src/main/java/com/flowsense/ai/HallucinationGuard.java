package com.flowsense.ai;

import com.flowsense.graph.ClassNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Validates LLM answers to catch hallucinated class/method names.
 *
 * GROUND-FIRST, LANGUAGE-LAST ARCHITECTURE:
 * The LLM should only mention class/method names that exist in the
 * actual codebase. If it mentions a name that doesn't exist in Neo4j,
 * that's a hallucination — we flag it.
 *
 * INTERVIEW TALKING POINT:
 * "I call this 'ground-first, language-last' — the LLM's job is only
 * to format information into readable prose, not to generate facts.
 * All facts come from Neo4j. The HallucinationGuard verifies that
 * every class name in the answer exists in our graph. If not, we
 * add a warning so the engineer knows to verify that claim."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HallucinationGuard {

    private final ClassNodeRepository classRepository;

    /**
     * Validate an answer — check all class names against Neo4j.
     * Modifies the answer in-place to add validation metadata.
     */
    public void validate(GraphRAGEngine.RAGAnswer answer, String projectId) {
        List<String> warnings = new ArrayList<>();

        // Extract all PascalCase class names from the answer
        List<String> mentionedClasses = extractClassNames(answer.getAnswer());

        // Check each against Neo4j
        for (String className : mentionedClasses) {
            boolean existsInGraph = classRepository
                    .findByClassNameAndProjectId(className, projectId)
                    .isPresent();

            if (!existsInGraph) {
                log.warn("Potential hallucination: '{}' not found in graph", className);
                warnings.add("'" + className + "' could not be verified in the codebase graph");
            }
        }

        answer.setValidated(true);
        answer.setValidationWarnings(warnings);

        if (!warnings.isEmpty()) {
            log.warn("Hallucination guard flagged {} potential issues", warnings.size());
        } else {
            log.debug("Hallucination guard: answer fully verified");
        }
    }

    private List<String> extractClassNames(String text) {
        // Find PascalCase words (likely class names)
        Pattern pattern = Pattern.compile("\\b([A-Z][a-zA-Z]{2,})\\b");
        Matcher matcher = pattern.matcher(text);

        Set<String> names = new HashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            // Skip common English words
            if (!isCommonWord(name)) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    private boolean isCommonWord(String word) {
        return Set.of(
                "The", "This", "That", "When", "Where", "What", "Which",
                "Here", "There", "Then", "With", "From", "Into", "Over",
                "Java", "Spring", "Boot", "Class", "Method", "Service",
                "Note", "Also", "Additionally", "However", "Therefore",
                "STRUCTURAL", "SEMANTIC", "CONTEXT", "QUERY", "INTENT").contains(word);
    }
}
