package com.flowsense.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowsense.Kafka.PREventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Receives GitHub webhook events when PRs are opened/updated.
 *
 * SECURITY:
 * GitHub signs every webhook with HMAC-SHA256 using your secret.
 * We validate this signature before processing — prevents anyone
 * from faking GitHub events to trigger our analysis.
 *
 * INTERVIEW TALKING POINT:
 * "The webhook receiver validates GitHub's HMAC-SHA256 signature on
 * every request. Invalid signatures return 403 immediately — no
 * processing happens. After validation, I push to Kafka rather than
 * processing inline, which means the HTTP response returns in <50ms
 * and the PR analysis happens asynchronously. This is critical for
 * GitHub's 10-second webhook timeout."
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class GithubWebhookController {

    private final PREventProducer prEventProducer;
    private final ObjectMapper objectMapper;

    @Value("${flowsense.github.webhook-secret:flowsense-webhook-secret}")
    private String webhookSecret;

    /**
     * POST /webhook/github
     * GitHub sends events here on PR create/update/close.
     *
     * Setup in GitHub repo:
     * Settings → Webhooks → Add webhook
     * Payload URL: http://your-server/webhook/github
     * Content type: application/json
     * Secret: same as flowsense.github.webhook-secret
     * Events: Pull requests
     */
    @PostMapping("/github")
    public ResponseEntity<Map<String, String>> handleGitHubEvent(
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {

        log.info("GitHub webhook received: event={}", eventType);

        // ── STEP 1: Validate HMAC signature ───────────────────
        if (!validateSignature(payload, signature)) {
            log.warn("Invalid webhook signature — rejecting request");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid signature"));
        }

        // ── STEP 2: Only process PR events ────────────────────
        if (!"pull_request".equals(eventType)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "event", eventType));
        }

        try {
            // ── STEP 3: Parse the PR payload ──────────────────
            JsonNode root = objectMapper.readTree(payload);
            String action = root.path("action").asText();

            // Only analyze on open/synchronize (new commits pushed)
            if (!Set.of("opened", "synchronize", "reopened").contains(action)) {
                return ResponseEntity.ok(Map.of("status", "ignored", "action", action));
            }

            PREvent event = extractPREvent(root);
            log.info("PR event: repo={} pr=#{} action={}",
                    event.getRepoName(), event.getPrNumber(), action);

            // ── STEP 4: Push to Kafka (async processing) ──────
            // This returns immediately — analysis happens in background
            prEventProducer.publishPREvent(event);

            return ResponseEntity.accepted()
                    .body(Map.of(
                            "status", "accepted",
                            "message", "PR analysis queued",
                            "prNumber", String.valueOf(event.getPrNumber())));

        } catch (Exception e) {
            log.error("Failed to process webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────

    /**
     * Validate GitHub's HMAC-SHA256 signature.
     * GitHub sends: X-Hub-Signature-256: sha256=<hmac>
     *
     * INTERVIEW TALKING POINT:
     * "I use constant-time comparison (MessageDigest.isEqual) rather
     * than string equals to prevent timing attacks — an attacker can't
     * determine how much of the signature matched by measuring response time."
     */
    private boolean validateSignature(String payload, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) {
            log.warn("Missing or malformed signature header");
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);

            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computed = "sha256=" + bytesToHex(hmac);

            // Constant-time comparison — prevents timing attacks
            return java.security.MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            log.error("Signature validation error: {}", e.getMessage());
            return false;
        }
    }

    private PREvent extractPREvent(JsonNode root) {
        JsonNode pr = root.path("pull_request");
        JsonNode repo = root.path("repository");
        JsonNode head = pr.path("head");

        // Extract changed file paths from diff_url (simplified)
        List<String> changedFiles = new ArrayList<>();
        JsonNode files = root.path("pull_request").path("changed_files");

        return PREvent.builder()
                .prNumber(pr.path("number").asInt())
                .prTitle(pr.path("title").asText())
                .prUrl(pr.path("html_url").asText())
                .repoName(repo.path("full_name").asText())
                .repoCloneUrl(repo.path("clone_url").asText())
                .headSha(head.path("sha").asText())
                .headBranch(head.path("ref").asText())
                .baseBranch(pr.path("base").path("ref").asText())
                .authorLogin(pr.path("user").path("login").asText())
                .changedFiles(changedFiles)
                .filesChanged(files.asInt(0))
                .additions(pr.path("additions").asInt(0))
                .deletions(pr.path("deletions").asInt(0))
                .projectId(repo.path("name").asText()) // Use repo name as project ID
                .receivedAt(java.time.LocalDateTime.now())
                .build();
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
