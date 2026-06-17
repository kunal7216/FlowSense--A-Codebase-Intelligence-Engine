package com.flowsense.Kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowsense.prediction.PRAnalysisService;
import com.flowsense.prediction.PRImpactReport;
import com.flowsense.webhook.PREvent;
import com.flowsense.webhook.PRCommentPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * Consumes PR events from Kafka and triggers analysis.
 *
 * INTERVIEW TALKING POINT:
 * "I use manual acknowledgment (MANUAL_IMMEDIATE) so messages are
 * only committed to Kafka after successful processing. If analysis
 * fails midway, the message stays in Kafka and gets retried — this
 * gives us at-least-once delivery semantics. The trade-off is we
 * need idempotent processing, which I handle by checking if a PR
 * has already been analyzed before starting."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PREventConsumer {

    private final PRAnalysisService prAnalysisService;
    private final PRCommentPublisher commentPublisher;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "flowsense.pr-events", groupId = "flowsense-analysis-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumePREvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Consuming PR event: key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());

        try {
            // Deserialize the PR event
            PREvent event = objectMapper.readValue(record.value(), PREvent.class);

            log.info("Analyzing PR #{} in repo {}", event.getPrNumber(), event.getRepoName());
            long startTime = System.currentTimeMillis();

            // Run the full impact analysis
            PRImpactReport report = prAnalysisService.analyzePR(event);

            long duration = System.currentTimeMillis() - startTime;
            log.info("PR #{} analyzed in {}ms — risk score: {}",
                    event.getPrNumber(), duration, report.getRiskScore());

            // Post comment back to GitHub PR
            commentPublisher.postAnalysisComment(event, report);

            // Acknowledge — remove from Kafka queue
            ack.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process PR event: {}", e.getMessage(), e);
            // Don't acknowledge — Kafka will retry
            // In production: add retry limit + dead letter topic
        }
    }
}
