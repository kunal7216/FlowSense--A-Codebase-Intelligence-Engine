-- FlowSense Pro Phase 2 - PostgreSQL Init Script
-- Run automatically on first postgres container start

-- Enable pgvector
CREATE EXTENSION IF NOT EXISTS vector;

-- ── Phase 1 Tables (keep these) ──────────────────────────
CREATE TABLE IF NOT EXISTS code_embeddings (
    id              BIGSERIAL PRIMARY KEY,
    project_id      VARCHAR(255) NOT NULL,
    file_path       VARCHAR(1000) NOT NULL,
    class_name      VARCHAR(500) NOT NULL,
    method_name     VARCHAR(500) NOT NULL,
    method_signature TEXT,
    source_code     TEXT,
    embedding       vector(768),
    line_start      INTEGER,
    line_end        INTEGER,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS projects (
    id              VARCHAR(255) PRIMARY KEY,
    name            VARCHAR(500) NOT NULL,
    root_path       VARCHAR(1000) NOT NULL,
    total_files     INTEGER DEFAULT 0,
    total_methods   INTEGER DEFAULT 0,
    indexed_at      TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(255) UNIQUE NOT NULL,
    password        VARCHAR(255) NOT NULL,
    role            VARCHAR(50) DEFAULT 'USER',
    created_at      TIMESTAMP DEFAULT NOW()
);

-- ── Phase 2 NEW Tables ────────────────────────────────────

-- Stores past production incidents for historical risk matching
CREATE TABLE IF NOT EXISTS incident_history (
    id                  BIGSERIAL PRIMARY KEY,
    title               VARCHAR(500) NOT NULL,
    description         TEXT,
    severity            INTEGER CHECK (severity BETWEEN 1 AND 5),
    affected_services   TEXT,
    resolution          TEXT,
    embedding           vector(768),      -- Embedded for similarity search
    occurred_at         TIMESTAMP DEFAULT NOW(),
    created_at          TIMESTAMP DEFAULT NOW()
);

-- Stores PR analysis results
CREATE TABLE IF NOT EXISTS pr_analyses (
    id                      BIGSERIAL PRIMARY KEY,
    project_id              VARCHAR(255) NOT NULL,
    pr_number               INTEGER NOT NULL,
    pr_title                VARCHAR(500),
    pr_url                  VARCHAR(1000),
    risk_score              INTEGER,
    risk_level              VARCHAR(20),
    risk_explanation        TEXT,
    changed_classes         TEXT,         -- JSON array
    directly_impacted       TEXT,         -- JSON array
    transitively_impacted   TEXT,         -- JSON array
    recommended_tests       TEXT,         -- JSON array
    historical_risk_signal  DECIMAL(5,4),
    complexity_risk_signal  DECIMAL(5,4),
    coverage_gap_signal     DECIMAL(5,4),
    analyzed_at             TIMESTAMP DEFAULT NOW()
);

-- Stores Q&A sessions for analytics
CREATE TABLE IF NOT EXISTS query_sessions (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(255) NOT NULL,
    project_id      VARCHAR(255) NOT NULL,
    question        TEXT NOT NULL,
    answer          TEXT,
    intent          VARCHAR(50),
    citations       TEXT,               -- JSON array
    response_ms     INTEGER,
    created_at      TIMESTAMP DEFAULT NOW()
);

-- ── Indexes ───────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_embeddings_project
    ON code_embeddings(project_id);

CREATE INDEX IF NOT EXISTS idx_embeddings_class
    ON code_embeddings(class_name);

-- HNSW index for fast ANN search (approximate nearest neighbors)
CREATE INDEX IF NOT EXISTS idx_embeddings_vector
    ON code_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_incidents_vector
    ON incident_history
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_pr_analyses_project
    ON pr_analyses(project_id, pr_number);

CREATE INDEX IF NOT EXISTS idx_sessions_project
    ON query_sessions(project_id, created_at);

-- ── Seed data: sample incidents for testing ───────────────
INSERT INTO incident_history (title, description, severity, affected_services, resolution)
VALUES
    ('PaymentService timeout caused checkout failures',
     'High latency in processPayment() caused cascade timeouts in OrderService and CheckoutController. 503 errors for 15 minutes.',
     4, 'PaymentService, OrderService, CheckoutController',
     'Increased timeout config, added circuit breaker'),

    ('UserService NPE on null email field',
     'NullPointerException in validateUser() when email field was null. Affected login and registration flows.',
     3, 'UserService, AuthController',
     'Added null check, deployed hotfix'),

    ('Circular dependency between OrderService and InventoryService caused stack overflow',
     'Stack overflow on startup after refactor introduced circular dependency.',
     5, 'OrderService, InventoryService',
     'Extracted interface, broke circular dependency')
ON CONFLICT DO NOTHING;

-- Default admin user
INSERT INTO users (username, password, role)
VALUES ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ADMIN')
ON CONFLICT (username) DO NOTHING;

SELECT 'FlowSense Phase 2 database initialized' AS status;
