
# FlowSense 
### A Codebase Intelligence Engine for Java
Java 21

Spring Boot 3.x

License: MIT
> Parse any Java project into a high-fidelity knowledge graph. Ask complex architectural questions in plain English. Predict production incidents and pull request risks before they deploy—all running locally on your hardware.
> 
## 📖 Table of Contents
 1. What Is FlowSense Pro?
 2. The Problem It Solves
 3. System Architecture
 4. Core Features
 5. Tech Stack
 6. Quick Start
 7. API Reference
 8. Project Structure
 9. Deep Dive: Architecture Decisions
 10. Roadmap & License
## What Is FlowSense Pro?
**FlowSense Pro** is a production-grade backend system that transforms complex Java codebases into queryable semantic knowledge graphs. It uses state-of-the-art **Graph RAG (Retrieval-Augmented Generation)** to answer architectural questions, predict PR risk scores, generate living documentation, and track technical debt—without ever sending your sensitive source code to third-party cloud APIs.
Built using **Java 21, Spring Boot 3.x, Neo4j, pgvector, Apache Kafka, and Ollama**, it provides an enterprise-ready, completely air-gapped developer tool.
## The Problem It Solves
As engineering organizations scale past 10 microservices or several hundred thousand lines of code, they hit systemic, silent friction points:
 * **Invisible Blast Radii:** Changing a core library or model causes silent, cascading failures downstream.
 * **Knowledge Silos:** Onboarding new engineers takes weeks spent manually tracing execution flows.
 * **Stale Documentation:** Architecture documentation degrades the moment code is merged.
 * **Hidden Technical Debt:** Highly coupled "hotspot" classes remain invisible until they cause an outage.
FlowSense Pro brings these relationships into the light, providing deterministic graph tracing paired with LLM reasoning.
### Direct API Interaction Examples
**Indexing a Microservice:**
```http
POST /api/projects/index
Content-Type: application/json

{ 
  "projectId": "payments-service", 
  "projectPath": "C:/projects/payments" 
}

→ RESPONSE: 47 files processed · 284 methods indexed · graph built in 3.2s

```
**Context-Aware Structural Querying:**
```http
POST /api/query/payments-service
Content-Type: application/json

{ "question": "What breaks if I change PaymentService?" }

→ RESPONSE: "PaymentService [PaymentService.java:1] is directly depended on by
   OrderService [OrderService.java:15] and CheckoutController [CheckoutController.java:42]. 
   Transitively, NotificationService and AuditService are also affected — total impact score 68/100.
   Changes here require integration testing of at least 4 services."

```
## System Architecture
FlowSense Pro combines structural abstract syntax trees (AST) with deep vector embeddings to construct a dual-engine knowledge base.
```
Java Project
     │
     ▼
┌─────────────────────────────────────────────────────────┐
│                  Spring Boot Core Engine                 │
│                                                         │
│  JavaParser AST  →  CodebaseScanner  →  CodeGraphBuilder│
│  Spring AI       →  GraphRAGEngine   →  LLM Orchestrator│
│  Spring Batch    →  IndexingJob      →  EmbeddingService │
│  Spring Kafka    →  PREventConsumer  →  PRAnalysisService│
└──────┬─────────────────┬──────────────────┬─────────────┘
       │                 │                  │
  ┌────▼────┐    ┌───────▼──────┐   ┌───────▼───────┐
  │  Neo4j  │    │   pgvector   │   │  PostgreSQL   │
  │  Graph  │    │  Embeddings  │   │   Metadata    │
  │  DB     │    │  2M+ vectors │   │   Incidents   │
  └────┬────┘    └───────┬──────┘   └───────┬───────┘
       └─────────────────┴──────────────────┘
                         │
              ┌──────────▼───────────┐
              │    Graph RAG Engine   │
              │                      │
              │  1. Decompose query  │
              │  2. Traverse Neo4j   │
              │  3. Search pgvector  │
              │  4. Merge context    │
              │  5. Generate answer  │
              └──────────┬───────────┘
                         │
              ┌──────────▼───────────┐
              │   HallucinationGuard │ ← Validates structural integrity
              └──────────┬───────────┘
                         │
                  Streaming Answer
               with [file:line] citations

```
## Core Features
### 🏗️ Phase 1 — Foundation (Structural Indexing)
 * **AST Parser:** Utilizes JavaParser to extract granular syntactic data (classes, methods, signatures, annotations, call expressions) conforming to Java 21 features.
 * **Knowledge Graph Construction:** Maps entities out into a native **Neo4j** graph of nodes (Class, Method) connected by rich directional relationships (CALLS, EXTENDS, IMPLEMENTS).
 * **Method-Level Vectorization:** Generates isolated code embeddings using local nomic-embed-text models via **Ollama**, indexed natively inside **PostgreSQL (pgvector)** with HNSW indexes.
 * **Deterministic & Semantic Exploration:** Runs complex Cypher-based call-chain tracing and circular-dependency checks side-by-side with semantic similarity searches.
### 🧠 Phase 2 — Intelligence (Graph RAG Engine)
 * **Hybrid RAG Pipeline:** Combines explicit Neo4j structural graph traversals with pgvector similarity searches to inject precise architectural context into the LLM system prompt.
 * **HallucinationGuard:** Intercepts LLM completions, verifying all output class names and relationships against actual Neo4j records before streaming to the client.
 * **Event-Driven PR Profiling:** Consumes GitHub webhooks asynchronously via an internal **Apache Kafka** pipeline to rate PR risk based on cyclomatic complexity variations, code coupling, and past production incident vectors.
