package com.flowsense.dashboard;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Full engineering health report for a project.
 */
@Data
@Builder
public class HealthReport {

    private String projectId;
    private LocalDateTime generatedAt;
    private long generationMs;

    // Counts
    private int totalServices;
    private int totalMethods;
    private int criticalServices;
    private int highRiskServices;

    // Scores (0-100)
    private int projectHealthScore; // 100 = perfect health, 0 = critical
    private int avgDebtScore;

    // Architecture issues
    private List<String> circularDependencies;
    private List<String> deadCodeClasses;
    private boolean hasCircularDeps;

    // Per-service breakdown
    private List<ServiceHealthMetric> serviceMetrics;
    private List<ServiceHealthMetric> hotspots; // High debt services
    private List<ServiceHealthMetric> stableServices; // Low debt services

    // Distributions
    private Map<String, Long> complexityDistribution;

    // Summary
    private String healthSummary;

    public static HealthReport empty(String projectId) {
        return HealthReport.builder()
                .projectId(projectId)
                .generatedAt(LocalDateTime.now())
                .totalServices(0)
                .totalMethods(0)
                .projectHealthScore(100)
                .avgDebtScore(0)
                .circularDependencies(Collections.emptyList())
                .deadCodeClasses(Collections.emptyList())
                .serviceMetrics(Collections.emptyList())
                .hotspots(Collections.emptyList())
                .stableServices(Collections.emptyList())
                .complexityDistribution(Collections.emptyMap())
                .healthSummary("No classes indexed yet. Run POST /api/projects/index first.")
                .build();
    }
}
