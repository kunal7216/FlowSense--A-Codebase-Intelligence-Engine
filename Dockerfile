# ── Stage 1: Build ───────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper and pom first (for layer caching)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies (cached unless pom.xml changes)
RUN ./mvnw dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Security: run as non-root
RUN addgroup -S flowsense && adduser -S flowsense -G flowsense
USER flowsense

# Copy built jar
COPY --from=builder /app/target/flowsense-core-*.jar app.jar

# Create logs directory
RUN mkdir -p logs

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

# JVM tuning for 16GB machine
ENTRYPOINT ["java",
    "-XX:+UseG1GC",
    "-XX:MaxRAMPercentage=75.0",
    "-XX:+HeapDumpOnOutOfMemoryError",
    "-XX:HeapDumpPath=/tmp/heapdump.hprof",
    "-Djava.security.egd=file:/dev/./urandom",
    "-jar", "app.jar"]
