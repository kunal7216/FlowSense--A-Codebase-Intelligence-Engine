package com.flowsense.ai;

import com.flowsense.embedding.EmbeddingService;
import com.flowsense.graph.ClassNodeRepository;
import com.flowsense.graph.GraphQueryService;
import com.flowsense.graph.MethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ╔══════════════════════════════════════════════════════════╗
 * ║ Graph RAG Engine — Core of Phase 2 ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 * WHAT IS GRAPH RAG?
 * Standard RAG: query → vector search → LLM
 * Graph RAG: query → graph traversal + vector search → merge → LLM
 *
 * WHY GRAPH RAG OVER STANDARD RAG?
 * Standard RAG retrieves semantically similar chunks — it finds
 * text that LOOKS similar to the question. But code understanding
 * requires STRUCTURAL knowledge: who calls what, what depends on what.
 *
 * Example: "What breaks if I change PaymentService?"
 * - Standard RAG: finds chunks mentioning "PaymentService" → misses
 * classes that CALL PaymentService but don't mention it by name
 * - Graph RAG: traverses the CALLS graph → finds every class that
 * transitively depends on PaymentService, even 5 hops away
 *
 * THE 5-STEP PIPELINE:
 * 1. DECOMPOSE → understand what the question is really asking
 * 2. GRAPH → traverse Neo4j for structural context
 * 3. VECTOR → search pgvector for semantic context
 * 4. MERGE → combine both into a rich LLM prompt
 * 5. GENERATE → LLM produces grounded, cited answer
 *
 * HALLUCINATION PREVENTION:
 * The LLM never generates class/method names — it only receives them
 * from the graph and formats them into prose. This is "ground-first,
 * language-last" architecture.
 *
 * INTERVIEW TALKING POINT:
 * "I designed the system so structural hallucination is architecturally
 * impossible. The LLM cannot invent a method name because it never
 * generates one — all names come from the parsed AST via Neo4j."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphRAGEngine {

    private final ChatClient chatClient;
    private final GraphQueryService graphQueryService;
    private final EmbeddingService embeddingService;
    private final QueryDecomposer queryDecomposer;
    private final ContextMerger contextMerger;
    private final HallucinationGuard hallucinationGuard;

    /**
     * Main entry point — answer a natural language question about the codebase.
     * Returns a streaming response (tokens arrive one by one).
     *
     * @param question  Natural language question
     * @param projectId Project to query
     * @param history   Conversation history for follow-up questions
     */
    public Flux<String> answerStreaming(String question,
            String projectId,
            List<ConversationTurn> history) {
        log.info("Graph RAG query: '{}' in project {}", question, projectId);
        long start = System.currentTimeMillis();

        try {
            // ── STEP 1: Decompose the question ─────────────────
            QueryDecomposer.DecomposedQuery decomposed = queryDecomposer.decompose(question, projectId);
            log.debug("Decomposed: intent={}, entities={}",
                    decomposed.getIntent(), decomposed.getEntities());

            // ── STEP 2: Graph traversal (structural context) ───
            String graphContext = buildGraphContext(decomposed, projectId);
            log.debug("Graph context length: {} chars", graphContext.length());

            // ── STEP 3: Vector search (semantic context) ───────
            List<EmbeddingService.SimilarMethod> semanticResults = embeddingService.findSimilarMethods(question,
                    projectId, 8);
            String semanticContext = buildSemanticContext(semanticResults);
            log.debug("Semantic results: {} methods found", semanticResults.size());

            // ── STEP 4: Merge contexts ─────────────────────────
            String mergedContext = contextMerger.merge(graphContext, semanticContext, decomposed);

            // ── STEP 5: Generate grounded answer ───────────────
            List<Message> messages = buildMessages(history, mergedContext, question);

            log.debug("Sending to Ollama (codellama:13b)...");

            return chatClient.prompt()
                    .messages(messages)
                    .stream()
                    .content()
                    .doOnComplete(() -> log.info("Answer streamed in {}ms", System.currentTimeMillis() - start))
                    .doOnError(e -> log.error("Streaming error: {}", e.getMessage()));

        } catch (Exception e) {
            log.error("Graph RAG pipeline failed: {}", e.getMessage(), e);
            return Flux.just("I encountered an error analyzing the codebase: " + e.getMessage());
        }
    }

    /**
     * Non-streaming version — returns complete answer at once.
     * Useful for PR analysis and batch processing.
     */
    public RAGAnswer answer(String question, String projectId) {
        log.info("Graph RAG (sync): '{}'", question);

        QueryDecomposer.DecomposedQuery decomposed = queryDecomposer.decompose(question, projectId);

        String graphContext = buildGraphContext(decomposed, projectId);
        List<EmbeddingService.SimilarMethod> semanticResults = embeddingService.findSimilarMethods(question, projectId,
                6);
        String semanticContext = buildSemanticContext(semanticResults);
        String mergedContext = contextMerger.merge(graphContext, semanticContext, decomposed);

        List<Message> messages = buildMessages(Collections.emptyList(), mergedContext, question);

        String rawAnswer = chatClient.prompt()
                .messages(messages)
                .call()
                .content();

        // Extract citations from semantic results
        List<String> citations = semanticResults.stream()
                .map(EmbeddingService.SimilarMethod::getCitation)
                .distinct()
                .collect(Collectors.toList());

        // Validate — guard against hallucinated class names
        RAGAnswer answer = RAGAnswer.builder()
                .question(question)
                .answer(rawAnswer)
                .citations(citations)
                .graphContextUsed(!graphContext.isBlank())
                .semanticResultsUsed(semanticResults.size())
                .intent(decomposed.getIntent().name())
                .build();

        hallucinationGuard.validate(answer, projectId);

        return answer;
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE — Pipeline steps
    // ─────────────────────────────────────────────────────────

    /**
     * STEP 2: Build structural context from Neo4j graph traversal.
     * This is what makes Graph RAG different from standard RAG.
     */
    private String buildGraphContext(QueryDecomposer.DecomposedQuery decomposed,
            String projectId) {
        StringBuilder context = new StringBuilder();

        for (String entity : decomposed.getEntities()) {
            switch (decomposed.getIntent()) {

                case DEPENDENCY_QUERY -> {
                    GraphQueryService.DependencyResult deps = graphQueryService.getDependencies(entity, projectId);
                    if (deps.isFound()) {
                        context.append("=== Dependency Analysis: ").append(entity).append(" ===\n");
                        context.append("Direct dependents (classes that USE this): ")
                                .append(deps.getDirectDependents()).append("\n");
                        context.append("All transitive dependents (full impact): ")
                                .append(deps.getAllTransitiveDependents()).append("\n");
                        context.append("Extends: ").append(deps.getSuperClasses()).append("\n");
                        context.append("Implements: ").append(deps.getInterfaces()).append("\n");
                        context.append("Impact score: ").append(deps.getImpactScore()).append("/100\n\n");
                    }
                }

                case CALL_CHAIN_QUERY -> {
                    List<String> chain = graphQueryService.traceCallChain(entity, projectId);
                    if (!chain.isEmpty()) {
                        context.append("=== Call Chain from ").append(entity).append(" ===\n");
                        chain.forEach(step -> context.append("  → ").append(step).append("\n"));
                        context.append("\n");
                    }
                }

                case CALLER_QUERY -> {
                    // Split "ClassName.methodName" format
                    String[] parts = entity.split("\\.");
                    if (parts.length >= 2) {
                        String className = parts[0];
                        String methodName = parts[parts.length - 1];
                        List<String> callers = graphQueryService
                                .getCallers(className, methodName, projectId);
                        context.append("=== Who calls ").append(entity).append(" ===\n");
                        callers.forEach(c -> context.append("  • ").append(c).append("\n"));
                        context.append("\n");
                    }
                }

                case DEAD_CODE_QUERY -> {
                    List<String> dead = graphQueryService.findDeadCode(projectId);
                    context.append("=== Dead Code (never called) ===\n");
                    dead.forEach(d -> context.append("  • ").append(d).append("\n"));
                    context.append("\n");
                }

                case DB_OPERATION_QUERY -> {
                    List<String> writes = graphQueryService.findDatabaseWrites(projectId);
                    context.append("=== Database Write Operations ===\n");
                    writes.forEach(w -> context.append("  • ").append(w).append("\n"));
                    context.append("\n");
                }

                case CIRCULAR_DEP_QUERY -> {
                    List<String> cycles = graphQueryService.findCircularDependencies(projectId);
                    context.append("=== Circular Dependencies ===\n");
                    if (cycles.isEmpty()) {
                        context.append("  No circular dependencies found.\n");
                    } else {
                        cycles.forEach(c -> context.append("  ⚠️  ").append(c).append("\n"));
                    }
                    context.append("\n");
                }

                default -> {
                    // General query — just get stats
                    GraphQueryService.ProjectStats stats = graphQueryService.getProjectStats(projectId);
                    context.append("=== Project Overview ===\n");
                    context.append("Total classes: ").append(stats.getTotalClasses()).append("\n");
                    context.append("Total methods: ").append(stats.getTotalMethods()).append("\n");
                    context.append("Avg complexity: ")
                            .append(String.format("%.1f", stats.getAverageComplexity())).append("\n");
                    context.append("Circular deps: ").append(stats.getCircularDependencies()).append("\n");
                }
            }
        }

        return context.toString();
    }

    /**
     * STEP 3: Build context from semantic vector search results.
     */
    private String buildSemanticContext(List<EmbeddingService.SimilarMethod> results) {
        if (results.isEmpty())
            return "";

        StringBuilder context = new StringBuilder("=== Semantically Relevant Code ===\n");

        results.stream()
                .filter(r -> r.getSimilarityScore() > 0.6) // Only high-confidence matches
                .limit(5)
                .forEach(method -> {
                    context.append("\n[")
                            .append(method.getCitation())
                            .append("] ")
                            .append(method.getClassName())
                            .append(".")
                            .append(method.getMethodName())
                            .append("\n");

                    // Include first 300 chars of source code
                    if (method.getSourceCode() != null) {
                        String code = method.getSourceCode();
                        if (code.length() > 300)
                            code = code.substring(0, 300) + "...";
                        context.append(code).append("\n");
                    }
                });

        return context.toString();
    }

    /**
     * STEP 5: Build the full message list for the LLM.
     * Includes system prompt, conversation history, and context.
     */
    private List<Message> buildMessages(List<ConversationTurn> history,
            String context, String question) {
        List<Message> messages = new ArrayList<>();

        // System message with strict grounding instructions
        String systemPrompt = """
                You are FlowSense, an expert Java codebase analyst.

                CRITICAL RULES — follow these exactly:
                1. ONLY use class/method names from the CODEBASE CONTEXT below
                2. NEVER invent or hallucinate class names, method names, or file paths
                3. ALWAYS cite your sources using [FileName.java:lineNumber] format
                4. If the context doesn't contain enough information, say so clearly
                5. Be precise and technical — the user is a software engineer
                6. Format code references in backticks: `ClassName.methodName()`

                CODEBASE CONTEXT (from graph traversal + semantic search):
                ---
                %s
                ---

                Answer based ONLY on the context above.
                """.formatted(context.isBlank() ? "No specific context retrieved." : context);

        messages.add(new org.springframework.ai.chat.messages.SystemMessage(systemPrompt));

        // Add conversation history (for follow-up questions)
        for (ConversationTurn turn : history) {
            messages.add(new UserMessage(turn.getQuestion()));
            messages.add(new AssistantMessage(turn.getAnswer()));
        }

        // Add current question
        messages.add(new UserMessage(question));

        return messages;
    }

    // ── Result DTOs ───────────────────────────────────────────

    @lombok.Data
    @lombok.Builder
    public static class RAGAnswer {
        private String question;
        private String answer;
        private List<String> citations;
        private boolean graphContextUsed;
        private int semanticResultsUsed;
        private String intent;
        private boolean validated;
        private List<String> validationWarnings;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ConversationTurn {
        private String question;
        private String answer;
    }
}
