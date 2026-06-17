package com.flowsense.prediction;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full impact report for a PR — posted as a GitHub comment.
 */
@Data
@Builder
public class PRImpactReport {
    private int prNumber;
    private String prTitle;
    private String prUrl;
    private String projectId;

    // Risk assessment
    private int riskScore;              // 0-100
    private String riskLevel;           // LOW / MEDIUM / HIGH / CRITICAL
    private String riskExplanation;     // LLM-generated explanation

    // Impact analysis
    private List<String> changedClasses;
    private List<String> directlyImpactedServices;
    private List<String> transitivelyImpactedServices;
    private int totalServicesImpacted;

    // Risk signals (for transparency)
    private double historicalRiskSignal;
    private double complexityRiskSignal;
    private double coverageGapSignal;

    // Recommendations
    private List<String> recommendedTests;

    private LocalDateTime analyzedAt;
}
