-- FlowSense Pro - PostgreSQL Init Script
-- Runs automatically when postgres container starts

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- ── Code Embeddings Table ─────────────────────────────────
-- Stores vector embeddings for every Java method
CREATE TABLE IF NOT EXISTS code_embeddings (
    id              BIGSERIAL PRIMARY KEY,
    project_id      VARCHAR(255) NOT NULL,
    file_path       VARCHAR(1000) NOT NULL,
    class_name      VARCHAR(500) NOT NULL,
    method_name     VARCHAR(500) NOT NULL,
    method_signature TEXT,
    source_code     TEXT,
    embedding       vector(768),          -- nomic-embed-text dimension
    line_start      INTEGER,
    line_end        INTEGER,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- ── Projects Table ────────────────────────────────────────
CREATE TABLE IF NOT EXISTS projects (
    id              VARCHAR(255) PRIMARY KEY,
    name            VARCHAR(500) NOT NULL,
    root_path       VARCHAR(1000) NOT NULL,
    total_files     INTEGER DEFAULT 0,
    total_methods   INTEGER DEFAULT 0,
    indexed_at      TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW()
);

-- ── Indexing Jobs Table ───────────────────────────────────
CREATE TABLE IF NOT EXISTS indexing_jobs (
    id              BIGSERIAL PRIMARY KEY,
    project_id      VARCHAR(255) NOT NULL,
    status          VARCHAR(50) DEFAULT 'PENDING',
    files_processed INTEGER DEFAULT 0,
    files_total     INTEGER DEFAULT 0,
    error_message   TEXT,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW()
);

-- ── Users Table (for JWT auth) ────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(255) UNIQUE NOT NULL,
    password        VARCHAR(255) NOT NULL,
    role            VARCHAR(50) DEFAULT 'USER',
    created_at      TIMESTAMP DEFAULT NOW()
);

-- ── Indexes for performance ───────────────────────────────
CREATE INDEX IF NOT EXISTS idx_embeddings_project
    ON code_embeddings(project_id);

CREATE INDEX IF NOT EXISTS idx_embeddings_class
    ON code_embeddings(class_name);

-- pgvector HNSW index for fast similarity search
CREATE INDEX IF NOT EXISTS idx_embeddings_vector
    ON code_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Insert default admin user (password: admin123 - bcrypt hashed)
INSERT INTO users (username, password, role)
VALUES ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ADMIN')
ON CONFLICT (username) DO NOTHING;

-- Done
SELECT 'FlowSense database initialized successfully' AS status;
