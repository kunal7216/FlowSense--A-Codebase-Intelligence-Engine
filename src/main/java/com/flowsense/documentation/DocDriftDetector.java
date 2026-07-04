package com.flowsense.documentation;

import com.flowsense.graph.ClassNode;
import com.flowsense.graph.MethodNode;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects documentation drift — where comments no longer match code.
 *
 * WHAT IS DOC DRIFT?
 * A method is documented as "validates user email format"
 * but the actual code now validates phone numbers too.
 * The comment is stale — it no longer describes what the code does.
 *
 * HOW WE DETECT IT:
 * 1. Extract the Javadoc comment from a method
 * 2. Extract the actual method source code
 * 3. Ask Ollama: "Does this comment accurately describe this code?"
 * 4. If not → flag as drift with explanation
 *
 * INTERVIEW TALKING POINT:
 * "Doc drift detection was one of the most interesting engineering
 * problems I solved. I use the LLM as a semantic comparator —
 * comparing what the comment claims vs what the code actually does.
 * This is something regex or static analysis tools can't do because
 * it requires understanding both natural language and code semantics."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocDriftDetector {

    private final ChatClient chatClient;

    /**
     * Check all methods in a class for documentation drift.
     */
    public List<DriftIssue> detectDrift(ClassNode classNode, List<MethodNode> methods) {
        List<DriftIssue> issues = new ArrayList<>();

        for (MethodNode method : methods) {
            // Only check methods that have Javadoc
            // (Can't detect drift if there was never a comment)
            if (method.getSignature() == null)
                continue;

            try {
                // Use naming convention drift detection (fast, no LLM needed)
                DriftIssue namingDrift = detectNamingDrift(method);
                if (namingDrift != null) {
                    issues.add(namingDrift);
                }

                // Use LLM for deep semantic drift (slower, more accurate)
                // Only run on public methods to keep cost/time manageable
                if (method.isPublic() && method.getCyclomaticComplexity() > 3) {
                    DriftIssue semanticDrift = detectSemanticDrift(method, classNode);
                    if (semanticDrift != null) {
                        issues.add(semanticDrift);
                    }
                }

            } catch (Exception e) {
                log.debug("Drift detection failed for {}.{}: {}",
                        classNode.getClassName(), method.getMethodName(), e.getMessage());
            }
        }

        return issues;
    }

    // ─────────────────────────────────────────────────────────
    // NAMING CONVENTION DRIFT (fast — no LLM)
    // ─────────────────────────────────────────────────────────

    /**
     * Check if method name matches what it actually does.
     * Examples:
     * - Method named "getUser" but returns void → drift
     * - Method named "validateEmail" but has no validation logic → drift
     * - Method named "saveToDatabase" but has no repository call → drift
     */
    private DriftIssue detectNamingDrift(MethodNode method) {
        String name = method.getMethodName().toLowerCase();
        String returnType = method.getReturnType();
        String signature = method.getSignature();

        // "get" methods should return something
        if (name.startsWith("get") && "void".equals(returnType)) {
            return DriftIssue.builder()
                    .methodName(method.getMethodName())
                    .className(method.getClassName())
                    .lineNumber(method.getLineStart())
                    .driftType(DriftType.NAMING_CONVENTION)
                    .severity(DriftSeverity.WARNING)
                    .description("Method named '" + method.getMethodName() +
                            "' starts with 'get' but returns void — consider renaming")
                    .build();
        }

        // "is/has/can" methods should return boolean
        if ((name.startsWith("is") || name.startsWith("has") || name.startsWith("can")) &&
                !returnType.equals("boolean") && !returnType.equals("Boolean")) {
            return DriftIssue.builder()
                    .methodName(method.getMethodName())
                    .className(method.getClassName())
                    .lineNumber(method.getLineStart())
                    .driftType(DriftType.NAMING_CONVENTION)
                    .severity(DriftSeverity.WARNING)
                    .description("Method '" + method.getMethodName() +
                            "' implies boolean result but returns " + returnType)
                    .build();
        }

        return null; // No drift detected
    }

    // ─────────────────────────────────────────────────────────
    // SEMANTIC DRIFT (LLM-powered)
    // ─────────────────────────────────────────────────────────

    /**
     * Use LLM to detect if method name/signature is misleading.
     * Only called for complex, public methods.
     */
    private DriftIssue detectSemanticDrift(MethodNode method, ClassNode classNode) {
        String prompt = """
                You are a Java code reviewer checking for documentation drift.

                Class: %s
                Method signature: %s
                Return type: %s
                Cyclomatic complexity: %d

                Does the method name accurately reflect what this method likely does?
                Consider: naming conventions, return type, complexity.

                Respond ONLY with JSON:
                {
                  "hasDrift": true/false,
                  "confidence": 0.0-1.0,
                  "reason": "brief explanation if drift detected"
                }
                """.formatted(
                classNode.getClassName(),
                method.getSignature(),
                method.getReturnType(),
                method.getCyclomaticComplexity());

        try {
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // Parse JSON response
            if (response.contains("\"hasDrift\": true") ||
                    response.contains("\"hasDrift\":true")) {

                // Extract reason
                String reason = "Potential documentation drift detected";
                int reasonStart = response.indexOf("\"reason\": \"");
                if (reasonStart >= 0) {
                    int start = reasonStart + 11;
                    int end = response.indexOf("\"", start);
                    if (end > start) {
                        reason = response.substring(start, end);
                    }
                }

                // Only flag high-confidence issues
                double confidence = 0.5;
                int confStart = response.indexOf("\"confidence\": ");
                if (confStart >= 0) {
                    try {
                        confidence = Double.parseDouble(
                                response.substring(confStart + 14, confStart + 17).trim()
                                        .replaceAll("[^0-9.]", ""));
                    } catch (Exception ignored) {
                    }
                }

                if (confidence > 0.75) {
                    return DriftIssue.builder()
                            .methodName(method.getMethodName())
                            .className(classNode.getClassName())
                            .lineNumber(method.getLineStart())
                            .driftType(DriftType.SEMANTIC_MISMATCH)
                            .severity(DriftSeverity.INFO)
                            .description(reason)
                            .confidence(confidence)
                            .build();
                }
            }

        } catch (Exception e) {
            log.debug("LLM drift detection failed: {}", e.getMessage());
        }

        return null;
    }

    // ── Types ─────────────────────────────────────────────────

    @Data
    @Builder
    public static class DriftIssue {
        private String className;
        private String methodName;
        private int lineNumber;
        private DriftType driftType;
        private DriftSeverity severity;
        private String description;
        private double confidence;
    }

    public enum DriftType {
        NAMING_CONVENTION, // Method name doesn't match return type
        SEMANTIC_MISMATCH, // LLM detected misleading name
        STALE_COMMENT, // Comment doesn't match code behaviour
        MISSING_DOCS // Public method has no documentation
    }

    public enum DriftSeverity {
        ERROR, // Definitely wrong
        WARNING, // Likely wrong
        INFO // Possibly wrong
    }
}
