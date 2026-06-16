package com.flowsense.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for querying the Neo4j knowledge graph.
 * These queries are what powers the Q&A engine in Phase 2.
 *
 * All results are cached in Redis to avoid repeated graph traversals.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphQueryService {

    private final ClassNodeRepository classRepository;
    private final MethodNodeRepository methodRepository;

    /**
     * Get all dependencies of a class.
     * "What does PaymentService depend on?"
     */
    @Cacheable(value = "graph-queries", key = "#className + ':' + #projectId + ':deps'")
    public DependencyResult getDependencies(String className, String projectId) {
        log.debug("Getting dependencies for {} in project {}", className, projectId);

        Optional<ClassNode> classNode = classRepository
            .findByClassNameAndProjectId(className, projectId);

        if (classNode.isEmpty()) {
            return DependencyResult.notFound(className);
        }

        ClassNode node = classNode.get();

        // Get direct dependents (classes that depend ON this class)
        List<ClassNode> directDependents = classRepository
            .findDirectDependents(node.getFullyQualifiedName(), projectId);

        // Get transitive dependents (full impact)
        List<ClassNode> allDependents = classRepository
            .findAllTransitiveDependents(node.getFullyQualifiedName(), projectId);

        return DependencyResult.builder()
            .className(className)
            .fullyQualifiedName(node.getFullyQualifiedName())
            .directDependents(toNames(directDependents))
            .allTransitiveDependents(toNames(allDependents))
            .superClasses(toNames(node.getSuperClasses()))
            .interfaces(toNames(node.getImplementedInterfaces()))
            .impactScore(calculateImpactScore(directDependents, allDependents))
            .build();
    }

    /**
     * Find who calls a specific method.
     * "Who calls PaymentService.processPayment()?"
     */
    public List<String> getCallers(String className, String methodName, String projectId) {
        List<MethodNode> callers = methodRepository.findCallers(methodName, className, projectId);
        return callers.stream()
            .map(m -> m.getClassName() + "." + m.getMethodName())
            .collect(Collectors.toList());
    }

    /**
     * Trace the full call chain from an entry point.
     * "Trace everything that happens when checkout() is called"
     */
    public List<String> traceCallChain(String methodName, String projectId) {
        List<MethodNode> chain = methodRepository.traceCallChain(methodName, projectId);
        return chain.stream()
            .map(m -> m.getClassName() + "." + m.getMethodName()
                + " [" + m.getFullyQualifiedClassName() + ".java:" + m.getLineStart() + "]")
            .collect(Collectors.toList());
    }

    /**
     * Detect circular dependencies in the project.
     */
    public List<String> findCircularDependencies(String projectId) {
        return classRepository.findCircularDependencies(projectId);
    }

    /**
     * Find dead code — classes never called.
     */
    public List<String> findDeadCode(String projectId) {
        List<ClassNode> deadClasses = classRepository.findDeadCode(projectId);
        return deadClasses.stream()
            .map(c -> c.getFullyQualifiedName() + " (" + c.getFilePath() + ")")
            .collect(Collectors.toList());
    }

    /**
     * Find all DB write operations.
     */
    public List<String> findDatabaseWrites(String projectId) {
        List<MethodNode> writes = methodRepository.findDatabaseWriteOperations(projectId);
        return writes.stream()
            .map(m -> m.getClassName() + "." + m.getMethodName()
                + " [line " + m.getLineStart() + "]")
            .collect(Collectors.toList());
    }

    /**
     * Find most complex methods (tech debt indicators).
     */
    public List<ComplexityResult> findHighComplexityMethods(String projectId) {
        List<MethodNode> methods = methodRepository.findHighComplexityMethods(projectId);
        return methods.stream()
            .map(m -> ComplexityResult.builder()
                .className(m.getClassName())
                .methodName(m.getMethodName())
                .complexity(m.getCyclomaticComplexity())
                .location(m.getFullyQualifiedClassName() + ".java:" + m.getLineStart())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * Get project-level statistics.
     */
    public ProjectStats getProjectStats(String projectId) {
        List<ClassNode> allClasses = classRepository.findByProjectId(projectId);
        List<MethodNode> allMethods = methodRepository.findByProjectId(projectId);

        int totalComplexity = allMethods.stream()
            .mapToInt(MethodNode::getCyclomaticComplexity)
            .sum();

        long highComplexityCount = allMethods.stream()
            .filter(m -> m.getCyclomaticComplexity() > 10)
            .count();

        return ProjectStats.builder()
            .totalClasses(allClasses.size())
            .totalMethods(allMethods.size())
            .averageComplexity(allMethods.isEmpty() ? 0 :
                (double) totalComplexity / allMethods.size())
            .highComplexityMethods((int) highComplexityCount)
            .circularDependencies(findCircularDependencies(projectId).size())
            .deadCodeClasses(findDeadCode(projectId).size())
            .build();
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────

    private List<String> toNames(List<ClassNode> nodes) {
        if (nodes == null) return Collections.emptyList();
        return nodes.stream()
            .map(ClassNode::getFullyQualifiedName)
            .collect(Collectors.toList());
    }

    private int calculateImpactScore(List<ClassNode> direct, List<ClassNode> all) {
        // Simple impact score: 0-100 based on how many classes depend on this
        int directCount = direct.size();
        int totalCount = all.size();
        return Math.min(100, (directCount * 10) + (totalCount * 2));
    }

    // ── Result DTOs ───────────────────────────────────────────

    @lombok.Data
    @lombok.Builder
    public static class DependencyResult {
        private String className;
        private String fullyQualifiedName;
        private List<String> directDependents;
        private List<String> allTransitiveDependents;
        private List<String> superClasses;
        private List<String> interfaces;
        private int impactScore;
        private boolean found;

        static DependencyResult notFound(String className) {
            return DependencyResult.builder()
                .className(className)
                .found(false)
                .directDependents(Collections.emptyList())
                .allTransitiveDependents(Collections.emptyList())
                .superClasses(Collections.emptyList())
                .interfaces(Collections.emptyList())
                .build();
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class ComplexityResult {
        private String className;
        private String methodName;
        private int complexity;
        private String location;
    }

    @lombok.Data
    @lombok.Builder
    public static class ProjectStats {
        private int totalClasses;
        private int totalMethods;
        private double averageComplexity;
        private int highComplexityMethods;
        private int circularDependencies;
        private int deadCodeClasses;
    }
}
