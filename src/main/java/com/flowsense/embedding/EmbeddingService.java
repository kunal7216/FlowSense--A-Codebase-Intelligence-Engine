package com.flowsense.embedding;

import com.flowsense.model.ParsedClass;
import com.flowsense.model.ParsedMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages code embeddings using Ollama's nomic-embed-text model (FREE).
 *
 * WHAT THIS DOES:
 * - Takes method source code as text
 * - Generates a 768-dimensional vector embedding via Ollama
 * - Stores in pgvector for similarity search
 *
 * INTERVIEW TALKING POINT:
 * "I embed method-level code rather than file-level because it gives
 * more precise semantic search. When you search 'payment processing',
 * you get the exact method, not a whole file where payment is mentioned
 * in one corner. I also cache embeddings in Redis — if a file hasn't
 * changed since last index, we reuse the stored embedding and skip
 * the Ollama call entirely."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;   // Injected by Spring AI (Ollama)
    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final int EMBEDDING_DIMENSION = 768;  // nomic-embed-text dimension
    private static final String CACHE_PREFIX = "embedding:";

    /**
     * Generate and store embeddings for all methods in a project.
     *
     * @param parsedClasses All parsed classes from the project
     * @param projectId     Project identifier
     * @return Number of embeddings generated
     */
    public int embedProject(List<ParsedClass> parsedClasses, String projectId) {
        log.info("Starting embedding generation for project {}", projectId);
        int count = 0;

        // Clear existing embeddings for this project
        jdbcTemplate.update(
            "DELETE FROM code_embeddings WHERE project_id = ?", projectId);

        // Process each class
        for (ParsedClass parsedClass : parsedClasses) {
            for (ParsedMethod method : parsedClass.getMethods()) {
                try {
                    // Generate embedding text (what we actually embed)
                    String embeddingText = buildEmbeddingText(parsedClass, method);

                    // Check Redis cache first
                    String cacheKey = CACHE_PREFIX + projectId + ":" +
                        parsedClass.getFullyQualifiedName() + "." + method.getSignature();

                    float[] embedding = getCachedEmbedding(cacheKey);

                    if (embedding == null) {
                        // Generate via Ollama (nomic-embed-text)
                        embedding = generateEmbedding(embeddingText);
                        cacheEmbedding(cacheKey, embedding);
                    }

                    // Store in pgvector
                    storeEmbedding(projectId, parsedClass, method, embeddingText, embedding);
                    count++;

                    if (count % 100 == 0) {
                        log.info("Embedded {} methods so far...", count);
                    }

                } catch (Exception e) {
                    log.warn("Failed to embed method {}.{}: {}",
                        parsedClass.getClassName(), method.getMethodName(), e.getMessage());
                }
            }
        }

        log.info("Embedding complete: {} methods embedded for project {}", count, projectId);
        return count;
    }

    /**
     * Generate embedding for a single text query.
     * Used at query time to embed the user's question.
     */
    public float[] generateEmbedding(String text) {
        try {
            // Spring AI handles the Ollama API call
            List<Double> rawEmbedding = embeddingModel.embed(text);
            float[] result = new float[rawEmbedding.size()];
            for (int i = 0; i < rawEmbedding.size(); i++) {
                result[i] = rawEmbedding.get(i).floatValue();
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to generate embedding: {}", e.getMessage());
            // Return zero vector as fallback (won't match anything — safe)
            return new float[EMBEDDING_DIMENSION];
        }
    }

    /**
     * Find methods semantically similar to a query.
     * "Find all methods related to payment processing"
     *
     * @param queryText Natural language query
     * @param projectId Project to search in
     * @param limit     Max results to return
     * @return List of similar methods with similarity scores
     */
    public List<SimilarMethod> findSimilarMethods(String queryText, String projectId, int limit) {
        log.debug("Semantic search: '{}' in project {}", queryText, projectId);

        float[] queryEmbedding = generateEmbedding(queryText);
        String vectorStr = toVectorString(queryEmbedding);

        String sql = """
            SELECT
                class_name,
                method_name,
                method_signature,
                file_path,
                line_start,
                line_end,
                source_code,
                1 - (embedding <=> ?::vector) AS similarity
            FROM code_embeddings
            WHERE project_id = ?
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;

        return jdbcTemplate.query(sql,
            (rs, rowNum) -> SimilarMethod.builder()
                .className(rs.getString("class_name"))
                .methodName(rs.getString("method_name"))
                .signature(rs.getString("method_signature"))
                .filePath(rs.getString("file_path"))
                .lineStart(rs.getInt("line_start"))
                .lineEnd(rs.getInt("line_end"))
                .sourceCode(rs.getString("source_code"))
                .similarityScore(rs.getDouble("similarity"))
                .citation(rs.getString("file_path") + ":" + rs.getInt("line_start"))
                .build(),
            vectorStr, projectId, vectorStr, limit);
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────

    /**
     * Build the text we actually embed for a method.
     * We include class context + method signature + body
     * for richer semantic matching.
     */
    private String buildEmbeddingText(ParsedClass parsedClass, ParsedMethod method) {
        StringBuilder sb = new StringBuilder();

        // Context: what class is this in?
        sb.append("Class: ").append(parsedClass.getFullyQualifiedName()).append("\n");

        // Javadoc if present
        if (method.getJavadoc() != null && !method.getJavadoc().isBlank()) {
            sb.append("Description: ").append(method.getJavadoc()).append("\n");
        }

        // Method signature
        sb.append("Method: ").append(method.getSignature()).append("\n");

        // Annotations (e.g. @Transactional tells us about DB operations)
        if (!method.getAnnotations().isEmpty()) {
            sb.append("Annotations: ")
              .append(String.join(", ", method.getAnnotations()))
              .append("\n");
        }

        // Source code (truncated to avoid token limits)
        if (method.getSourceCode() != null) {
            String code = method.getSourceCode();
            if (code.length() > 1000) {
                code = code.substring(0, 1000) + "...";
            }
            sb.append("Code:\n").append(code);
        }

        return sb.toString();
    }

    private void storeEmbedding(String projectId, ParsedClass parsedClass,
                                 ParsedMethod method, String text, float[] embedding) {
        String vectorStr = toVectorString(embedding);

        jdbcTemplate.update("""
            INSERT INTO code_embeddings
                (project_id, file_path, class_name, method_name, method_signature,
                 source_code, embedding, line_start, line_end)
            VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?, ?)
            """,
            projectId,
            parsedClass.getFilePath(),
            parsedClass.getClassName(),
            method.getMethodName(),
            method.getSignature(),
            method.getSourceCode(),
            vectorStr,
            method.getLineStart(),
            method.getLineEnd()
        );
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private float[] getCachedEmbedding(String cacheKey) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof float[] arr) return arr;
        } catch (Exception e) {
            // Cache miss or Redis down — continue without cache
        }
        return null;
    }

    private void cacheEmbedding(String cacheKey, float[] embedding) {
        try {
            redisTemplate.opsForValue().set(cacheKey, embedding, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Could not cache embedding: {}", e.getMessage());
        }
    }

    // ── Result DTO ────────────────────────────────────────────

    @lombok.Data
    @lombok.Builder
    public static class SimilarMethod {
        private String className;
        private String methodName;
        private String signature;
        private String filePath;
        private int lineStart;
        private int lineEnd;
        private String sourceCode;
        private double similarityScore;
        private String citation;          // "FileName.java:42" format
    }
}
