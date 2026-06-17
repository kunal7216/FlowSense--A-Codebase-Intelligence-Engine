package com.flowsense.webhook;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a GitHub Pull Request event received via webhook.
 * Serialized to JSON and sent through Kafka for async processing.
 */
@Data
@Builder
public class PREvent {
    private int prNumber;
    private String prTitle;
    private String prUrl;
    private String repoName;
    private String repoCloneUrl;
    private String headSha;
    private String headBranch;
    private String baseBranch;
    private String authorLogin;
    private List<String> changedFiles; // Will be populated by PRAnalysisService
    private int filesChanged;
    private int additions;
    private int deletions;
    private String projectId;
    private LocalDateTime receivedAt;
}