### 📊 Phase 3 — Production Systems
 * **Living Documentation Engine:** Auto-compiles markdown files and embeds self-updating **Mermaid.js** topology diagrams detailing cross-service dependencies.
 * **Technical Debt & Hotspot Dashboards:** Computes continuous complexity metrics using a weighted algorithm:
   
 * **Enterprise Operations:** Features chunk-based Spring Batch routines processing massive multi-gigabyte repositories smoothly, backed by robust Prometheus metrics and a native Grafana observability stack.
## Tech Stack
| Layer | Technology | Rationale |
|---|---|---|
| **Framework Engine** | Spring Boot 3.x / Java 21 | High-throughput baseline runtime, modern syntax support. |
| **AI Abstraction** | Spring AI & LangChain4j | Decoupled model orchestration allowing seamless model migration. |
| **Local Inference** | Ollama (codellama:13b) | Enterprise privacy; execution runs zero external network bills. |
| **Vector Indexing** | PostgreSQL 16 + pgvector | HNSW indexes kept alongside standard operational tables. |
| **Graph DB** | Neo4j Enterprise (Local) | Graph queries resolve natively in O(\log n) vs O(n^2) SQL joins. |
| **Asynchronous Bus** | Apache Kafka | Isolates heavy computing operations from HTTP webhook threads. |
| **Distributed Cache** | Redis 7 | Buffers embeddings, repetitive Cypher runs, and session memory. |
## Quick Start
### 1. Provision Local AI Models
Ensure Ollama is installed and active on your system.
```powershell
ollama pull codellama:13b
ollama pull nomic-embed-text

```
### 2. Stand Up Infrastructure Stacks
```powershell
git clone https://github.com/yourusername/flowsense-pro
cd flowsense-pro
docker-compose up -d

```
| Management Console | URL Endpoint | Default Credentials |
|---|---|---|
| **Neo4j Browser** | http://localhost:7474 | neo4j / password123 |
| **Kafka UI** | http://localhost:8090 | *None Required* |
| **Grafana Metrics** | http://localhost:3000 | admin / admin |
| **FlowSense App** | http://localhost:8080 | *Actuator Enabled* |
### 3. Build & Run Application Core
```powershell
ollama serve
mvn spring-boot:run

```
## API Reference
### Project Indexing
```bash
POST /api/projects/index
Content-Type: application/json

{
  "projectId": "core-ledger",
  "projectPath": "/absolute/path/to/java/source"
}

```
### Structural Queries (Sample Endpoints)
 * GET /api/graph/{projectId}/dependencies?class=ClassName — Identify dependency parents.
 * GET /api/graph/{projectId}/circular-deps — Pinpoint circular initialization paths.
 * GET /api/graph/{projectId}/dead-code — Locate unreachable structural methods.
### Graph RAG Q&A Interface
```bash
POST /api/query/{projectId}/stream
Content-Type: application/json

{
  "question": "Trace the execution pipeline from CheckoutController down to DB writes.",
  "sessionId": "usr-session-991"
}

```
## Project Structure
```
flowsense-pro/
├── src/main/java/com/flowsense/
│   ├── parser/         ← JavaParser AST extraction & project scanning
│   ├── graph/          ← Neo4j entity modeling and Cypher repositories
│   ├── embedding/      ← Ollama pgvector ingestion layer
│   ├── ai/             ← GraphRAG implementation & HallucinationGuard
│   ├── webhook/        ← Signature-verified GitHub event handlers
│   ├── kafka/          ← Async pipelines and ingestion message brokers
│   ├── prediction/     ← Code change risk metric scoring calculators
│   ├── documentation/  ← Markdown living docs & Mermaid generation
│   ├── dashboard/      ← Technical debt analysis engines
│   └── batch/          ← Spring Batch restartable processing pipelines
├── docker/             ← Bootstrapping scripts, init.sql schema files
├── docker-compose.yml  ← Orchestration blueprints for local runtime
└── README.md

```
## Why This Architecture?
### Why Neo4j Over Relational DBs for Code Trees?
Abstract syntax trees and cross-module tracking form highly complex, unpredictable networks. Performing deep transitive or cascading dependency evaluations via standard SQL requires nested relational tables and intensive, multi-layered JOIN queries that scale exponentially in performance costs (O(n^2)). Neo4j processes these relationships as explicit micro-pointers, handling deep 5-hop code traversals effortlessly in under 100 milliseconds (O(\log n)).
### Why Graph RAG Over Standard Vector RAG?
Standard RAG relies entirely on semantic or textual match similarities. If a developer asks *"What breaks if I adjust the access modifiers inside SecurityConfig?"*, a standard vector lookup simply fetches files containing words similar to "SecurityConfig". It completely misses downstream classes that rely structurally on that component but use different vocabularies. **Graph RAG bridges this gap:** it finds the exact structural code nodes via explicit Neo4j relationship maps and merges them with semantic vector matches to provide highly accurate, contextual source tracking.
## Roadmap
 * [ ] **IDE Native Extension:** Integration panels inside VS Code and IntelliJ IDEA.
 * [ ] **Multi-Language Adaptability:** Support for Python, Go, and TypeScript syntax parsers.
 * [ ] **Git Blame Integration:** Mapping organizational code ownership directly against commit patterns and structural graphs.
## License
Distributed under the MIT License. See LICENSE for details.
*Engineered by Developers, for Developers. Free to modify, private by design.*
