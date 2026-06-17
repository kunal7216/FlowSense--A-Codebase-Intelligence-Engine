package com.flowsense.Kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowsense.webhook.PREvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes PR events to Kafka for async processing.
 *
 * WHY KAFKA AND NOT DIRECT PROCESSING?
 * GitHub webhooks have a 10-second timeout. PR analysis (AST re-parse
 * + graph update + LLM call) takes 20-40 seconds. Without Kafka,
 * GitHub would mark every webhook as failed.
 *
 * With Kafka:
 * Webhook → publish to Kafka (50ms) → return 202 Accepted
 * Kafka consumer → async analysis (30s) → post comment to GitHub
 *
 * INTERVIEW TALKING POINT:
 * "I used Kafka to decouple webhook receipt from PR analysis. The
 * webhook controller returns in <50ms — well within GitHub's 10s
 * timeout. The Kafka consumer processes asynchronously and posts
 * the analysis comment back via GitHub API when done. This also
 * gives us natural retry logic — if analysis fails, Kafka can
 * replay the event."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PREventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String TOPIC = "flowsense.pr-events";

    /**
     * Publish a PR event to Kafka.
     * Non-blocking — returns immediately.
     */
    public void publishPREvent(PREvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String key = event.getRepoName() + "#" + event.getPrNumber();

            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(TOPIC, key, payload);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish PR event to Kafka: {}", ex.getMessage());
                } else {
                    log.info("PR event published: topic={} partition={} offset={}",
                            TOPIC,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });

        } catch (Exception e) {
            log.error("Failed to serialize PR event: {}", e.getMessage());
        }
    }
}
