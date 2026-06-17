package com.flowsense.prediction;

import com.flowsense.embedding.EmbeddingService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Stores and retrieves past production incidents.
 * Used by PRAnalysisService to find historical patterns.
 *
 * INTERVIEW TALKING POINT:
 * "When a PR modifies PaymentService, I search historical incidents
 * involving PaymentService using vector similarity — not keyword search.
 * This catches cases where the incident description says 'checkout
 * failure' rather than 'PaymentService error', because semantically
 * similar incidents have similar embeddings."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentHistoryService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    /**
     * Find past incidents similar to the current PR changes.
     * Uses vector similarity on incident descriptions.
     */
    public List<IncidentRecord> findSimilarIncidents(String prDescription, int limit) {
        try {
            float[] queryEmbedding = embeddingService.generateEmbedding(prDescription);
            String vectorStr = toVectorString(queryEmbedding);

            return jdbcTemplate.query("""
                    SELECT
                        id, title, description, severity, affected_services,
                        resolution, occurred_at,
                        1 - (embedding <=> ?::vector) AS similarity
                    FROM incident_history
                    WHERE 1 - (embedding <=> ?::vector) > 0.5
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """,
                    (rs, rowNum) -> IncidentRecord.builder()
                            .id(rs.getLong("id"))
                            .title(rs.getString("title"))
                            .description(rs.getString("description"))
                            .severity(rs.getInt("severity"))
                            .affectedServices(rs.getString("affected_services"))
                            .resolution(rs.getString("resolution"))
                            .similarityScore(rs.getDouble("similarity"))
                            .build(),
                    vectorStr, vectorStr, vectorStr, limit);

        } catch (Exception e) {
            log.warn("Incident history query failed (table may not exist yet): {}",
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Record a new incident (called when engineers report production issues).
     */
    public void recordIncident(String title, String description,
            int severity, String affectedServices) {
        try {
            float[] embedding = embeddingService.generateEmbedding(
                    title + " " + description + " " + affectedServices);
            String vectorStr = toVectorString(embedding);

            jdbcTemplate.update("""
                    INSERT INTO incident_history
                        (title, description, severity, affected_services, embedding, occurred_at)
                    VALUES (?, ?, ?, ?, ?::vector, NOW())
                    """,
                    title, description, severity, affectedServices, vectorStr);

            log.info("Incident recorded: {}", title);

        } catch (Exception e) {
            log.error("Failed to record incident: {}", e.getMessage());
        }
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0)
                sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    @Data
    @Builder
    public static class IncidentRecord {
        private long id;
        private String title;
        private String description;
        private int severity; // 1-5, 5 = critical outage
        private String affectedServices;
        private String resolution;
        private double similarityScore;
    }
}
