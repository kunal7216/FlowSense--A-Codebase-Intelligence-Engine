# FlowSense 

FlowSense is an AI-powered codebase intelligence platform for Java projects. It builds a live graph model of your codebase, answers natural-language questions about it, predicts the risk of incoming pull requests, keeps documentation in sync with the code, and gives your team an ongoing view of engineering health.

Under the hood, FlowSense combines static analysis, a graph database, vector search, and a local LLM to turn a codebase into something you can query, monitor, and reason about — not just browse.

---

## Features

- **Code graph construction** — parses Java source with JavaParser and builds a Neo4j graph of classes, methods, and call relationships.
- **Semantic code search** — generates embeddings for code and stores them in pgvector for similarity-based search.
- **Natural-language Q&A** — a Graph RAG pipeline (query decomposition → graph traversal → vector search → context merging → generation) with hallucination guarding, so you can ask questions like *"What are the most important classes in this project?"* and get grounded answers.
- **Conversation memory** — multi-turn Q&A sessions that retain context across questions.
- **PR risk prediction** — a GitHub webhook integration that analyzes incoming pull requests against historical incident data and comments directly on the PR with an impact assessment.
- **Living documentation** — auto-generated service-level documentation with drift detection that flags when docs fall out of sync with the actual code.
- **Engineering health dashboard** — per-service technical debt scoring to track codebase health over time.
- **Large-scale indexing** — Spring Batch support for indexing large codebases efficiently.
- **Built-in observability** — Prometheus metrics and Grafana dashboards out of the box.

---

## Architecture

| Layer | Technology |
|---|---|
| Application | Spring Boot |
| Graph database | Neo4j |
| Vector store | PostgreSQL + pgvector |
| Cache | Redis |
| Messaging | Kafka + Zookeeper |
| Local LLM / embeddings | Ollama (`codellama:13b`, `nomic-embed-text`) |
| Batch processing | Spring Batch |
| Observability | Prometheus + Grafana |

---

## Project Structure

```
flowsense-complete/
├── pom.xml                          # Project dependencies
├── docker-compose.yml               # Neo4j, Postgres, Redis, Kafka, Zookeeper, Kafka UI, Prometheus, Grafana
├── Dockerfile                       # Production multi-stage build
├── docker/
│   ├── init.sql                     # Schema: code_embeddings, projects, users, incident_history, pr_analyses, query_sessions
│   └── prometheus.yml               # Scrape config
├── benchmarks/
│   └── run-benchmarks.sh            # Performance test script
├── src/main/resources/
│   └── application.yml              # Postgres, Neo4j, Redis, Kafka, Batch, Ollama config
├── src/main/java/com/flowsense/
│   ├── FlowSenseApplication.java    # Main entry point
│   ├── ai/                          # Graph RAG engine, query decomposition, context merging, hallucination guarding, conversation memory
│   ├── api/                         # REST controllers
│   ├── batch/                       # Spring Batch indexing job
│   ├── config/                      # Security, Redis, Kafka, AI, and startup health check configuration
│   ├── dashboard/                   # Engineering health dashboard service and models
│   ├── documentation/               # Documentation generation and drift detection
│   ├── embedding/                   # Embedding generation service
│   ├── graph/                       # Neo4j node models, repositories, graph builder, and query service
│   ├── kafka/                       # PR event producer/consumer
│   ├── model/                       # Parsed class/method/field/annotation models
│   ├── monitoring/                  # Custom application metrics
│   ├── parser/                      # AST parser and codebase scanner
│   ├── prediction/                  # PR risk analysis and incident history service
│   └── webhook/                     # GitHub webhook integration
└── src/test/java/com/flowsense/     # 25 tests covering parsing, risk scoring, drift detection, and debt scoring
```

---

## Prerequisites

- Java 17+ and Maven
- Docker and Docker Compose
- [Ollama](https://ollama.com) installed locally
- A Java project on disk to index (for testing queries)

---

## Getting Started

### 1. Build the project

```powershell
cd flowsense-complete
mvn clean compile
```

### 2. Run the test suite

```powershell
mvn test
```
Tests run entirely offline — no Neo4j, Postgres, or Ollama required — and cover parsing, risk scoring, drift detection, and debt scoring.

### 3. Pull the required AI models

```powershell
ollama pull codellama:13b       # 7.4 GB
ollama pull nomic-embed-text    # 274 MB
```

### 4. Start infrastructure

```powershell
docker-compose up -d
docker ps
```

You should see eight running containers: `neo4j`, `postgres`, `redis`, `zookeeper`, `kafka`, `kafka-ui`, `prometheus`, `grafana`.

### 5. Start Ollama

```powershell
ollama serve
```

### 6. Run FlowSense

```powershell
mvn spring-boot:run
```

Confirm the startup logs show:

```
✅ PostgreSQL + pgvector: Connected
✅ Neo4j: Connected
✅ Redis: Connected
✅ Ollama (nomic-embed-text): Connected and running
```

### 7. Index a project and ask your first question

**Index a codebase:**

```bash
POST http://localhost:8080/api/projects/index
{ "projectId": "test", "projectPath": "C:/path/to/any/java/project" }
```

**Ask a question about it:**

```bash
POST http://localhost:8080/api/query/test
{ "question": "What are the most important classes in this project?", "sessionId": "s1" }
```

---

## Service URLs

| Service | URL | Credentials |
|---|---|---|
| FlowSense API | http://localhost:8080 | — |
| Neo4j Browser | http://localhost:7474 | `neo4j` / `flowsense123` |
| Kafka UI | http://localhost:8090 | — |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | `admin` / `admin` |

---

## Troubleshooting

| Error | Likely Cause | Fix |
|---|---|---|
| `package org.springframework.X does not exist` | Missing dependency in `pom.xml` | Add the correct artifact for that package |
| `cannot find symbol: method X in class Y` | Method name or argument mismatch | Check the call site against the method definition |
| `incompatible types: X cannot be converted to Y` | Version mismatch against a pinned dependency | Check the method signature for the dependency version in `pom.xml` |
| Lombok errors (`cannot find symbol: method getX/setX`) | Annotation processor not running | In IntelliJ: **Settings → Build → Compiler → Annotation Processors → Enable** |

---

## License

Add your license of choice here (e.g., MIT, Apache 2.0).
