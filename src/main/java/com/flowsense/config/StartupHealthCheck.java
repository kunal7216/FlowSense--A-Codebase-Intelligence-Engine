package com.flowsense.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs on startup to verify all connections are working.
 * Gives clear error messages if something isn't running.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupHealthCheck {

    private final Neo4jClient neo4jClient;
    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final EmbeddingModel embeddingModel;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Running startup health checks...");

        checkPostgres();
        checkNeo4j();
        checkRedis();
        checkOllama();

        log.info("""
                
                ✅ All systems operational!
                
                Try these API calls:
                
                1. Index a project:
                   POST http://localhost:8080/api/projects/index
                   Body: {"projectId":"test","projectPath":"C:/path/to/java/project"}
                
                2. Get dependencies:
                   GET http://localhost:8080/api/graph/test/dependencies?class=YourClassName
                
                3. Semantic search:
                   GET http://localhost:8080/api/search/test?q=payment+processing
                
                4. View graph visually:
                   Open http://localhost:7474 in browser
                   Username: neo4j / Password: flowsense123
                   Run: MATCH (c:Class) RETURN c LIMIT 25
                """);
    }

    private void checkPostgres() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            // Check pgvector is enabled
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
            log.info("✅ PostgreSQL + pgvector: Connected");
        } catch (Exception e) {
            log.error("❌ PostgreSQL: NOT CONNECTED — {}", e.getMessage());
            log.error("   → Run: docker-compose up -d postgres");
        }
    }

    private void checkNeo4j() {
        try {
            neo4jClient.query("RETURN 1").fetch().one();
            log.info("✅ Neo4j: Connected (Browser: http://localhost:7474)");
        } catch (Exception e) {
            log.error("❌ Neo4j: NOT CONNECTED — {}", e.getMessage());
            log.error("   → Run: docker-compose up -d neo4j");
        }
    }

    private void checkRedis() {
        try {
            redisTemplate.opsForValue().set("health-check", "ok");
            log.info("✅ Redis: Connected");
        } catch (Exception e) {
            log.error("❌ Redis: NOT CONNECTED — {}", e.getMessage());
            log.error("   → Run: docker-compose up -d redis");
        }
    }

    private void checkOllama() {
        try {
            // Quick test embedding
            embeddingModel.embed("test");
            log.info("✅ Ollama (nomic-embed-text): Connected and running");
        } catch (Exception e) {
            log.error("❌ Ollama: NOT RUNNING — {}", e.getMessage());
            log.error("   → Open PowerShell and run: ollama serve");
            log.error("   → Make sure you pulled: ollama pull nomic-embed-text");
            log.error("   → Embeddings will not work until Ollama is running");
        }
    }
}
