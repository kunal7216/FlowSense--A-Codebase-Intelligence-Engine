package com.flowsense.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

/**
 * Understands WHAT the user is asking before we query anything.
 *
 * PROBLEM: "What breaks if I change PaymentService?" is ambiguous.
 * We need to know:
 *   - Intent: DEPENDENCY_QUERY
 *   - Entities: ["PaymentService"]
 *
 * WITHOUT decomposition, we'd run every graph query for every question.
 * WITH decomposition, we run only the relevant graph traversal.
 *
 * INTERVIEW TALKING POINT:
 * "Query decomposition is the first step of my Graph RAG pipeline.
 * Instead of naively running all possible graph queries, I first
 * classify the intent — is this a dependency question? A call chain
 * question? A dead code question? Then I extract the relevant entities
 * (class/method names) and run ONLY the appropriate Neo4j traversal.
 * This keeps latency low and context focused."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryDecomposer {

    private final ChatClient chatClient;

    /**
     * Decompose a natural language question into structured intent + entities.
     * Uses a combination of pattern matching (fast) and LLM (accurate).
     */
    public DecomposedQuery decompose(String question, String projectId) {
        // First try fast pattern matching — no LLM call needed
        DecomposedQuery patternResult = tryPatternMatch(question);
        if (patternResult != null) {
            log.debug("Pattern match succeeded: intent={}", patternResult.getIntent());
            return patternResult;
        }

        // Fall back to LLM decomposition for complex questions
        log.debug("Falling back to LLM decomposition");
        return llmDecompose(question);
    }

    // ─────────────────────────────────────────────────────────
    // PATTERN MATCHING (fast path — no LLM call)
    // ─────────────────────────────────────────────────────────

    private static final List<IntentPattern> PATTERNS = List.of(

        // "What depends on X" / "What breaks if I change X"
        new IntentPattern(
            QueryIntent.DEPENDENCY_QUERY,
            Pattern.compile(
                "(?i)(what|which).*(depend|use|break|impact|affect).*?([A-Z][a-zA-Z]+)|" +
                "(?i)(depend|breaks?|impact).*(if|when).*?([A-Z][a-zA-Z]+)|" +
                "(?i)([A-Z][a-zA-Z]+).*(depend|dependen|impact)"
            )
        ),

        // "Trace X" / "What happens when X is called" / "call chain"
        new IntentPattern(
            QueryIntent.CALL_CHAIN_QUERY,
            Pattern.compile(
                "(?i)(trace|follow|track).*(call|execution|flow).*?([a-zA-Z]+)|" +
                "(?i)what happens when ([a-zA-Z]+).*called|" +
                "(?i)call.?chain.*?([a-zA-Z]+)|" +
                "(?i)execution.?flow.*?([a-zA-Z]+)"
            )
        ),

        // "Who calls X" / "What calls X"
        new IntentPattern(
            QueryIntent.CALLER_QUERY,
            Pattern.compile(
                "(?i)(who|what|which).*(call|invoke|use).*?([A-Z][a-zA-Z]+\\.?[a-zA-Z]*)|" +
                "(?i)callers? of ([A-Z][a-zA-Z]+)"
            )
        ),

        // "Dead code" / "unused classes"
        new IntentPattern(
            QueryIntent.DEAD_CODE_QUERY,
            Pattern.compile(
                "(?i)(dead.?code|unused|unreachable|never.?called|orphan)"
            )
        ),

        // "Database writes" / "DB operations"
        new IntentPattern(
            QueryIntent.DB_OPERATION_QUERY,
            Pattern.compile(
                "(?i)(database|db).*(write|save|insert|update|delete)|" +
                "(?i)(where|which).*(write|save|persist).*(database|db|repository)"
            )
        ),

        // "Circular dependencies" / "cycles"
        new IntentPattern(
            QueryIntent.CIRCULAR_DEP_QUERY,
            Pattern.compile(
                "(?i)(circular|cycle|cyclic).*(depend|reference)|" +
                "(?i)(depend).*(circular|cycle|cyclic)"
            )
        ),

        // "Complex methods" / "tech debt"
        new IntentPattern(
            QueryIntent.COMPLEXITY_QUERY,
            Pattern.compile(
                "(?i)(complex|complicated|messy|tech.?debt|refactor)|" +
                "(?i)high.?complexity"
            )
        )
    );

    private DecomposedQuery tryPatternMatch(String question) {
        for (IntentPattern ip : PATTERNS) {
            Matcher m = ip.pattern().matcher(question);
            if (m.find()) {
                // Extract entity names (capitalized words = class names)
                List<String> entities = extractEntities(question);
                return DecomposedQuery.builder()
                    .originalQuestion(question)
                    .intent(ip.intent())
                    .entities(entities)
                    .confidence(0.85)
                    .decomposedBy("PATTERN")
                    .build();
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────
    // LLM DECOMPOSITION (accurate path for complex questions)
    // ─────────────────────────────────────────────────────────

    private DecomposedQuery llmDecompose(String question) {
        String prompt = """
            Analyze this codebase question and respond ONLY with JSON. No explanation.
            
            Question: "%s"
            
            Respond with this exact JSON format:
            {
              "intent": "<one of: DEPENDENCY_QUERY, CALL_CHAIN_QUERY, CALLER_QUERY, DEAD_CODE_QUERY, DB_OPERATION_QUERY, CIRCULAR_DEP_QUERY, COMPLEXITY_QUERY, GENERAL_QUERY>",
              "entities": ["<class or method names mentioned>"],
              "isFollowUp": false
            }
            
            INTENT GUIDE:
            - DEPENDENCY_QUERY: asking what depends on something, or what breaks if changed
            - CALL_CHAIN_QUERY: tracing execution flow, what happens when X is called
            - CALLER_QUERY: who/what calls a specific method or class
            - DEAD_CODE_QUERY: unused classes or methods
            - DB_OPERATION_QUERY: database reads/writes
            - CIRCULAR_DEP_QUERY: circular dependencies
            - COMPLEXITY_QUERY: code complexity, tech debt
            - GENERAL_QUERY: anything else
            """.formatted(question);

        try {
            String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

            return parseDecomposedQuery(question, response);

        } catch (Exception e) {
            log.warn("LLM decomposition failed, using GENERAL fallback: {}", e.getMessage());
            return DecomposedQuery.builder()
                .originalQuestion(question)
                .intent(QueryIntent.GENERAL_QUERY)
                .entities(extractEntities(question))
                .confidence(0.5)
                .decomposedBy("FALLBACK")
                .build();
        }
    }

    private DecomposedQuery parseDecomposedQuery(String question, String llmResponse) {
        // Parse the JSON response from LLM
        // Simple extraction without full JSON parser for robustness
        QueryIntent intent = QueryIntent.GENERAL_QUERY;
        List<String> entities = new ArrayList<>();

        // Extract intent
        for (QueryIntent qi : QueryIntent.values()) {
            if (llmResponse.contains(qi.name())) {
                intent = qi;
                break;
            }
        }

        // Extract entities array
        Pattern entityPattern = Pattern.compile("\"entities\"\\s*:\\s*\\[([^\\]]+)\\]");
        Matcher entityMatcher = entityPattern.matcher(llmResponse);
        if (entityMatcher.find()) {
            String entityList = entityMatcher.group(1);
            Pattern namePattern = Pattern.compile("\"([^\"]+)\"");
            Matcher nameMatcher = namePattern.matcher(entityList);
            while (nameMatcher.find()) {
                entities.add(nameMatcher.group(1));
            }
        }

        // If LLM didn't find entities, fall back to pattern extraction
        if (entities.isEmpty()) {
            entities = extractEntities(question);
        }

        return DecomposedQuery.builder()
            .originalQuestion(question)
            .intent(intent)
            .entities(entities)
            .confidence(0.9)
            .decomposedBy("LLM")
            .build();
    }

    // ─────────────────────────────────────────────────────────
    // ENTITY EXTRACTION
    // ─────────────────────────────────────────────────────────

    /**
     * Extract Java class/method names from a question.
     * Looks for PascalCase (class names) and camelCase.method() patterns.
     */
    private List<String> extractEntities(String question) {
        List<String> entities = new ArrayList<>();

        // PascalCase = class names (e.g. PaymentService, OrderController)
        Pattern classPattern = Pattern.compile("\\b([A-Z][a-zA-Z]{2,})\\b");
        Matcher classMatcher = classPattern.matcher(question);
        while (classMatcher.find()) {
            String name = classMatcher.group(1);
            // Filter out common English words that happen to be capitalized
            if (!isCommonWord(name)) {
                entities.add(name);
            }
        }

        // ClassName.methodName() pattern
        Pattern methodPattern = Pattern.compile("\\b([A-Z][a-zA-Z]+)\\.([a-z][a-zA-Z]+)\\(\\)");
        Matcher methodMatcher = methodPattern.matcher(question);
        while (methodMatcher.find()) {
            entities.add(methodMatcher.group(1) + "." + methodMatcher.group(2));
        }

        return entities.stream().distinct().collect(java.util.stream.Collectors.toList());
    }

    private boolean isCommonWord(String word) {
        Set<String> common = Set.of(
            "What", "Which", "Where", "When", "Why", "How", "Who",
            "The", "This", "That", "These", "Those", "There",
            "If", "Can", "Could", "Would", "Should", "Will",
            "Show", "Find", "List", "Get", "Tell", "Give",
            "All", "Any", "Some", "Most", "Every",
            "Java", "Spring", "Class", "Method", "Code"
        );
        return common.contains(word);
    }

    // ─────────────────────────────────────────────────────────
    // TYPES
    // ─────────────────────────────────────────────────────────

    public enum QueryIntent {
        DEPENDENCY_QUERY,   // "What depends on X?"
        CALL_CHAIN_QUERY,   // "Trace X" / "What happens when X is called?"
        CALLER_QUERY,       // "Who calls X?"
        DEAD_CODE_QUERY,    // "What dead code exists?"
        DB_OPERATION_QUERY, // "Where do we write to the DB?"
        CIRCULAR_DEP_QUERY, // "Any circular dependencies?"
        COMPLEXITY_QUERY,   // "What's most complex?"
        GENERAL_QUERY       // Anything else
    }

    @lombok.Data
    @lombok.Builder
    public static class DecomposedQuery {
        private String originalQuestion;
        private QueryIntent intent;
        private List<String> entities;
        private double confidence;
        private String decomposedBy;   // "PATTERN" or "LLM"
    }

    private record IntentPattern(QueryIntent intent, Pattern pattern) {}
}
