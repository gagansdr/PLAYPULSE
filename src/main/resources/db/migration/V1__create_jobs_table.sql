CREATE TABLE jobs (
    id            UUID PRIMARY KEY,
    type          VARCHAR(100) NOT NULL,
    payload       TEXT,
    priority      VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_status ON jobs (status);
