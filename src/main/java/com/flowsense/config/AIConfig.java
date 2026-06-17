package com.flowsense.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Spring AI configuration — wires Ollama as the LLM provider.
 *
 * SWAP TO OPENAI IN ONE LINE:
 * Comment out OllamaChatModel, inject OpenAiChatModel instead.
 * Zero code changes elsewhere — that's Spring AI's abstraction value.
 */
@Configuration
public class AIConfig {

    /**
     * ChatClient — the main interface for LLM interactions.
     * Spring AI injects OllamaChatModel automatically from application.yml config.
     */
    @Bean
    public ChatClient chatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel)
                .defaultSystem("""
                        You are FlowSense, an expert Java codebase analyst.
                        You answer questions about code structure, dependencies, and architecture.
                        Always be precise, technical, and cite specific file:line locations.
                        Never invent class or method names — only use what exists in the codebase.
                        """)
                .build();
    }

    /**
     * RestTemplate for GitHub API calls.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
