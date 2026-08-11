# FlowSense -- A Codebase Intelligence Engine

**FlowSense is an AI-powered assistant that understands your Java codebase — so you can ask it questions in plain English, catch risky pull requests before they cause problems, and keep your documentation from going stale.**

Think of it like this: instead of digging through thousands of files to understand how a large Java project works, you can just *ask* FlowSense — "What are the most important classes in this project?" or "What breaks if I change this method?" — and get an answer grounded in your actual code, not a guess.


This README is written to be understandable even if you're new to some of the tools involved (Docker, Neo4j, vector databases, LLMs, etc.). Every technical term is explained the first time it's used.

---
## Table of Contents

1. [What Problem Does FlowSense Solve?](#what-problem-does-flowsense-solve)
2. [What Does FlowSense Actually Do?](#what-does-flowsense-actually-do)
3. [How It Works, In Plain English](#how-it-works-in-plain-english)
4. [Architecture](#architecture)
5. [Project Structure](#project-structure)
6. [Prerequisites](#prerequisites)
7. [Getting Started (Step by Step)](#getting-started-step-by-step)
8. [Using FlowSense](#using-flowsense)
9. [Service URLs & Credentials](#service-urls--credentials)
10. [Glossary](#glossary-of-terms-used-in-this-project)
11. [Troubleshooting](#troubleshooting)
12. [License](#license)


---

## What Problem Does FlowSense Solve?

Large Java codebases are hard to reason about. New developers spend weeks just figuring out how the code fits together. Reviewers approve pull requests without fully knowing what else in the system might be affected. Documentation is written once and never updated, so it slowly becomes wrong.


FlowSense tackles all three problems by building a live, queryable model of your codebase and layering AI on top of it:

- **Understanding code** → ask questions in plain English instead of reading every file.
- **Reviewing changes safely** → get an automatic risk assessment on every pull request.
- **Keeping docs honest** → get alerted the moment documentation and code drift apart.

## What Does FlowSense Actually Do?

Here's what each feature means in practice:

| Feature | What it means for you |
|---|---|
| **Code graph construction** | FlowSense reads your Java source code and builds a map (a "graph") showing every class, method, and how they call each other — like a wiring diagram for your codebase. |
| **Semantic code search** | You can search code by *meaning*, not just exact text. Searching "code that sends emails" can find relevant code even if the word "email" never appears literally. |
| **Natural-language Q&A** | Ask questions like "What are the most important classes in this project?" and get an answer generated from real analysis of your code — not a hallucinated guess. |
| **Conversation memory** | You can ask a follow-up question ("what about that first one specifically?") and FlowSense remembers what you were just talking about. |
| **PR risk prediction** | When someone opens a pull request on GitHub, FlowSense automatically reviews it, compares it against the history of past incidents, and posts a comment estimating how risky the change is. |
| **Living documentation** | FlowSense writes documentation for each service automatically, and tells you when that documentation no longer matches the code (called "drift"). |
| **Engineering health dashboard** | A running score of "technical debt" per service, so you can track whether your codebase is getting healthier or messier over time. |
| **Large-scale indexing** | Big codebases can be processed efficiently in batches instead of all at once. |
| **Built-in observability** | Pre-configured dashboards (Prometheus + Grafana) so you can monitor how FlowSense itself is performing. |

## How It Works, In Plain English

FlowSense uses a technique called **Graph RAG** (Retrieval-Augmented Generation using a graph). If you're new to AI terminology, here's the idea in five steps:

1. **Query decomposition** — Your question gets broken down into smaller, more specific pieces.
2. **Graph traversal** — FlowSense walks through the code graph (the class/method map mentioned above) to find structurally relevant pieces of code.
3. **Vector search** — At the same time, it searches for code that is *semantically* similar to your question, using AI-generated numerical representations of code called "embeddings."
4. **Context merging** — The results from steps 2 and 3 are combined into one coherent picture.
5. **Generation (with hallucination guarding)** — An AI model (running locally, not in the cloud) writes a natural-language answer, and a guard step checks that the answer is actually backed by the retrieved code before showing it to you.

The result: answers that are grounded in your real code, not invented.

## Architecture

FlowSense is built as a Spring Boot application backed by several supporting services, each doing one job well:

| Layer | Technology | What it's for (in plain terms) |
|---|---|---|
| Application | Spring Boot | The core Java application that ties everything together. |
| Graph database | Neo4j | Stores the "map" of your code (classes, methods, and how they connect). |
| Vector store | PostgreSQL + pgvector | Stores AI embeddings so FlowSense can search code by meaning. |
| Cache | Redis | Speeds things up by remembering recent results. |
| Messaging | Kafka + Zookeeper | Passes events (like "a new PR was opened") between parts of the system reliably. |
| Local LLM / embeddings | Ollama (`codellama:13b`, `nomic-embed-text`) | Runs the AI models on your own machine — your code never has to leave your network. |
| Batch processing | Spring Batch | Efficiently indexes very large codebases in manageable chunks. |
| Observability | Prometheus + Grafana | Lets you see metrics and dashboards about how FlowSense is running. |

All of these run as Docker containers except the Spring Boot app itself and Ollama, which you run directly on your machine.

## Project Structure

```
flowsense-complete/
├── pom.xml                          # Project dependencies (Maven configuration)
├── docker-compose.yml               # Spins up Neo4j, Postgres, Redis, Kafka, Zookeeper, Kafka UI, Prometheus, Grafana
├── Dockerfile                       # Production multi-stage build for FlowSense itself
├── docker/
│   ├── init.sql                     # Database schema: code_embeddings, projects, users, incident_history, pr_analyses, query_sessions
│   └── prometheus.yml               # Prometheus scrape configuration
├── benchmarks/
│   └── run-benchmarks.sh            # Performance test script
├── src/main/resources/
│   └── application.yml              # Configuration for Postgres, Neo4j, Redis, Kafka, Batch, Ollama
├── src/main/java/com/flowsense/
│   ├── FlowSenseApplication.java    # Main entry point — start here to see how the app boots
│   ├── ai/                          # The Graph RAG engine: query decomposition, context merging, hallucination guarding, conversation memory
│   ├── api/                         # REST controllers (the HTTP endpoints you call)
│   ├── batch/                       # Spring Batch job for indexing large codebases
│   ├── config/                      # Security, Redis, Kafka, AI, and startup health-check configuration
│   ├── dashboard/                   # Engineering health dashboard service and data models
│   ├── documentation/               # Documentation generation and drift detection logic
│   ├── embedding/                   # Service that generates AI embeddings for code
│   ├── graph/                       # Neo4j node models, repositories, graph builder, and query service
│   ├── kafka/                       # Producer/consumer for pull-request events
│   ├── model/                       # Data models for parsed classes, methods, fields, annotations
│   ├── monitoring/                  # Custom application metrics
│   ├── parser/                      # AST parser and codebase scanner (this is what reads your Java code)
│   ├── prediction/                  # PR risk analysis and incident history service
│   └── webhook/                     # GitHub webhook integration
└── src/test/java/com/flowsense/     # 25 tests covering parsing, risk scoring, drift detection, and debt scoring
```

> **New to Java projects?** `pom.xml` is like a `package.json` (Node.js) or `requirements.txt` (Python) — it lists everything the project depends on, and Maven uses it to build the project.

## Prerequisites
Before you start, make sure you have the following installed. Each one links to what it is and why it's needed.

| Requirement | Why you need it |
|---|---|
| **Java 17+** | FlowSense is written in Java and needs a compatible runtime to build and run. |
| **Maven** | The build tool used to compile the project and manage dependencies. |
| **Docker & Docker Compose** | Used to run all the supporting services (Neo4j, Postgres, Redis, Kafka, etc.) without installing them individually. |
| **Ollama** | Runs the AI models locally on your machine (no external API calls, no API keys needed). |
| **A Java project on disk** | You'll need some Java codebase to actually index and ask questions about — it can be any project, including a small personal one. |

If you're not sure whether something is installed, you can check from a terminal:

```bash
java -version
mvn -version
docker -version
docker compose version
ollama -v
```

If any of these commands fail, install that tool before continuing.

## Getting Started (Step by Step)

### Step 1 — Build the project

```bash
cd flowsense-complete
mvn clean compile
```
This downloads dependencies and compiles the Java source code. The first run may take a few minutes.

### Step 2 — Run the test suite (optional but recommended)

```bash
mvn test
```

These 25 tests run entirely offline — you don't need Neo4j, Postgres, or Ollama running yet. This is a good way to confirm the project builds correctly on your machine before setting up infrastructure.

### Step 3 — Pull the required AI models

```bash
ollama pull codellama:13b       # ~7.4 GB — the language model used for answering questions
ollama pull nomic-embed-text    # ~274 MB — the model used to generate code embeddings
```

> **Tip:** `codellama:13b` is a fairly large download. Make sure you have a stable connection and enough disk space (at least ~10 GB free) before running this.

### Step 4 — Start the supporting infrastructure

```bash
docker-compose up -d
docker ps
```

The `-d` flag runs everything in the background. After running `docker ps`, you should see **eight** running containers:

- `neo4j`
- `postgres`
- `redis`
- `zookeeper`
- `kafka`
- `kafka-ui`
- `prometheus`
- `grafana`

If you see fewer than eight, check the [Troubleshooting](#troubleshooting) section below or run `docker-compose logs` to see what failed.

### Step 5 — Start Ollama

```bash
ollama serve
```

Leave this running in its own terminal window — it's the process that serves the AI models FlowSense will call.

### Step 6 — Run FlowSense

In a new terminal:

```bash
mvn spring-boot:run
```

Watch the startup logs for these four confirmation lines. If you see all four, everything is wired up correctly:

```
✅ PostgreSQL + pgvector: Connected
✅ Neo4j: Connected
✅ Redis: Connected
✅ Ollama (nomic-embed-text): Connected and running
```

## Using FlowSense

### Step 7 — Index a codebase

Tell FlowSense which project to analyze by sending a request to its indexing endpoint:

```http
POST http://localhost:8080/api/projects/index
Content-Type: application/json

{
  "projectId": "test",
  "projectPath": "C:/path/to/any/java/project"
}
```

`projectPath` should point to a real Java project on your disk. This is what FlowSense will parse and build a graph from.

> **New to APIs?** You can send this request using a tool like [Postman](https://www.postman.com/), [Insomnia](https://insomnia.rest/), or a simple `curl` command from your terminal — you don't need to write any code to try this out.

### Step 8 — Ask your first question

Once indexing finishes, ask a question about the codebase:

```http
POST http://localhost:8080/api/query/test
Content-Type: application/json

{
  "question": "What are the most important classes in this project?",
  "sessionId": "s1"
}
```

`sessionId` groups your questions into a conversation, so you can ask follow-up questions and FlowSense will remember the context.

## Service URLs & Credentials

Once everything is running, these are the dashboards and tools available to you:

| Service | URL | Credentials |
|---|---|---|
| FlowSense API | http://localhost:8080 | — |
| Neo4j Browser (view the code graph visually) | http://localhost:7474 | `neo4j` / `flowsense123` |
| Kafka UI (inspect messages/events) | http://localhost:8090 | — |
| Prometheus (raw metrics) | http://localhost:9090 | — |
| Grafana (metrics dashboards) | http://localhost:3000 | `admin` / `admin` |

> **Security note:** These default credentials are meant for local development only. Change them before deploying anywhere beyond your own machine.

## Glossary of Terms Used in This Project

If you're new to some of the concepts FlowSense relies on, here's a quick reference:

- **Graph database** — A database that stores data as connected "nodes" and "relationships" (e.g., "Class A calls Method B"), instead of rows and tables. Neo4j is a popular graph database.
- **Embedding** — A way of turning text or code into a list of numbers that captures its *meaning*, so a computer can compare how similar two pieces of code are, even if the wording is different.
- **Vector search** — Searching by comparing embeddings (see above) instead of exact keyword matches. This is how FlowSense finds "similar" code.
- **RAG (Retrieval-Augmented Generation)** — An AI technique where the model first retrieves relevant information (in this case, from your code) and then uses that information to generate an accurate answer, instead of relying purely on what it memorized during training.
- **Hallucination guarding** — A safeguard that checks whether an AI-generated answer is actually supported by the retrieved information before showing it to the user, to avoid confidently wrong answers.
- **LLM (Large Language Model)** — The type of AI model (like `codellama:13b`) used to understand and generate natural language and code.
- **Webhook** — A way for one system (like GitHub) to automatically notify another system (FlowSense) the moment something happens, such as a pull request being opened.
- **AST (Abstract Syntax Tree)** — A structured, tree-like representation of source code that a program can analyze, used here to parse Java files.
- **Technical debt** — A metaphor for the extra work created by choosing an easy or quick solution now instead of a better approach that would take longer — FlowSense's dashboard tracks this over time.

## Troubleshooting

| Error | Likely Cause | Fix |
|---|---|---|
| `package org.springframework.X does not exist` | A required dependency is missing from `pom.xml`. | Add the correct Spring artifact for that package to `pom.xml`. |
| `cannot find symbol: method X in class Y` | The method name or arguments you're calling don't match how the method is actually defined. | Check the call site against the real method definition. |
| `incompatible types: X cannot be converted to Y` | A version mismatch against a dependency pinned in `pom.xml`. | Check the method signature for the exact dependency version listed in `pom.xml`. |
| Lombok errors (`cannot find symbol: method getX/setX`) | Your IDE's annotation processor isn't running, so Lombok-generated code (like getters/setters) doesn't exist yet. | In IntelliJ: **Settings → Build → Compiler → Annotation Processors → Enable**. |
| Fewer than 8 containers running after `docker-compose up -d` | One or more services failed to start (often a port conflict). | Run `docker-compose logs` to see which service failed and why. |
| Startup logs missing a ✅ connection line | The corresponding service (Postgres, Neo4j, Redis, or Ollama) isn't reachable. | Confirm that service is running (`docker ps` for infra, or check `ollama serve` is active) and that `application.yml` points to the right host/port. |

## License

Add your license of choice here (e.g., MIT, Apache 2.0).
