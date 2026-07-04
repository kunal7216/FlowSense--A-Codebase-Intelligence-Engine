package com.flowsense.dashboard;

import lombok.Builder;
import lombok.Data;

/**
 * Health metrics for a single Java service/class.
 */
@Data
@Builder
public class ServiceHealthMetric {

    private String className;
    private String fullyQualifiedName;
    private String filePath;

    // Debt score (0-100, higher = more debt)
    private int debtScore;
    private String riskLevel; // LOW / MEDIUM / HIGH / CRITICAL

    // Component scores (0-100)
    private int complexityScore; // Based on cyclomatic complexity
    private int couplingScore; // Based on number of dependents
    private int sizeScore; // Based on total lines
    private int apiSurfaceScore; // Based on public method ratio

    // Raw metrics
    private double avgComplexity;
    private int totalMethods;
    private int publicMethods;
    private int totalLines;
    private int directDependentCount;
    private int transitiveDependentCount;
    private int impactScore;

    // Actionable output
    private String recommendation;
    private int estimatedOnboardingDays;

    private boolean found;

    public static ServiceHealthMetric notFound(String className) {
        return ServiceHealthMetric.builder()
                .className(className)
                .found(false)
                .debtScore(0)
                .riskLevel("UNKNOWN")
                .recommendation("Class not found in indexed codebase")
                .build();
    }

    /**
     * Returns true if this service is a refactoring priority.
     */
    public boolean isHotspot() {
        return debtScore >= 60 || (couplingScore >= 70 && complexityScore >= 50);
    }
}
