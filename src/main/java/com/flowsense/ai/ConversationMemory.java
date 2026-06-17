package com.flowsense.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages conversation history so follow-up questions work correctly.
 *
 * "What depends on PaymentService?"
 * "And what about OrderService?" ← needs to remember previous context
 *
 * Uses Redis for persistence (survives server restarts).
 * Falls back to in-memory if Redis is unavailable.
 *
 * INTERVIEW TALKING POINT:
 * "Conversation memory lets engineers ask follow-up questions naturally.
 * I store the last N turns in Redis with a session TTL — so a 30-minute
 * investigation session keeps its context, but old sessions don't
 * consume memory forever."
 */
@Slf4j
@Service
public class ConversationMemory {

    private final RedisTemplate<String, Object> redisTemplate;
    // Fallback in-memory store if Redis is down
    private final Map<String, List<GraphRAGEngine.ConversationTurn>> memoryStore = new ConcurrentHashMap<>();

    private static final int MAX_TURNS = 10; // Keep last 10 Q&A pairs
    private static final Duration SESSION_TTL = Duration.ofHours(2);
    private static final String KEY_PREFIX = "flowsense:conversation:";

    public ConversationMemory(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Get conversation history for a session.
     */
    @SuppressWarnings("unchecked")
    public List<GraphRAGEngine.ConversationTurn> getHistory(String sessionId) {
        try {
            Object stored = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
            if (stored instanceof List<?> list) {
                return (List<GraphRAGEngine.ConversationTurn>) list;
            }
        } catch (Exception e) {
            log.debug("Redis unavailable, using in-memory store");
            return memoryStore.getOrDefault(sessionId, new ArrayList<>());
        }
        return new ArrayList<>();
    }

    /**
     * Add a Q&A turn to the conversation history.
     */
    public void addTurn(String sessionId, String question, String answer) {
        List<GraphRAGEngine.ConversationTurn> history = getHistory(sessionId);

        history.add(new GraphRAGEngine.ConversationTurn(question, answer));

        // Keep only last MAX_TURNS to avoid context overflow
        if (history.size() > MAX_TURNS) {
            history = history.subList(history.size() - MAX_TURNS, history.size());
        }

        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, history, SESSION_TTL);
        } catch (Exception e) {
            memoryStore.put(sessionId, history);
        }
    }

    /**
     * Clear conversation history (start fresh).
     */
    public void clearSession(String sessionId) {
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            memoryStore.remove(sessionId);
        }
    }
}
