package com.flowsense;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

@Slf4j
@EnableAsync
@EnableCaching
@SpringBootApplication
public class FlowSenseApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowSenseApplication.class, args);
        log.info("""
                
                ╔═══════════════════════════════════════════╗
                ║         FlowSense Pro is Running          ║
                ║                                           ║
                ║  API:     http://localhost:8080           ║
                ║  Neo4j:   http://localhost:7474           ║
                ║  Health:  http://localhost:8080/actuator  ║
                ╚═══════════════════════════════════════════╝
                """);
    }
}
