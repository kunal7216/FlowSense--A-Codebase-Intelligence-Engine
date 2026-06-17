package com.flowsense.api;

import com.flowsense.ai.ConversationMemory;
import com.flowsense.ai.GraphRAGEngine;
import com.flowsense.prediction.IncidentHistoryService;
import com.flowsense.prediction.PRAnalysisService;
import com.flowsense.prediction.PRImpactReport;
import com.flowsense.webhook.PREvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for Phase 2 — Q&A and PR Analysis.
 *
 * NEW ENDPOINTS:
 * POST /api/query/{projectId} → Q&A (non-streaming)
 * POST /api/query/{projectId}/stream → Q&A (streaming, token by token)
 * POST /api/predict/pr → Manual PR impact analysis
 * POST /api/incidents → Record a production incident
 * DELETE /api/query/{sessionId}/clear → Clear conversation history
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class Querycontroller {

    private final GraphRAGEngine graphRAGEngine;
    private final ConversationMemory conversationMemory;
    private final PRAnalysisService prAnalysisService;
    private final IncidentHistoryService incidentHistoryService;

    // ── Q&A ENDPOINTS ─────────────────────────────────────────

    /**
     * POST /api/query/{projectId}
     * Ask a question about the codebase — returns full answer at once.
     *
     * Body: { "question": "What depends on PaymentService?", "sessionId": "abc123"
     * }
     *
     * Example questions:
     * - "What breaks if I change PaymentService?"
     * - "Trace the call chain from checkout()"
     * - "Who calls processPayment?"
     * - "Are there any circular dependencies?"
     * - "Find all database write operations"
     * - "What dead code exists?"
     */
    @PostMapping("/query/{projectId}")
    public ResponseEntity<GraphRAGEngine.RAGAnswer> query(
            @PathVariable String projectId,
            @RequestBody QueryRequest request) {

        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();

        // Get conversation history for follow-up question support
        List<GraphRAGEngine.ConversationTurn> history = conversationMemory.getHistory(sessionId);

        // Run Graph RAG pipeline
        GraphRAGEngine.RAGAnswer answer = graphRAGEngine.answer(
                request.getQuestion(), projectId);

        // Store in conversation memory for follow-ups
        conversationMemory.addTurn(sessionId, request.getQuestion(), answer.getAnswer());

        return ResponseEntity.ok(answer);
    }

    /**
     * POST /api/query/{projectId}/stream
     * Same as above but streams tokens in real-time (Server-Sent Events).
     *
     * In Postman: set Accept header to text/event-stream
     * Watch the answer appear token by token!
     */
    @PostMapping(value = "/query/{projectId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> queryStream(
            @PathVariable String projectId,
            @RequestBody QueryRequest request) {

        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();

        List<GraphRAGEngine.ConversationTurn> history = conversationMemory.getHistory(sessionId);

        // Accumulate the full answer for memory storage
        StringBuilder fullAnswer = new StringBuilder();

        return graphRAGEngine
                .answerStreaming(request.getQuestion(), projectId, history)
                .doOnNext(token -> fullAnswer.append(token))
                .doOnComplete(() -> {
                    // Save to conversation memory after streaming completes
                    conversationMemory.addTurn(
                            sessionId, request.getQuestion(), fullAnswer.toString());
                });
    }

    /**
     * DELETE /api/query/{sessionId}/clear
     * Clear conversation history — start a new session.
     */
    @DeleteMapping("/query/{sessionId}/clear")
    public ResponseEntity<Map<String, String>> clearSession(@PathVariable String sessionId) {
        conversationMemory.clearSession(sessionId);
        return ResponseEntity.ok(Map.of(
                "status", "cleared",
                "sessionId", sessionId));
    }

    // ── PR IMPACT ANALYSIS ────────────────────────────────────

    /**
     * POST /api/predict/pr
     * Manually trigger PR impact analysis without a GitHub webhook.
     * Useful for testing the predictor directly.
     *
     * Body: {
     * "projectId": "myproject",
     * "prNumber": 42,
     * "prTitle": "Refactor PaymentService",
     * "changedFiles": ["src/main/java/.../PaymentService.java"]
     * }
     */
    @PostMapping("/predict/pr")
    public ResponseEntity<PRImpactReport> analyzePR(
            @RequestBody PRAnalysisRequest request) {

        PREvent event = PREvent.builder()
                .projectId(request.getProjectId())
                .prNumber(request.getPrNumber())
                .prTitle(request.getPrTitle())
                .prUrl("manual-trigger")
                .repoName(request.getProjectId())
                .changedFiles(request.getChangedFiles())
                .filesChanged(request.getChangedFiles().size())
                .receivedAt(java.time.LocalDateTime.now())
                .build();

        PRImpactReport report = prAnalysisService.analyzePR(event);
        return ResponseEntity.ok(report);
    }

    // ── INCIDENT MANAGEMENT ───────────────────────────────────

    /**
     * POST /api/incidents
     * Record a production incident to train the historical risk model.
     *
     * Body: {
     * "title": "PaymentService timeout caused checkout failures",
     * "description": "High latency in processPayment() caused 500 errors",
     * "severity": 4,
     * "affectedServices": "PaymentService, OrderService, CheckoutController"
     * }
     */
    @PostMapping("/incidents")
    public ResponseEntity<Map<String, String>> recordIncident(
            @RequestBody IncidentRequest request) {

        incidentHistoryService.recordIncident(
                request.getTitle(),
                request.getDescription(),
                request.getSeverity(),
                request.getAffectedServices());

        return ResponseEntity.ok(Map.of(
                "status", "recorded",
                "message", "Incident recorded. Future PRs touching similar code will get higher risk scores."));
    }

    // ── REQUEST DTOs ──────────────────────────────────────────

    @lombok.Data
    public static class QueryRequest {
        private String question;
        private String sessionId; // Optional — generated if not provided
    }

    @lombok.Data
    public static class PRAnalysisRequest {
        private String projectId;
        private int prNumber;
        private String prTitle;
        private List<String> changedFiles;
    }

    @lombok.Data
    public static class IncidentRequest {
        private String title;
        private String description;
        private int severity; // 1-5
        private String affectedServices;
    }
}
