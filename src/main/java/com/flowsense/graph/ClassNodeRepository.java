package com.flowsense.graph;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassNodeRepository extends Neo4jRepository<ClassNode, Long> {

    Optional<ClassNode> findByFullyQualifiedNameAndProjectId(String fqn, String projectId);

    List<ClassNode> findByProjectId(String projectId);

    Optional<ClassNode> findByClassNameAndProjectId(String className, String projectId);

    /**
     * Find all classes that directly depend on the given class.
     * INTERVIEW TALKING POINT: "This Cypher query finds reverse dependencies
     * in O(1) time regardless of graph size — impossible with SQL JOINs."
     */
    @Query("""
        MATCH (dependent:Class)-[:HAS_METHOD]->(:Method)-[:CALLS]->(:Method)<-[:HAS_METHOD]-(target:Class)
        WHERE target.fullyQualifiedName = $fqn AND target.projectId = $projectId
        RETURN DISTINCT dependent
        """)
    List<ClassNode> findDirectDependents(@Param("fqn") String fqn,
                                          @Param("projectId") String projectId);

    /**
     * Find ALL transitive dependents (classes that transitively depend on target).
     * This is used for PR impact prediction in Phase 2.
     */
    @Query("""
        MATCH path = (dependent:Class)-[:HAS_METHOD]->(:Method)-[:CALLS*1..10]->(:Method)
                     <-[:HAS_METHOD]-(target:Class)
        WHERE target.fullyQualifiedName = $fqn AND target.projectId = $projectId
        RETURN DISTINCT dependent
        LIMIT 100
        """)
    List<ClassNode> findAllTransitiveDependents(@Param("fqn") String fqn,
                                                 @Param("projectId") String projectId);

    /**
     * Detect circular dependencies in the project.
     */
    @Query("""
        MATCH path = (a:Class)-[:HAS_METHOD]->(:Method)-[:CALLS*1..5]->(:Method)
                     <-[:HAS_METHOD]-(b:Class)-[:HAS_METHOD]->(:Method)-[:CALLS*1..5]->(:Method)
                     <-[:HAS_METHOD]-(a)
        WHERE a.projectId = $projectId AND a <> b
        RETURN DISTINCT a.className + ' ↔ ' + b.className AS cycle
        LIMIT 20
        """)
    List<String> findCircularDependencies(@Param("projectId") String projectId);

    /**
     * Find dead code — classes with no incoming calls.
     */
    @Query("""
        MATCH (c:Class {projectId: $projectId})
        WHERE NOT exists {
            MATCH (:Method)-[:CALLS]->(:Method)<-[:HAS_METHOD]-(c)
        }
        AND NOT c.classType = 'INTERFACE'
        RETURN c
        LIMIT 50
        """)
    List<ClassNode> findDeadCode(@Param("projectId") String projectId);

    void deleteByProjectId(String projectId);
}
