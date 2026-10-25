-- How many parents of this job have not COMPLETED yet. A WAITING job is released when this hits 0.
ALTER TABLE jobs ADD COLUMN pending_dependencies INT NOT NULL DEFAULT 0;

-- One row per arrow in the job graph: job_id (child) cannot start until depends_on_job_id (parent) completes.
CREATE TABLE job_dependencies (
    job_id            UUID NOT NULL REFERENCES jobs (id),
    depends_on_job_id UUID NOT NULL REFERENCES jobs (id),
    PRIMARY KEY (job_id, depends_on_job_id),
    CHECK (job_id <> depends_on_job_id)
);

-- "Who are the children of parent X?" runs every time a job completes.
CREATE INDEX idx_job_deps_parent ON job_dependencies (depends_on_job_id);
