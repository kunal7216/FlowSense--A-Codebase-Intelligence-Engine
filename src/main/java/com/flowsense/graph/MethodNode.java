package com.flowsense.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Neo4j node representing a Java method in the knowledge graph.
 * Methods are connected by CALLS relationships — this is how we
 * trace execution paths and find dependencies.
 */
@Data
@Node("Method")
public class MethodNode {

    @Id
    @GeneratedValue
    private Long id;

    @Property("methodName")
    private String methodName;

    @Property("className")
    private String className;

    @Property("fullyQualifiedClassName")
    private String fullyQualifiedClassName;

    @Property("signature")
    private String signature;

    @Property("returnType")
    private String returnType;

    @Property("projectId")
    private String projectId;

    @Property("isPublic")
    private boolean isPublic;

    @Property("isStatic")
    private boolean isStatic;

    @Property("lineStart")
    private int lineStart;

    @Property("lineEnd")
    private int lineEnd;

    @Property("cyclomaticComplexity")
    private int cyclomaticComplexity;

    @Property("hasEmbedding")
    private boolean hasEmbedding;     // True once embedding is stored in pgvector

    // ── Relationships ───────────────────────────────────────

    /**
     * CALLS relationship: this method calls other methods.
     * This is the CORE of FlowSense's dependency graph.
     *
     * Query: MATCH (m:Method)-[:CALLS*]->(target:Method)
     * WHERE m.methodName = 'checkout'
     * RETURN target
     * → Finds everything checkout eventually calls
     */
    @Relationship(type = "CALLS", direction = Relationship.Direction.OUTGOING)
    private List<CallRelationship> calls = new ArrayList<>();

    @Data
    @RelationshipProperties
    public static class CallRelationship {
        @RelationshipId
        private Long id;

        @Property("lineNumber")
        private int lineNumber;

        @Property("calleeObject")
        private String calleeObject;

        @TargetNode
        private MethodNode callee;
    }
}
