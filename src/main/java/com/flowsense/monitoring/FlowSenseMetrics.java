package com.flowsense.monitoring;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom Prometheus metrics for FlowSense.
 * All metrics auto-scraped by Prometheus and visualized in Grafana.
 *
 * METRICS EXPOSED:
 * - flowsense_queries_total → Q&A query count
 * - flowsense_query_duration_seconds → Q&A response latency
 * - flowsense_embeddings_total → Embeddings generated
 * - flowsense_graph_nodes_total → Neo4j node count
 * - flowsense_pr_analyses_total → PR analyses run
 * - flowsense_risk_score_histogram → Distribution of PR risk scores
 * - flowsense_ollama_latency_seconds → Ollama response time
 * - flowsense_indexed_projects → Projects currently indexed
 *
 * INTERVIEW TALKING POINT:
 * "I exposed custom Prometheus metrics for every key operation —
 * query latency, embedding generation time, PR analysis count.
 * In the Grafana dashboard, I can see p95 query latency in real time.
 * This is how you prove your system works at scale — not just in
 * a demo but under load."
 */
@Slf4j
@Service
public class FlowSenseMetrics {

    private final MeterRegistry registry;

    // Counters
    private final Counter queryCounter;
    private final Counter embeddingCounter;
    private final Counter prAnalysisCounter;
    private final Counter errorCounter;

    // Timers (latency histograms)
    private final Timer queryTimer;
    private final Timer embeddingTimer;
    private final Timer prAnalysisTimer;
    private final Timer ollamaTimer;
    private final Timer graphQueryTimer;

    // Gauges (current state)
    private final AtomicInteger indexedProjectCount = new AtomicInteger(0);
    private final AtomicInteger activeQueryCount = new AtomicInteger(0);

    // Histograms
    private final DistributionSummary riskScoreDistribution;

    public FlowSenseMetrics(MeterRegistry registry) {
        this.registry = registry;

        // ── Counters ───────────────────────────────────────────
        this.queryCounter = Counter.builder("flowsense.queries.total")
                .description("Total number of Q&A queries processed")
                .tag("type", "rag")
                .register(registry);

        this.embeddingCounter = Counter.builder("flowsense.embeddings.total")
                .description("Total embeddings generated")
                .register(registry);

        this.prAnalysisCounter = Counter.builder("flowsense.pr.analyses.total")
                .description("Total PR impact analyses run")
                .register(registry);

        this.errorCounter = Counter.builder("flowsense.errors.total")
                .description("Total errors encountered")
                .register(registry);

        // ── Timers ─────────────────────────────────────────────
        this.queryTimer = Timer.builder("flowsense.query.duration")
                .description("Q&A query response time")
                .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        this.embeddingTimer = Timer.builder("flowsense.embedding.duration")
                .description("Embedding generation time per method")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        this.prAnalysisTimer = Timer.builder("flowsense.pr.analysis.duration")
                .description("PR impact analysis end-to-end time")
                .publishPercentiles(0.5, 0.75, 0.95)
                .register(registry);

        this.ollamaTimer = Timer.builder("flowsense.ollama.latency")
                .description("Ollama (LLM) response latency")
                .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        this.graphQueryTimer = Timer.builder("flowsense.neo4j.query.duration")
                .description("Neo4j graph query execution time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // ── Gauges ─────────────────────────────────────────────
        Gauge.builder("flowsense.projects.indexed", indexedProjectCount, AtomicInteger::get)
                .description("Number of projects currently indexed")
                .register(registry);

        Gauge.builder("flowsense.queries.active", activeQueryCount, AtomicInteger::get)
                .description("Number of queries currently being processed")
                .register(registry);

        // ── Distribution Summary ───────────────────────────────
        this.riskScoreDistribution = DistributionSummary.builder("flowsense.pr.risk.score")
                .description("Distribution of PR risk scores")
                .baseUnit("score")
                .publishPercentiles(0.5, 0.75, 0.95)
                .scale(1)
                .register(registry);

        log.info("FlowSense Prometheus metrics registered");
    }

    // ── PUBLIC API ────────────────────────────────────────────

    /**
     * Record a completed Q&A query.
     */
    public void recordQuery(long durationMs, String intent, boolean success) {
        queryCounter.increment();
        queryTimer.record(durationMs, TimeUnit.MILLISECONDS);
        if (!success)
            errorCounter.increment();

        // Per-intent breakdown
        Counter.builder("flowsense.queries.by.intent")
                .tag("intent", intent)
                .tag("success", String.valueOf(success))
                .register(registry)
                .increment();
    }

    /**
     * Record embedding generation.
     */
    public void recordEmbedding(long durationMs, int count) {
        embeddingCounter.increment(count);
        embeddingTimer.record(durationMs / Math.max(1, count), TimeUnit.MILLISECONDS);
    }

    /**
     * Record PR analysis.
     */
    public void recordPRAnalysis(long durationMs, int riskScore) {
        prAnalysisCounter.increment();
        prAnalysisTimer.record(durationMs, TimeUnit.MILLISECONDS);
        riskScoreDistribution.record(riskScore);
    }

    /**
     * Record Ollama LLM call latency.
     */
    public void recordOllamaCall(long durationMs, boolean success) {
        ollamaTimer.record(durationMs, TimeUnit.MILLISECONDS);
        if (!success)
            errorCounter.increment();
    }

    /**
     * Record Neo4j graph query latency.
     */
    public void recordGraphQuery(long durationMs, String queryType) {
        graphQueryTimer.record(durationMs, TimeUnit.MILLISECONDS);
        Counter.builder("flowsense.neo4j.queries.by.type")
                .tag("type", queryType)
                .register(registry)
                .increment();
    }

    /**
     * Update project indexing gauge.
     */
    public void setIndexedProjectCount(int count) {
        indexedProjectCount.set(count);
    }

    /**
     * Track active concurrent queries.
     */
    public void queryStarted() {
        activeQueryCount.incrementAndGet();
    }

    public void queryCompleted() {
        activeQueryCount.decrementAndGet();
    }

    /**
     * Get current metrics snapshot (for health endpoint).
     */
    public Map<String, Object> getSnapshot() {
        return Map.of(
                "totalQueries", (long) queryCounter.count(),
                "totalEmbeddings", (long) embeddingCounter.count(),
                "totalPRAnalyses", (long) prAnalysisCounter.count(),
                "totalErrors", (long) errorCounter.count(),
                "activeQueries", activeQueryCount.get(),
                "indexedProjects", indexedProjectCount.get(),
                "queryP95Ms", (long) (queryTimer.percentile(0.95) / 1_000_000),
                "ollamaP95Ms", (long) (ollamaTimer.percentile(0.95) / 1_000_000));
    }
}
