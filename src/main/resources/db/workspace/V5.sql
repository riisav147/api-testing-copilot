CREATE TABLE IF NOT EXISTS execution_contexts (
    execution_id VARCHAR(36) PRIMARY KEY REFERENCES executions(id) ON DELETE CASCADE,
    payload TEXT NOT NULL
);
