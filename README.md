# FlowSense Pro 
### AI-Powered Codebase Intelligence Engine

> *"I built a system that parses any Java codebase into a knowledge graph,
> answers architectural questions in natural language, and predicts
> production incident probability before code merges."*

---
## Phase 1 — Foundation (Weeks 1–4)

**What's built:** AST Parser → Neo4j Knowledge Graph → Semantic Search API

---

## Quick Start (10 minutes)

### Prerequisites
- Java 21
- Maven 3.8+
- Docker Desktop
- IntelliJ IDEA

---

### Step 1 — Install Ollama (FREE local AI)

Download from: https://ollama.com/download/windows

Open PowerShell and run:
```powershell
# Pull the models (do this tonight — takes time based on internet speed)
ollama pull codellama:13b       # 7.4GB — code intelligence model
ollama pull nomic-embed-text    # 274MB — embeddings model

# Start Ollama server
ollama serve

# Verify it's running
curl http://localhost:11434/api/tags
```

---

### Step 2 — Start Infrastructure

```powershell
# Clone/open this project in IntelliJ
# Then in terminal at project root:

docker-compose up -d

# Verify everything is running:
docker ps

# You should see:
# flowsense-neo4j    → running
# flowsense-postgres → running
# flowsense-redis    → running
```

**Open Neo4j Browser:** http://localhost:7474
- Username: `neo4j`
- Password: `flowsense123`

---

### Step 3 — Run FlowSense

```powershell
ollama serve
mvn spring-boot:run
```

Watch the startup logs — you'll see:
```
✅ PostgreSQL + pgvector: Connected
✅ Neo4j: Connected
✅ Redis: Connected
✅ Ollama (nomic-embed-text): Connected and running
✅ All systems operational!
```

---

### Step 4 — Index Your First Project

Open **Postman** and make this request:

```
POST http://localhost:8080/api/projects/index
Content-Type: application/json

{
  "projectId": "myproject",
  "projectPath": "C:/path/to/any/java/project"
}
```

Response:
```json
{
  "projectId": "myproject",
  "status": "SUCCESS",
  "filesProcessed": 47,
  "classesFound": 52,
  "methodsFound": 284,
  "nodesCreated": 52,
  "relationshipsCreated": 198,
  "embeddingsGenerated": 284
}
```

---

### Step 5 — Query the Graph

```
# Get all dependencies of a class
GET http://localhost:8080/api/graph/myproject/dependencies?class=PaymentService

# Find callers of a method
GET http://localhost:8080/api/graph/myproject/callers?class=PaymentService&method=processPayment

# Trace full execution chain
GET http://localhost:8080/api/graph/myproject/trace?method=checkout

# Find circular dependencies
GET http://localhost:8080/api/graph/myproject/circular-deps

# Find dead code
GET http://localhost:8080/api/graph/myproject/dead-code

# Semantic search
GET http://localhost:8080/api/search/myproject?q=payment+processing

# Project statistics
GET http://localhost:8080/api/graph/myproject/stats
```

---

### Step 6 — Explore in Neo4j Browser

Go to http://localhost:7474 and run these Cypher queries:

```cypher
// See all classes
MATCH (c:Class) RETURN c LIMIT 25

// See the dependency graph
MATCH (c:Class)-[r]->(d:Class) RETURN c, r, d LIMIT 50

// Find what depends on PaymentService
MATCH (c:Class)-[:HAS_METHOD]->(:Method)-[:CALLS]->(:Method)
      <-[:HAS_METHOD]-(target:Class)
WHERE target.className = 'PaymentService'
RETURN c

// Find most called methods
MATCH (:Method)-[:CALLS]->(m:Method)
RETURN m.className + '.' + m.methodName AS method, count(*) AS callCount
ORDER BY callCount DESC LIMIT 20
```

---

## Architecture (Phase 1)

```
Java Project
     │
     ▼
CodebaseScanner          ← walks all .java files
     │
     ▼
ASTParser                ← JavaParser extracts classes, methods, calls
     │
     ├──────────────────────────────────┐
     ▼                                  ▼
CodeGraphBuilder                  EmbeddingService
(Neo4j Knowledge Graph)           (pgvector embeddings)
     │                                  │
     ▼                                  ▼
ClassNode + MethodNode            nomic-embed-text (Ollama)
+ CALLS relationships             vector similarity search
     │                                  │
     └──────────────┬───────────────────┘
                    ▼
             REST API (Spring Boot)
             + Redis Cache
```

---

## Tech Stack

| Technology | Purpose | Why |
|---|---|---|
| Spring Boot 3.x | Core framework | Industry standard |
| JavaParser | AST parsing | Handles generics, lambdas correctly |
| Neo4j | Graph database | Code IS a graph — native traversal |
| pgvector | Vector embeddings | Runs in PostgreSQL — no extra infra |
| Ollama (nomic-embed-text) | Embeddings | FREE, local, no API key |
| Redis | Caching | Avoid recomputing same embeddings |
| Spring AI | LLM abstraction | Swap models with one config line |

---

## Running Tests

```powershell
# Run unit tests (no Docker needed)
mvn test -Dtest=ASTParserTest

# All tests
mvn test
```

---

## Coming in Phase 2 (Weeks 5–8)

- Natural language Q&A with Graph RAG
- "What happens when /checkout is called?" → full call chain answer
- GitHub webhook integration
- PR change impact prediction with risk score
---
