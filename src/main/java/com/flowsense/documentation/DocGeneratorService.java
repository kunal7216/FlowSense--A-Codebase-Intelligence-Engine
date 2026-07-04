package com.flowsense.documentation;

import com.flowsense.graph.ClassNodeRepository;
import com.flowsense.graph.GraphQueryService;
import com.flowsense.graph.MethodNodeRepository;
import com.flowsense.graph.ClassNode;
import com.flowsense.graph.MethodNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ╔══════════════════════════════════════════════════════════╗
 * ║ Living Documentation Engine — Phase 3 ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 * WHAT IT DOES:
 * Auto-generates human-readable documentation for any Java service
 * directly from the parsed codebase. Updates automatically when
 * code changes. Detects where comments contradict implementation.
 *
 * WHY IT'S DIFFERENT FROM JAVADOC:
 * - Javadoc describes WHAT a method does (from the comment)
 * - FlowSense docs explain WHY it exists (from call context + git history)
 * - Javadoc goes stale — FlowSense detects doc drift
 * - Javadoc is method-level — FlowSense is service-level architecture
 *
 * INTERVIEW TALKING POINT:
 * "The doc generator uses a two-pass approach. First pass extracts
 * structural facts from Neo4j — what the class does, what it depends
 * on, what depends on it. Second pass sends this structured context
 * to Ollama to generate readable prose. The LLM never invents
 * structural facts — it only formats what the graph provides.
 * This is the same ground-first principle from Phase 2."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocGeneratorService {

    private final ChatClient chatClient;
    private final ClassNodeRepository classRepository;
    private final MethodNodeRepository methodRepository;
    private final GraphQueryService graphQueryService;
    private final DocDriftDetector driftDetector;

    /**
     * Generate complete documentation for a single class.
     */
    public ServiceDoc generateClassDoc(String className, String projectId) {
        log.info("Generating docs for: {}", className);
        long start = System.currentTimeMillis();

        // ── PASS 1: Extract structural facts from graph ────────
        Optional<ClassNode> classNodeOpt = classRepository.findByClassNameAndProjectId(className, projectId);

        if (classNodeOpt.isEmpty()) {
            return ServiceDoc.notFound(className);
        }

        ClassNode classNode = classNodeOpt.get();
        List<MethodNode> methods = methodRepository.findByClassNameAndProjectId(className, projectId);

        // Get dependency context
        GraphQueryService.DependencyResult deps = graphQueryService.getDependencies(className, projectId);

        // Build structural summary (facts from graph — no LLM yet)
        StructuralSummary structural = buildStructuralSummary(classNode, methods, deps);

        // ── PASS 2: LLM generates readable prose from facts ───
        String overview = generateOverview(structural);
        String responsibilities = generateResponsibilities(structural);
        String integrationGuide = generateIntegrationGuide(structural);
        String onboardingNotes = generateOnboardingNotes(structural);

        // ── PASS 3: Detect doc drift ───────────────────────────
        List<DocDriftDetector.DriftIssue> driftIssues = driftDetector.detectDrift(classNode, methods);

        // ── GENERATE MERMAID DIAGRAM ───────────────────────────
        String diagram = generateMermaidDiagram(className, deps);

        // ── ESTIMATE ONBOARDING TIME ───────────────────────────
        int onboardingDays = estimateOnboardingDays(structural);

        long duration = System.currentTimeMillis() - start;
        log.info("Doc generated for {} in {}ms", className, duration);

        return ServiceDoc.builder()
                .className(className)
                .fullyQualifiedName(classNode.getFullyQualifiedName())
                .filePath(classNode.getFilePath())
                .classType(classNode.getClassType())
                .projectId(projectId)
                .overview(overview)
                .responsibilities(responsibilities)
                .integrationGuide(integrationGuide)
                .onboardingNotes(onboardingNotes)
                .publicMethods(structural.getPublicMethodSignatures())
                .directDependencies(deps.getSuperClasses())
                .usedBy(deps.getDirectDependents())
                .mermaidDiagram(diagram)
                .driftIssues(driftIssues)
                .hasDrift(!driftIssues.isEmpty())
                .estimatedOnboardingDays(onboardingDays)
                .totalMethods(methods.size())
                .publicMethodCount((int) methods.stream().filter(MethodNode::isPublic).count())
                .avgComplexity(methods.stream()
                        .mapToInt(MethodNode::getCyclomaticComplexity)
                        .average().orElse(0))
                .generatedAt(LocalDateTime.now())
                .generationMs(duration)
                .build();
    }

    /**
     * Generate documentation for all classes in a project.
     * Returns a full project documentation set.
     */
    public ProjectDocs generateProjectDocs(String projectId) {
        log.info("Generating full project docs for: {}", projectId);

        List<ClassNode> allClasses = classRepository.findByProjectId(projectId);
        List<ServiceDoc> docs = new ArrayList<>();

        for (ClassNode classNode : allClasses) {
            try {
                ServiceDoc doc = generateClassDoc(classNode.getClassName(), projectId);
                docs.add(doc);
            } catch (Exception e) {
                log.warn("Failed to generate doc for {}: {}", classNode.getClassName(), e.getMessage());
            }
        }

        // Project-level summary
        long totalDriftIssues = docs.stream()
                .mapToLong(d -> d.getDriftIssues().size())
                .sum();

        double avgOnboarding = docs.stream()
                .mapToInt(ServiceDoc::getEstimatedOnboardingDays)
                .average().orElse(0);

        return ProjectDocs.builder()
                .projectId(projectId)
                .serviceDocs(docs)
                .totalServices(docs.size())
                .servicesWithDrift((int) docs.stream().filter(ServiceDoc::isHasDrift).count())
                .totalDriftIssues((int) totalDriftIssues)
                .avgOnboardingDays(avgOnboarding)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE — Structural extraction
    // ─────────────────────────────────────────────────────────

    private StructuralSummary buildStructuralSummary(ClassNode classNode,
            List<MethodNode> methods,
            GraphQueryService.DependencyResult deps) {
        List<String> publicMethods = methods.stream()
                .filter(MethodNode::isPublic)
                .map(m -> m.getReturnType() + " " + m.getSignature())
                .collect(Collectors.toList());

        List<String> annotations = new ArrayList<>();
        // Infer role from class name patterns
        String role = inferClassRole(classNode.getClassName());

        return StructuralSummary.builder()
                .className(classNode.getClassName())
                .fullyQualifiedName(classNode.getFullyQualifiedName())
                .classType(classNode.getClassType())
                .role(role)
                .publicMethodSignatures(publicMethods)
                .totalMethods(methods.size())
                .directDependents(deps.getDirectDependents())
                .superClasses(deps.getSuperClasses())
                .interfaces(deps.getInterfaces())
                .impactScore(deps.getImpactScore())
                .avgComplexity(methods.stream()
                        .mapToInt(MethodNode::getCyclomaticComplexity)
                        .average().orElse(1.0))
                .build();
    }

    private String inferClassRole(String className) {
        if (className.endsWith("Controller"))
            return "REST Controller — handles HTTP requests";
        if (className.endsWith("Service"))
            return "Business Service — contains business logic";
        if (className.endsWith("Repository"))
            return "Data Repository — manages persistence";
        if (className.endsWith("Config"))
            return "Configuration — Spring configuration class";
        if (className.endsWith("Mapper"))
            return "Data Mapper — transforms between models";
        if (className.endsWith("Factory"))
            return "Factory — creates object instances";
        if (className.endsWith("Handler"))
            return "Handler — processes specific events/requests";
        if (className.endsWith("Validator"))
            return "Validator — validates input/business rules";
        if (className.endsWith("Consumer"))
            return "Kafka Consumer — processes async messages";
        if (className.endsWith("Producer"))
            return "Kafka Producer — publishes async messages";
        return "Component — general purpose class";
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE — LLM prose generation
    // ─────────────────────────────────────────────────────────

    private String generateOverview(StructuralSummary structural) {
        String prompt = """
                Generate a 2-3 sentence technical overview for this Java class.
                Be precise. No fluff. Write for a senior engineer onboarding to this codebase.

                Class: %s
                Type: %s
                Inferred Role: %s
                Public Methods: %s
                Used By: %s
                Depends On: %s
                Impact Score: %d/100

                Write ONLY the overview paragraph. No headers. No bullet points.
                """.formatted(
                structural.getClassName(),
                structural.getClassType(),
                structural.getRole(),
                structural.getPublicMethodSignatures().stream().limit(5).toList(),
                structural.getDirectDependents().stream().limit(3).toList(),
                structural.getSuperClasses(),
                structural.getImpactScore());

        return callLLM(prompt, "Overview not available — Ollama not running");
    }

    private String generateResponsibilities(StructuralSummary structural) {
        String prompt = """
                List the key responsibilities of this Java class as 3-5 bullet points.
                Base them ONLY on the method signatures provided.
                Each bullet: start with a verb. Max 15 words each.

                Class: %s
                Public Methods: %s

                Format: bullet list only. No intro sentence.
                """.formatted(
                structural.getClassName(),
                structural.getPublicMethodSignatures());

        return callLLM(prompt, "• Handles core business operations for " + structural.getClassName());
    }

    private String generateIntegrationGuide(StructuralSummary structural) {
        if (structural.getPublicMethodSignatures().isEmpty()) {
            return "No public methods — likely used via inheritance or reflection.";
        }

        String prompt = """
                Write a brief integration guide for using this Java class.
                Include: how to inject it, which methods to call, what to watch out for.
                Max 4 sentences. Be specific.

                Class: %s
                Role: %s
                Key Methods: %s
                Used By: %s
                """.formatted(
                structural.getClassName(),
                structural.getRole(),
                structural.getPublicMethodSignatures().stream().limit(3).toList(),
                structural.getDirectDependents().stream().limit(3).toList());

        return callLLM(prompt, "Inject via @Autowired. Call public methods as needed.");
    }

    private String generateOnboardingNotes(StructuralSummary structural) {
        String prompt = """
                Write 1-2 sentences of onboarding advice for a new engineer
                working with this class for the first time.
                Focus on: what to know before touching this, common pitfalls.

                Class: %s
                Impact Score: %d/100
                Complexity: %.1f average cyclomatic complexity
                Depended on by: %d services
                """.formatted(
                structural.getClassName(),
                structural.getImpactScore(),
                structural.getAvgComplexity(),
                structural.getDirectDependents().size());

        return callLLM(prompt,
                "High-impact class. Review all callers before making changes.");
    }

    private String callLLM(String prompt, String fallback) {
        try {
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("LLM call failed, using fallback: {}", e.getMessage());
            return fallback;
        }
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE — Mermaid diagram generation
    // ─────────────────────────────────────────────────────────

    /**
     * Generate a Mermaid class diagram showing dependencies.
     * Renders automatically in GitHub README and Confluence.
     */
    private String generateMermaidDiagram(String className,
            GraphQueryService.DependencyResult deps) {
        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\n");
        sb.append("graph TD\n");

        // Center node (the class itself)
        sb.append("    ").append(className)
                .append("[\"<b>").append(className).append("</b>\"]\n");

        // Style center node
        sb.append("    style ").append(className)
                .append(" fill:#4f8eff,color:#fff,stroke:#3070dd\n");

        // Superclasses (extends)
        for (String superClass : deps.getSuperClasses()) {
            sb.append("    ").append(superClass)
                    .append(" -->|extends| ").append(className).append("\n");
        }

        // Direct dependents (classes that USE this)
        int depCount = 0;
        for (String dependent : deps.getDirectDependents()) {
            if (depCount++ >= 5)
                break; // Cap at 5 for readability
            String safeId = dependent.replace(".", "_");
            sb.append("    ").append(safeId)
                    .append("[").append(dependent).append("]")
                    .append(" -->|uses| ").append(className).append("\n");
            sb.append("    style ").append(safeId)
                    .append(" fill:#10b981,color:#fff\n");
        }

        if (deps.getDirectDependents().size() > 5) {
            sb.append("    more[\"...").append(deps.getDirectDependents().size() - 5)
                    .append(" more\"]\n");
            sb.append("    more -->|uses| ").append(className).append("\n");
            sb.append("    style more fill:#64748b,color:#fff\n");
        }

        sb.append("```");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE — Onboarding time estimation
    // ─────────────────────────────────────────────────────────

    private int estimateOnboardingDays(StructuralSummary structural) {
        double score = 0;

        // More methods = more to understand
        score += structural.getTotalMethods() * 0.1;

        // Higher complexity = harder to understand
        score += structural.getAvgComplexity() * 0.3;

        // More dependents = need to understand more context
        score += structural.getDirectDependents().size() * 0.2;

        // High impact = need more care
        score += structural.getImpactScore() * 0.01;

        return Math.max(1, (int) Math.round(score));
    }

    // ─────────────────────────────────────────────────────────
    // INNER TYPES
    // ─────────────────────────────────────────────────────────

    @lombok.Data
    @lombok.Builder
    static class StructuralSummary {
        private String className;
        private String fullyQualifiedName;
        private String classType;
        private String role;
        private List<String> publicMethodSignatures;
        private int totalMethods;
        private List<String> directDependents;
        private List<String> superClasses;
        private List<String> interfaces;
        private int impactScore;
        private double avgComplexity;
    }
}
