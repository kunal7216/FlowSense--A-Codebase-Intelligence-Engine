package com.flowsense.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Neo4j node representing a Java class in the knowledge graph.
 *
 * INTERVIEW TALKING POINT:
 * "I chose Neo4j over a relational database for code relationships
 * because code IS a graph — classes extend other classes, implement
 * interfaces, and call each other's methods. Graph traversal in Neo4j
 * is O(log n) where a relational JOIN would be O(n²) for deep
 * dependency chains."
 */
@Data
@Node("Class")
public class ClassNode {

    @Id
    @GeneratedValue
    private Long id;

    @Property("className")
    private String className;

    @Property("packageName")
    private String packageName;

    @Property("fullyQualifiedName")
    private String fullyQualifiedName;

    @Property("filePath")
    private String filePath;

    @Property("classType")
    private String classType;         // CLASS, INTERFACE, ABSTRACT, ENUM

    @Property("isAbstract")
    private boolean isAbstract;

    @Property("projectId")
    private String projectId;

    @Property("lineStart")
    private int lineStart;

    @Property("lineEnd")
    private int lineEnd;

    @Property("totalLines")
    private int totalLines;

    @Property("totalMethods")
    private int totalMethods;

    // ── Relationships ───────────────────────────────────────

    @Relationship(type = "EXTENDS", direction = Relationship.Direction.OUTGOING)
    private List<ClassNode> superClasses = new ArrayList<>();

    @Relationship(type = "IMPLEMENTS", direction = Relationship.Direction.OUTGOING)
    private List<ClassNode> implementedInterfaces = new ArrayList<>();

    @Relationship(type = "HAS_METHOD", direction = Relationship.Direction.OUTGOING)
    private List<MethodNode> methods = new ArrayList<>();
}
