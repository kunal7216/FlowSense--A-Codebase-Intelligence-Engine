package com.flowsense.api;

import com.flowsense.embedding.EmbeddingService;
import com.flowsense.graph.*;
import com.flowsense.parser.CodebaseScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * REST API for FlowSense Phase 1.
 *
 * All endpoints here can be tested in Postman immediately.
 *
 * BASE URL: http://localhost:8080/api
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CodebaseController {

    private final CodebaseScanner codebaseScanner;
    private final CodeGraphBuilder codeGraphBuilder;
    private final GraphQueryService graphQueryService;
    private final EmbeddingService embeddingService;

    // ── INDEX PROJECT ─────────────────────────────────────────

    /**
     * POST /api/projects/index
     * Index a Java project from a local path.
     *
     * Body: { "projectId": "myproject", "projectPath": "C:/path/to/project" }
     *
     * This is the MAIN entry point — run this first.
     */
    @PostMapping("/projects/index")
    public ResponseEntity<IndexResponse> indexProject(@RequestBody IndexRequest request) {
        log.info("Index request received for project: {}", request.getProjectId());

        try {
            Path projectPath = Paths.get(request.getProjectPath());

            // Step 1: Scan all Java files
            log.info("Step 1/3: Scanning files...");
            CodebaseScanner.ScanResult scanResult = codebaseScanner.scanProject(projectPath);

            // Step 2: Build Neo4j graph
            log.info("Step 2/3: Building knowledge graph...");
            CodeGraphBuilder.GraphBuildResult graphResult =
                codeGraphBuilder.buildGraph(scanResult, request.getProjectId());

            // Step 3: Generate embeddings (runs async for large projects)
            log.info("Step 3/3: Generating embeddings...");
            int embeddingsGenerated = embeddingService.embedProject(
                scanResult.getClasses(), request.getProjectId());

            return ResponseEntity.ok(IndexResponse.builder()
                .projectId(request.getProjectId())
                .status("SUCCESS")
                .filesProcessed(scanResult.getFilesProcessed())
                .classesFound(scanResult.getTotalClasses())
                .methodsFound(scanResult.getTotalMethods())
                .nodesCreated(graphResult.getNodesCreated())
                .relationshipsCreated(graphResult.getRelationshipsCreated())
                .embeddingsGenerated(embeddingsGenerated)
                .scanDurationMs(scanResult.getScanDurationMs())
                .graphBuildDurationMs(graphResult.getBuildDurationMs())
                .indexedAt(LocalDateTime.now())
                .message("Project indexed successfully! Open Neo4j Browser at http://localhost:7474 to explore the graph.")
                .build());

        } catch (Exception e) {
            log.error("Failed to index project: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                .body(IndexResponse.builder()
                    .projectId(request.getProjectId())
                    .status("FAILED")
                    .message("Error: " + e.getMessage())
                    .build());
        }
    }

    // ── GRAPH QUERIES ─────────────────────────────────────────

    /**
     * GET /api/graph/{projectId}/dependencies?class=PaymentService
     * Get all dependencies of a class.
     */
    @GetMapping("/graph/{projectId}/dependencies")
    public ResponseEntity<GraphQueryService.DependencyResult> getDependencies(
            @PathVariable String projectId,
            @RequestParam String className) {
        log.debug("Get dependencies: {} in {}", className, projectId);
        GraphQueryService.DependencyResult result =
            graphQueryService.getDependencies(className, projectId);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/graph/{projectId}/callers?class=PaymentService&method=processPayment
     * Find all callers of a method.
     */
    @GetMapping("/graph/{projectId}/callers")
    public ResponseEntity<Map<String, Object>> getCallers(
            @PathVariable String projectId,
            @RequestParam String className,
            @RequestParam String method) {
        List<String> callers = graphQueryService.getCallers(className, method, projectId);
        return ResponseEntity.ok(Map.of(
            "class", className,
            "method", method,
            "calledBy", callers,
            "callerCount", callers.size()
        ));
    }

    /**
     * GET /api/graph/{projectId}/trace?method=checkout
     * Trace the full execution chain from a method.
     */
    @GetMapping("/graph/{projectId}/trace")
    public ResponseEntity<Map<String, Object>> traceCallChain(
            @PathVariable String projectId,
            @RequestParam String method) {
        List<String> chain = graphQueryService.traceCallChain(method, projectId);
        return ResponseEntity.ok(Map.of(
            "entryPoint", method,
            "callChain", chain,
            "depth", chain.size()
        ));
    }

    /**
     * GET /api/graph/{projectId}/circular-deps
     * Detect circular dependencies.
     */
    @GetMapping("/graph/{projectId}/circular-deps")
    public ResponseEntity<Map<String, Object>> findCircularDependencies(
            @PathVariable String projectId) {
        List<String> cycles = graphQueryService.findCircularDependencies(projectId);
        return ResponseEntity.ok(Map.of(
            "circularDependencies", cycles,
            "count", cycles.size(),
            "hasCycles", !cycles.isEmpty()
        ));
    }

    /**
     * GET /api/graph/{projectId}/dead-code
     * Find dead code — classes never referenced.
     */
    @GetMapping("/graph/{projectId}/dead-code")
    public ResponseEntity<Map<String, Object>> findDeadCode(
            @PathVariable String projectId) {
        List<String> deadCode = graphQueryService.findDeadCode(projectId);
        return ResponseEntity.ok(Map.of(
            "deadCodeClasses", deadCode,
            "count", deadCode.size()
        ));
    }

    /**
     * GET /api/graph/{projectId}/db-writes
     * Find all database write operations.
     */
    @GetMapping("/graph/{projectId}/db-writes")
    public ResponseEntity<Map<String, Object>> findDatabaseWrites(
            @PathVariable String projectId) {
        List<String> writes = graphQueryService.findDatabaseWrites(projectId);
        return ResponseEntity.ok(Map.of(
            "databaseWriteOperations", writes,
            "count", writes.size()
        ));
    }

    /**
     * GET /api/graph/{projectId}/stats
     * Get project-level statistics.
     */
    @GetMapping("/graph/{projectId}/stats")
    public ResponseEntity<GraphQueryService.ProjectStats> getProjectStats(
            @PathVariable String projectId) {
        return ResponseEntity.ok(graphQueryService.getProjectStats(projectId));
    }

    // ── SEMANTIC SEARCH ───────────────────────────────────────

    /**
     * GET /api/search/{projectId}?q=payment+processing&limit=10
     * Semantic search across all method embeddings.
     * "Find all methods related to payment processing"
     */
    @GetMapping("/search/{projectId}")
    public ResponseEntity<SearchResponse> semanticSearch(
            @PathVariable String projectId,
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {
        log.debug("Semantic search: '{}' in project {}", q, projectId);

        long start = System.currentTimeMillis();
        List<EmbeddingService.SimilarMethod> results =
            embeddingService.findSimilarMethods(q, projectId, limit);
        long duration = System.currentTimeMillis() - start;

        return ResponseEntity.ok(SearchResponse.builder()
            .query(q)
            .results(results)
            .resultCount(results.size())
            .searchDurationMs(duration)
            .build());
    }

    // ── REQUEST / RESPONSE DTOs ───────────────────────────────

    @lombok.Data
    public static class IndexRequest {
        private String projectId;
        private String projectPath;
    }

    @lombok.Data
    @lombok.Builder
    public static class IndexResponse {
        private String projectId;
        private String status;
        private int filesProcessed;
        private int classesFound;
        private int methodsFound;
        private int nodesCreated;
        private int relationshipsCreated;
        private int embeddingsGenerated;
        private long scanDurationMs;
        private long graphBuildDurationMs;
        private LocalDateTime indexedAt;
        private String message;
    }

    @lombok.Data
    @lombok.Builder
    public static class SearchResponse {
        private String query;
        private List<EmbeddingService.SimilarMethod> results;
        private int resultCount;
        private long searchDurationMs;
    }
}
