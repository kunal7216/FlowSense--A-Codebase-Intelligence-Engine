package com.flowsense.graph;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MethodNodeRepository extends Neo4jRepository<MethodNode, Long> {

    List<MethodNode> findByClassNameAndProjectId(String className, String projectId);

    Optional<MethodNode> findBySignatureAndProjectId(String signature, String projectId);

    List<MethodNode> findByProjectId(String projectId);

    /**
     * Trace full call chain from an entry point (e.g. a REST endpoint method).
     * "What happens when /checkout is called?"
     */
    @Query("""
        MATCH path = (start:Method)-[:CALLS*1..8]->(end:Method)
        WHERE start.methodName = $methodName
        AND start.projectId = $projectId
        RETURN nodes(path) AS methods, relationships(path) AS calls
        LIMIT 50
        """)
    List<MethodNode> traceCallChain(@Param("methodName") String methodName,
                                     @Param("projectId") String projectId);

    /**
     * Find all methods that call a specific method.
     * "Who calls PaymentService.processPayment()?"
     */
    @Query("""
        MATCH (caller:Method)-[:CALLS]->(target:Method)
        WHERE target.methodName = $methodName
        AND target.className = $className
        AND target.projectId = $projectId
        RETURN caller
        """)
    List<MethodNode> findCallers(@Param("methodName") String methodName,
                                  @Param("className") String className,
                                  @Param("projectId") String projectId);

    /**
     * Find all database write operations.
     * "Where do we write to the payments DB?"
     */
    @Query("""
        MATCH (m:Method {projectId: $projectId})
        WHERE m.methodName =~ '(?i).*(save|update|delete|insert|persist|merge).*'
        OR m.className =~ '(?i).*(Repository|Dao|Mapper).*'
        RETURN m
        LIMIT 100
        """)
    List<MethodNode> findDatabaseWriteOperations(@Param("projectId") String projectId);

    /**
     * Find most complex methods (high cyclomatic complexity = tech debt).
     */
    @Query("""
        MATCH (m:Method {projectId: $projectId})
        WHERE m.cyclomaticComplexity > 5
        RETURN m
        ORDER BY m.cyclomaticComplexity DESC
        LIMIT 20
        """)
    List<MethodNode> findHighComplexityMethods(@Param("projectId") String projectId);

    void deleteByProjectId(String projectId);
}
