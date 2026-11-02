CREATE TABLE IF NOT EXISTS plan_adoptions (
    target_plan_id VARCHAR(36) PRIMARY KEY REFERENCES plans(id) ON DELETE CASCADE,
    source_plan_id VARCHAR(36) NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    payload TEXT NOT NULL
);
