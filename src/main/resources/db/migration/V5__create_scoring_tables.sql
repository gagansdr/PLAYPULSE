-- How many fantasy points each kind of match event is worth.
CREATE TABLE scoring_rules (
    event_type VARCHAR(30) PRIMARY KEY,
    points     INT NOT NULL
);
INSERT INTO scoring_rules (event_type, points) VALUES
    ('GOAL', 6), ('ASSIST', 3), ('SAVE', 1), ('YELLOW_CARD', -1), ('RED_CARD', -3);

-- A team's running total.
CREATE TABLE team_scores (
    team_id    BIGINT      PRIMARY KEY REFERENCES fantasy_teams (id),
    points     INT         NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Points a team earned from one event. The primary key is the idempotency guard: if a recalculation job
-- runs twice for the same (team, event), the second insert is ignored and the total is not changed again.
CREATE TABLE team_event_scores (
    team_id    BIGINT      NOT NULL REFERENCES fantasy_teams (id),
    event_id   UUID        NOT NULL REFERENCES match_events (id),
    points     INT         NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, event_id)
);

-- The "push notification" outbox. UNIQUE (team, event) means a repeated job cannot notify twice.
CREATE TABLE notifications (
    id         UUID        PRIMARY KEY,
    team_id    BIGINT      NOT NULL REFERENCES fantasy_teams (id),
    event_id   UUID        NOT NULL REFERENCES match_events (id),
    message    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (team_id, event_id)
);
