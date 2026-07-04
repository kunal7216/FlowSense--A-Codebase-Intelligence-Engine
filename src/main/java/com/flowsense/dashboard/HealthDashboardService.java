package com.flowsense.dashboard;

import com.flowsense.graph.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ╔══════════════════════════════════════════════════════════╗
 * ║ Engineering Health Dashboard — Phase 3 ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 * Provides real-time engineering health metrics across
 * the entire project. Used by tech leads and architects
 * to make data-driven refactoring decisions.
 *
 * METRICS PROVIDED:
 * - Technical debt score per service (0-100)
 * - Complexity trends over time
 * - Dependency health map
 * - Team ownership boundaries
 * - Most risky services to touch
 * - Onboarding time estimates
 *
 * INTERVIEW TALKING POINT:
 * "The health dashboard gives engineering leaders a single number
 * per service — the debt score — that combines cyclomatic complexity,
 * coupling (how many things depend on it), and cohesion (does it
 * do one thing). High debt + high coupling = dangerous to touch.
 * This is the kind of metric FAANG staff engineers use to prioritize
 * refactoring sprints."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthDashboardService {

    private final ClassNodeRepository classRepository;
    private final MethodNodeRepository methodRepository;
    private final GraphQueryService graphQueryService;

    /**
     * Generate full engineering health report for a project.
     * Cached for 30 minutes — expensive to compute.
     */
    @Cacheable(value = "health-dashboard", key = "#projectId")
    public HealthReport generateHealthReport(String projectId) {
        log.info("Generating health report for project: {}", projectId);
        long start = System.currentTimeMillis();

        List<ClassNode> allClasses = classRepository.findByProjectId(projectId);
        List<MethodNode> allMethods = methodRepository.findByProjectId(projectId);

        if (allClasses.isEmpty()) {
            return HealthReport.empty(projectId);
        }

        // ── Per-service metrics ────────────────────────────────
        List<ServiceHealthMetric> serviceMetrics = allClasses.stream()
                .map(cls -> computeServiceMetric(cls, allMethods, projectId))
                .sorted(Comparator.comparingInt(ServiceHealthMetric::getDebtScore).reversed())
                .collect(Collectors.toList());

        // ── Project-level aggregates ───────────────────────────
        double avgDebtScore = serviceMetrics.stream()
                .mapToInt(ServiceHealthMetric::getDebtScore)
                .average().orElse(0);

        int criticalServices = (int) serviceMetrics.stream()
                .filter(m -> m.getDebtScore() >= 75).count();

        int highRiskServices = (int) serviceMetrics.stream()
                .filter(m -> m.getDebtScore() >= 50).count();

        // ── Architecture metrics ───────────────────────────────
        List<String> circularDeps = graphQueryService.findCircularDependencies(projectId);
        List<String> deadCode = graphQueryService.findDeadCode(projectId);

        // ── Hotspots ──────────────────────────────────────────
        List<ServiceHealthMetric> hotspots = serviceMetrics.stream()
                .filter(m -> m.getDebtScore() >= 60)
                .limit(10)
                .collect(Collectors.toList());

        // ── Most stable services ───────────────────────────────
        List<ServiceHealthMetric> stable = serviceMetrics.stream()
                .filter(m -> m.getDebtScore() < 25)
                .limit(10)
                .collect(Collectors.toList());

        // ── Complexity distribution ────────────────────────────
        Map<String, Long> complexityDistribution = allMethods.stream()
                .collect(Collectors.groupingBy(
                        m -> {
                            int c = m.getCyclomaticComplexity();
                            if (c <= 2)
                                return "Simple (1-2)";
                            if (c <= 5)
                                return "Moderate (3-5)";
                            if (c <= 10)
                                return "Complex (6-10)";
                            return "Very Complex (10+)";
                        },
                        Collectors.counting()));

        long duration = System.currentTimeMillis() - start;

        return HealthReport.builder()
                .projectId(projectId)
                .generatedAt(LocalDateTime.now())
                .generationMs(duration)
                // Counts
                .totalServices(allClasses.size())
                .totalMethods(allMethods.size())
                .criticalServices(criticalServices)
                .highRiskServices(highRiskServices)
                // Scores
                .projectHealthScore(100 - (int) avgDebtScore)
                .avgDebtScore((int) avgDebtScore)
                // Architecture
                .circularDependencies(circularDeps)
                .deadCodeClasses(deadCode)
                .hasCircularDeps(!circularDeps.isEmpty())
                // Per-service
                .serviceMetrics(serviceMetrics)
                .hotspots(hotspots)
                .stableServices(stable)
                .complexityDistribution(complexityDistribution)
                // Summary
                .healthSummary(generateHealthSummary(
                        (int) avgDebtScore, criticalServices,
                        circularDeps.size(), deadCode.size()))
                .build();
    }

    /**
     * Get health metric for a single service.
     */
    public ServiceHealthMetric getServiceHealth(String className, String projectId) {
        Optional<ClassNode> classOpt = classRepository.findByClassNameAndProjectId(className, projectId);

        if (classOpt.isEmpty()) {
            return ServiceHealthMetric.notFound(className);
        }

        List<MethodNode> methods = methodRepository.findByClassNameAndProjectId(className, projectId);

        return computeServiceMetric(classOpt.get(), methods, projectId);
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE — Debt score computation
    // ─────────────────────────────────────────────────────────

    /**
     * Compute technical debt score for a service.
     *
     * DEBT SCORE FORMULA:
     * Component 1 (40%): Complexity — average cyclomatic complexity of methods
     * Component 2 (35%): Coupling — how many other services depend on this
     * Component 3 (15%): Size — total lines of code
     * Component 4 (10%): Stability — public method count vs total
     *
     * Score 0-100: 0=no debt, 100=critical debt
     *
     * INTERVIEW TALKING POINT:
     * "I use a weighted debt score that combines four dimensions.
     * The most important is coupling — a class with high complexity
     * but no dependents is safe to refactor. A class with low complexity
     * but 20 dependents is extremely risky to touch. Coupling amplifies
     * every other risk factor."
     */
    private ServiceHealthMetric computeServiceMetric(ClassNode classNode,
            List<MethodNode> allMethods,
            String projectId) {
        List<MethodNode> classMethods = allMethods.stream()
                .filter(m -> m.getClassName().equals(classNode.getClassName()))
                .collect(Collectors.toList());

        // Component 1: Complexity (0-100)
        double avgComplexity = classMethods.stream()
                .mapToInt(MethodNode::getCyclomaticComplexity)
                .average().orElse(1.0);
        int complexityScore = (int) Math.min(100, avgComplexity * 10);

        // Component 2: Coupling — dependents (0-100)
        GraphQueryService.DependencyResult deps = graphQueryService.getDependencies(classNode.getClassName(),
                projectId);
        int couplingScore = Math.min(100, deps.getDirectDependents().size() * 15);

        // Component 3: Size (0-100)
        int sizeScore = Math.min(100, classNode.getTotalLines() / 5);

        // Component 4: Public API surface (0-100)
        long publicCount = classMethods.stream().filter(MethodNode::isPublic).count();
        int apiScore = classMethods.isEmpty() ? 0 : (int) Math.min(100, (publicCount * 100.0 / classMethods.size()));

        // Weighted debt score
        int debtScore = (int) (complexityScore * 0.40 +
                couplingScore * 0.35 +
                sizeScore * 0.15 +
                apiScore * 0.10);

        // Risk level
        String riskLevel;
        if (debtScore >= 75)
            riskLevel = "CRITICAL";
        else if (debtScore >= 50)
            riskLevel = "HIGH";
        else if (debtScore >= 25)
            riskLevel = "MEDIUM";
        else
            riskLevel = "LOW";

        // Refactoring recommendation
        String recommendation = generateRecommendation(
                classNode.getClassName(), debtScore, complexityScore,
                couplingScore, deps.getDirectDependents().size());

        // Onboarding time estimate (days)
        int onboardingDays = Math.max(1,
                (int) ((avgComplexity * 0.3) + (classMethods.size() * 0.1) +
                        (deps.getDirectDependents().size() * 0.2)));

        return ServiceHealthMetric.builder()
                .className(classNode.getClassName())
                .fullyQualifiedName(classNode.getFullyQualifiedName())
                .filePath(classNode.getFilePath())
                .debtScore(debtScore)
                .riskLevel(riskLevel)
                .complexityScore(complexityScore)
                .couplingScore(couplingScore)
                .sizeScore(sizeScore)
                .apiSurfaceScore(apiScore)
                .avgComplexity(avgComplexity)
                .totalMethods(classMethods.size())
                .publicMethods((int) publicCount)
                .totalLines(classNode.getTotalLines())
                .directDependentCount(deps.getDirectDependents().size())
                .transitiveDependentCount(deps.getAllTransitiveDependents().size())
                .impactScore(deps.getImpactScore())
                .recommendation(recommendation)
                .estimatedOnboardingDays(onboardingDays)
                .found(true)
                .build();
    }

    private String generateRecommendation(String className, int debtScore,
            int complexityScore, int couplingScore,
            int dependentCount) {
        if (debtScore < 25)
            return "Healthy — no immediate action needed";
        if (debtScore < 50)
            return "Monitor — consider refactoring in next sprint";

        List<String> actions = new ArrayList<>();
        if (complexityScore > 60)
            actions.add("reduce method complexity (extract methods)");
        if (couplingScore > 60)
            actions.add("introduce interface to reduce coupling");
        if (dependentCount > 10)
            actions.add("consider splitting responsibilities");

        return "Action needed: " + String.join(", ", actions);
    }

    private String generateHealthSummary(int avgDebt, int critical,
            int circular, int dead) {
        if (avgDebt < 25 && critical == 0)
            return "Project is in good health. No immediate concerns.";
        if (avgDebt < 50)
            return String.format("Project needs attention: %d services have high debt. " +
                    "Focus on reducing coupling.", critical);
        return String.format("Project has significant technical debt: %d critical services, " +
                "%d circular dependencies, %d dead code classes. " +
                "Prioritize refactoring of top hotspots.", critical, circular, dead);
    }
}
