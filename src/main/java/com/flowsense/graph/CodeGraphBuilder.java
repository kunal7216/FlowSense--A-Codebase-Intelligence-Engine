package com.flowsense.graph;

import com.flowsense.model.ParsedClass;
import com.flowsense.model.ParsedMethod;
import com.flowsense.parser.CodebaseScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Converts parsed Java classes into a Neo4j knowledge graph.
 *
 * FLOW:
 * ParsedClass[] → ClassNode (Neo4j) + MethodNode (Neo4j) + CALLS edges
 *
 * INTERVIEW TALKING POINT:
 * "I build the graph in two passes. First pass creates all class
 * and method nodes. Second pass creates the CALLS relationships —
 * this two-pass approach is necessary because a method might call
 * another method defined later in the codebase."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeGraphBuilder {

    private final ClassNodeRepository classRepository;
    private final MethodNodeRepository methodRepository;

    /**
     * Build the complete Neo4j graph from scan results.
     *
     * @param scanResult Result from CodebaseScanner
     * @param projectId  Unique identifier for this project
     * @return GraphBuildResult with statistics
     */
    @Transactional
    public GraphBuildResult buildGraph(CodebaseScanner.ScanResult scanResult, String projectId) {
        log.info("Building graph for project {} with {} classes", projectId, scanResult.getTotalClasses());
        long startTime = System.currentTimeMillis();

        // Clear existing graph for this project (for re-indexing)
        clearProjectGraph(projectId);

        // ── PASS 1: Create all Class and Method nodes ─────────
        log.info("Pass 1: Creating nodes...");
        Map<String, ClassNode> classNodeMap = new HashMap<>();   // fqn → ClassNode
        Map<String, MethodNode> methodNodeMap = new HashMap<>();  // signature → MethodNode
        int nodesCreated = 0;

        for (ParsedClass parsedClass : scanResult.getClasses()) {
            try {
                // Create class node
                ClassNode classNode = createClassNode(parsedClass, projectId);

                // Create method nodes for this class
                List<MethodNode> methodNodes = new ArrayList<>();
                for (ParsedMethod method : parsedClass.getMethods()) {
                    MethodNode methodNode = createMethodNode(method, parsedClass, projectId);
                    methodNodes.add(methodNode);
                    String key = parsedClass.getFullyQualifiedName() + "." + method.getSignature();
                    methodNodeMap.put(key, methodNode);
                }

                classNode.setMethods(methodNodes);
                ClassNode saved = classRepository.save(classNode);

                classNodeMap.put(parsedClass.getFullyQualifiedName(), saved);
                nodesCreated++;

            } catch (Exception e) {
                log.warn("Failed to create node for class {}: {}",
                    parsedClass.getClassName(), e.getMessage());
            }
        }

        log.info("Pass 1 complete: {} class nodes created", nodesCreated);

        // ── PASS 2: Create CALLS relationships ────────────────
        log.info("Pass 2: Creating CALLS relationships...");
        int relationshipsCreated = 0;

        for (ParsedClass parsedClass : scanResult.getClasses()) {
            for (ParsedMethod method : parsedClass.getMethods()) {
                String callerKey = parsedClass.getFullyQualifiedName() + "." + method.getSignature();
                MethodNode callerNode = methodNodeMap.get(callerKey);

                if (callerNode == null) continue;

                for (ParsedMethod.MethodCall call : method.getMethodCalls()) {
                    // Try to find the callee method node
                    MethodNode calleeNode = findCalleeNode(call, methodNodeMap, projectId);

                    if (calleeNode != null) {
                        MethodNode.CallRelationship relationship = new MethodNode.CallRelationship();
                        relationship.setLineNumber(call.getLineNumber());
                        relationship.setCalleeObject(call.getCalleeObject());
                        relationship.setCallee(calleeNode);

                        if (callerNode.getCalls() == null) {
                            callerNode.setCalls(new ArrayList<>());
                        }
                        callerNode.getCalls().add(relationship);
                        relationshipsCreated++;
                    }
                }

                if (callerNode.getCalls() != null && !callerNode.getCalls().isEmpty()) {
                    methodRepository.save(callerNode);
                }
            }
        }

        // ── PASS 3: Create EXTENDS/IMPLEMENTS relationships ───
        log.info("Pass 3: Creating class hierarchy relationships...");
        for (ParsedClass parsedClass : scanResult.getClasses()) {
            ClassNode classNode = classNodeMap.get(parsedClass.getFullyQualifiedName());
            if (classNode == null) continue;

            // Link superclasses
            for (String superClass : parsedClass.getSuperClasses()) {
                ClassNode superNode = findClassBySimpleName(superClass, classNodeMap);
                if (superNode != null) {
                    classNode.getSuperClasses().add(superNode);
                }
            }

            // Link interfaces
            for (String iface : parsedClass.getInterfaces()) {
                ClassNode ifaceNode = findClassBySimpleName(iface, classNodeMap);
                if (ifaceNode != null) {
                    classNode.getImplementedInterfaces().add(ifaceNode);
                }
            }

            if (!classNode.getSuperClasses().isEmpty() ||
                !classNode.getImplementedInterfaces().isEmpty()) {
                classRepository.save(classNode);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Graph build complete: {} nodes, {} relationships in {}ms",
            nodesCreated, relationshipsCreated, duration);

        return GraphBuildResult.builder()
            .projectId(projectId)
            .nodesCreated(nodesCreated)
            .relationshipsCreated(relationshipsCreated)
            .buildDurationMs(duration)
            .build();
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────

    private ClassNode createClassNode(ParsedClass parsedClass, String projectId) {
        ClassNode node = new ClassNode();
        node.setClassName(parsedClass.getClassName());
        node.setPackageName(parsedClass.getPackageName());
        node.setFullyQualifiedName(parsedClass.getFullyQualifiedName());
        node.setFilePath(parsedClass.getFilePath());
        node.setClassType(parsedClass.getClassType().name());
        node.setAbstract(parsedClass.isAbstract());
        node.setProjectId(projectId);
        node.setLineStart(parsedClass.getLineStart());
        node.setLineEnd(parsedClass.getLineEnd());
        node.setTotalLines(parsedClass.getTotalLines());
        node.setTotalMethods(parsedClass.getMethods().size());
        return node;
    }

    private MethodNode createMethodNode(ParsedMethod method, ParsedClass parsedClass, String projectId) {
        MethodNode node = new MethodNode();
        node.setMethodName(method.getMethodName());
        node.setClassName(parsedClass.getClassName());
        node.setFullyQualifiedClassName(parsedClass.getFullyQualifiedName());
        node.setSignature(method.getSignature());
        node.setReturnType(method.getReturnType());
        node.setProjectId(projectId);
        node.setPublic(method.isPublic());
        node.setStatic(method.isStatic());
        node.setLineStart(method.getLineStart());
        node.setLineEnd(method.getLineEnd());
        node.setCyclomaticComplexity(method.getCyclomaticComplexity());
        node.setHasEmbedding(false);
        return node;
    }

    private MethodNode findCalleeNode(ParsedMethod.MethodCall call,
                                       Map<String, MethodNode> methodNodeMap,
                                       String projectId) {
        // Try with known callee class
        if (call.getCalleeClass() != null) {
            for (Map.Entry<String, MethodNode> entry : methodNodeMap.entrySet()) {
                if (entry.getValue().getClassName().equals(call.getCalleeClass()) &&
                    entry.getValue().getMethodName().equals(call.getCalleeMethod())) {
                    return entry.getValue();
                }
            }
        }

        // Try by method name alone (less precise but catches more)
        return methodNodeMap.values().stream()
            .filter(m -> m.getMethodName().equals(call.getCalleeMethod()))
            .findFirst()
            .orElse(null);
    }

    private ClassNode findClassBySimpleName(String simpleName, Map<String, ClassNode> classNodeMap) {
        return classNodeMap.values().stream()
            .filter(c -> c.getClassName().equals(simpleName))
            .findFirst()
            .orElse(null);
    }

    private void clearProjectGraph(String projectId) {
        log.info("Clearing existing graph for project: {}", projectId);
        methodRepository.deleteByProjectId(projectId);
        classRepository.deleteByProjectId(projectId);
    }

    @lombok.Data
    @lombok.Builder
    public static class GraphBuildResult {
        private String projectId;
        private int nodesCreated;
        private int relationshipsCreated;
        private long buildDurationMs;
    }
}
