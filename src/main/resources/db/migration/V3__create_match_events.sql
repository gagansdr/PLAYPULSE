-- The durable log of things that happened in a match. external_event_id is the id the feed gave us;
-- its UNIQUE constraint is what stops a re-delivered event from fanning out (and scoring) twice.
CREATE TABLE match_events (
    id                UUID PRIMARY KEY,
    external_event_id VARCHAR(100) NOT NULL UNIQUE,
    match_id          BIGINT       NOT NULL,
    type              VARCHAR(30)  NOT NULL,
    player_id         BIGINT       NOT NULL,
    match_minute      INT,
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Which event (if any) created a job, so "what did this goal trigger?" is one query.
ALTER TABLE jobs ADD COLUMN event_id UUID REFERENCES match_events (id);
CREATE INDEX idx_jobs_event ON jobs (event_id);
