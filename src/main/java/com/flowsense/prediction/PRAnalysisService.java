package com.flowsense.prediction;

import com.flowsense.ai.GraphRAGEngine;
import com.flowsense.embedding.EmbeddingService;
import com.flowsense.graph.ClassNodeRepository;
import com.flowsense.graph.GraphQueryService;
import com.flowsense.graph.MethodNodeRepository;
import com.flowsense.webhook.PREvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ╔══════════════════════════════════════════════════════════╗
 * ║ PR Change Impact Predictor — Phase 2 Star ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 * WHAT IT DOES:
 * Before any code merges, FlowSense predicts:
 * - Which services are impacted (from graph traversal)
 * - Risk score 0-100 (from multiple signals)
 * - Why it's risky (from LLM reasoning)
 * - Which tests are most critical to run
 *
 * HOW RISK SCORE IS CALCULATED:
 * signal 1 (40%): Transitive impact breadth — how many services affected?
 * signal 2 (35%): Historical incident similarity — has similar code broken
 * before?
 * signal 3 (15%): Complexity of changed code — high cyclomatic complexity =
 * higher risk
 * signal 4 (10%): Test coverage gap — are the impacted methods tested?
 *
 * INTERVIEW TALKING POINT:
 * "The risk score combines static analysis from the graph with
 * historical pattern matching from vector similarity search.
 * It's not pure ML — it's a weighted formula over explainable
 * signals, which means engineers can understand why a PR is
 * flagged. Explainability was a deliberate design choice."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PRAnalysisService {

    private final ClassNodeRepository classRepository;
    private final MethodNodeRepository methodRepository;
    private final GraphQueryService graphQueryService;
    private final EmbeddingService embeddingService;
    private final ChatClient chatClient;
    private final IncidentHistoryService incidentHistoryService;

    /**
     * Run full impact analysis on a PR.
     *
     * @param event The PR event from GitHub webhook
     * @return Full impact report with risk score and recommendations
     */
    public PRImpactReport analyzePR(PREvent event) {
        log.info("Analyzing PR #{}: {} files changed",
                event.getPrNumber(), event.getFilesChanged());

        // ── SIGNAL 1: Graph Impact Analysis ───────────────────
        ImpactAnalysis impact = analyzeGraphImpact(event);
        log.debug("Graph impact: {} direct, {} transitive services affected",
                impact.getDirectlyImpacted().size(), impact.getTransitivelyImpacted().size());

        // ── SIGNAL 2: Historical Incident Similarity ──────────
        double historicalRisk = calculateHistoricalRisk(event, impact);
        log.debug("Historical risk signal: {}", historicalRisk);

        // ── SIGNAL 3: Code Complexity of Changed Files ────────
        double complexityRisk = calculateComplexityRisk(event, impact);
        log.debug("Complexity risk signal: {}", complexityRisk);

        // ── SIGNAL 4: Test Coverage Gap ───────────────────────
        double coverageGap = estimateCoverageGap(impact);
        log.debug("Coverage gap signal: {}", coverageGap);

        // ── COMBINE: Weighted Risk Score ──────────────────────
        int riskScore = calculateFinalRiskScore(
                impact, historicalRisk, complexityRisk, coverageGap);

        // ── LLM: Generate Human-Readable Explanation ──────────
        String riskExplanation = generateRiskExplanation(event, impact, riskScore);

        // ── RECOMMEND: Which tests to run ─────────────────────
        List<String> recommendedTests = recommendTests(impact, event.getProjectId());

        return PRImpactReport.builder()
                .prNumber(event.getPrNumber())
                .prTitle(event.getPrTitle())
                .prUrl(event.getPrUrl())
                .projectId(event.getProjectId())
                .riskScore(riskScore)
                .riskLevel(getRiskLevel(riskScore))
                .directlyImpactedServices(impact.getDirectlyImpacted())
                .transitivelyImpactedServices(impact.getTransitivelyImpacted())
                .totalServicesImpacted(
                        impact.getDirectlyImpacted().size() + impact.getTransitivelyImpacted().size())
                .historicalRiskSignal(historicalRisk)
                .complexityRiskSignal(complexityRisk)
                .coverageGapSignal(coverageGap)
                .riskExplanation(riskExplanation)
                .recommendedTests(recommendedTests)
                .changedClasses(impact.getChangedClasses())
                .analyzedAt(java.time.LocalDateTime.now())
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // SIGNAL 1: Graph Impact Analysis
    // ─────────────────────────────────────────────────────────

    private ImpactAnalysis analyzeGraphImpact(PREvent event) {
        Set<String> directlyImpacted = new LinkedHashSet<>();
        Set<String> transitivelyImpacted = new LinkedHashSet<>();
        List<String> changedClasses = new ArrayList<>();

        // Extract class names from changed file paths
        for (String filePath : event.getChangedFiles()) {
            String className = extractClassName(filePath);
            if (className != null) {
                changedClasses.add(className);

                // Find all classes that directly depend on this class
                GraphQueryService.DependencyResult deps = graphQueryService.getDependencies(className,
                        event.getProjectId());

                if (deps.isFound()) {
                    directlyImpacted.addAll(deps.getDirectDependents());
                    transitivelyImpacted.addAll(deps.getAllTransitiveDependents());
                }
            }
        }

        // Remove the changed classes themselves from impacted lists
        directlyImpacted.removeAll(changedClasses);
        transitivelyImpacted.removeAll(changedClasses);
        transitivelyImpacted.removeAll(directlyImpacted);

        return ImpactAnalysis.builder()
                .changedClasses(changedClasses)
                .directlyImpacted(new ArrayList<>(directlyImpacted))
                .transitivelyImpacted(new ArrayList<>(transitivelyImpacted))
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // SIGNAL 2: Historical Incident Similarity
    // ─────────────────────────────────────────────────────────

    private double calculateHistoricalRisk(PREvent event, ImpactAnalysis impact) {
        // Build a description of what this PR touches
        String prDescription = "PR changes classes: " +
                String.join(", ", impact.getChangedClasses()) +
                " affecting " + impact.getDirectlyImpacted();

        // Find similar past incidents using semantic similarity
        List<IncidentHistoryService.IncidentRecord> similarIncidents = incidentHistoryService
                .findSimilarIncidents(prDescription, 5);

        if (similarIncidents.isEmpty())
            return 0.0;

        // Average similarity of top incidents (higher = more likely to repeat)
        double avgSimilarity = similarIncidents.stream()
                .mapToDouble(IncidentHistoryService.IncidentRecord::getSimilarityScore)
                .average()
                .orElse(0.0);

        // Weight by incident severity
        double severityWeight = similarIncidents.stream()
                .mapToDouble(i -> i.getSeverity() / 5.0)
                .average()
                .orElse(0.5);

        return Math.min(1.0, avgSimilarity * severityWeight * 1.5);
    }

    // ─────────────────────────────────────────────────────────
    // SIGNAL 3: Complexity Risk
    // ─────────────────────────────────────────────────────────

    private double calculateComplexityRisk(PREvent event, ImpactAnalysis impact) {
        // Get average cyclomatic complexity of changed classes
        double avgComplexity = impact.getChangedClasses().stream()
                .flatMap(className -> methodRepository
                        .findByClassNameAndProjectId(className, event.getProjectId()).stream())
                .mapToInt(m -> m.getCyclomaticComplexity())
                .average()
                .orElse(1.0);

        // Normalize: complexity 1-5 = low risk, 6-10 = medium, 10+ = high
        if (avgComplexity <= 5)
            return 0.2;
        if (avgComplexity <= 10)
            return 0.5;
        return Math.min(1.0, avgComplexity / 20.0);
    }

    // ─────────────────────────────────────────────────────────
    // SIGNAL 4: Test Coverage Gap
    // ─────────────────────────────────────────────────────────

    private double estimateCoverageGap(ImpactAnalysis impact) {
        // Simplified: check if impacted classes have corresponding test files
        // In production, integrate with JaCoCo reports
        long impactedCount = impact.getDirectlyImpacted().size() +
                impact.getTransitivelyImpacted().size();
        if (impactedCount == 0)
            return 0.0;

        // Heuristic: more impacted = higher coverage gap risk
        return Math.min(1.0, impactedCount / 20.0);
    }

    // ─────────────────────────────────────────────────────────
    // FINAL SCORE CALCULATION
    // ─────────────────────────────────────────────────────────

    private int calculateFinalRiskScore(ImpactAnalysis impact,
            double historicalRisk,
            double complexityRisk,
            double coverageGap) {
        // Impact breadth score (0-1)
        int totalImpacted = impact.getDirectlyImpacted().size() +
                impact.getTransitivelyImpacted().size();
        double impactScore = Math.min(1.0, totalImpacted / 15.0);

        // Weighted combination
        double score = (impactScore * 0.40) + // 40% — how many services affected
                (historicalRisk * 0.35) + // 35% — has similar code broken before?
                (complexityRisk * 0.15) + // 15% — how complex is changed code?
                (coverageGap * 0.10); // 10% — is it tested?

        return (int) Math.round(score * 100);
    }

    // ─────────────────────────────────────────────────────────
    // LLM: Generate Plain-English Explanation
    // ─────────────────────────────────────────────────────────

    private String generateRiskExplanation(PREvent event,
            ImpactAnalysis impact,
            int riskScore) {
        String prompt = """
                You are a senior software engineer reviewing a PR.
                Generate a concise 3-4 sentence risk explanation.

                PR: %s
                Risk Score: %d/100
                Changed Classes: %s
                Directly Impacted Services: %s
                Transitively Impacted Services: %s

                Write a clear, technical explanation of:
                1. Why this PR has this risk score
                2. What could go wrong if this is wrong
                3. One specific recommendation

                Be direct. No fluff. Max 4 sentences.
                """.formatted(
                event.getPrTitle(),
                riskScore,
                impact.getChangedClasses(),
                impact.getDirectlyImpacted(),
                impact.getTransitivelyImpacted());

        try {
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("LLM explanation failed, using template: {}", e.getMessage());
            return generateTemplateExplanation(event, impact, riskScore);
        }
    }

    private String generateTemplateExplanation(PREvent event,
            ImpactAnalysis impact,
            int riskScore) {
        String level = getRiskLevel(riskScore);
        return String.format(
                "This PR modifies %s and has a %s risk score of %d/100. " +
                        "It directly impacts %d services and has %d transitive dependencies. " +
                        "Review %s carefully before merging.",
                String.join(", ", impact.getChangedClasses()),
                level, riskScore,
                impact.getDirectlyImpacted().size(),
                impact.getTransitivelyImpacted().size(),
                impact.getDirectlyImpacted().isEmpty() ? "the changes" : impact.getDirectlyImpacted().get(0));
    }

    // ─────────────────────────────────────────────────────────
    // TEST RECOMMENDATIONS
    // ─────────────────────────────────────────────────────────

    private List<String> recommendTests(ImpactAnalysis impact, String projectId) {
        List<String> tests = new ArrayList<>();

        // Recommend tests for changed classes
        impact.getChangedClasses().forEach(cls -> tests.add(cls + "Test — unit test for changed class"));

        // Recommend integration tests for directly impacted services
        impact.getDirectlyImpacted().stream()
                .limit(3)
                .forEach(svc -> tests.add(svc + "IntegrationTest — verify integration still works"));

        if (tests.isEmpty()) {
            tests.add("No specific test recommendations — changes appear isolated");
        }

        return tests;
    }

    // ─────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────

    private String extractClassName(String filePath) {
        if (filePath == null || !filePath.endsWith(".java"))
            return null;
        String[] parts = filePath.split("[/\\\\]");
        String fileName = parts[parts.length - 1];
        return fileName.replace(".java", "");
    }

    private String getRiskLevel(int score) {
        if (score >= 75)
            return "CRITICAL";
        if (score >= 50)
            return "HIGH";
        if (score >= 25)
            return "MEDIUM";
        return "LOW";
    }

    // ── Inner Types ───────────────────────────────────────────

    @lombok.Data
    @lombok.Builder
    static class ImpactAnalysis {
        private List<String> changedClasses;
        private List<String> directlyImpacted;
        private List<String> transitivelyImpacted;
    }
}
