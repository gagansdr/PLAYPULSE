-- Minimal fantasy domain: a team belongs to a league and holds a set of real players.
-- A match event for player P fans out to every team that holds P.
CREATE TABLE fantasy_teams (
    id        BIGINT       PRIMARY KEY,
    league_id BIGINT       NOT NULL,
    name      VARCHAR(100) NOT NULL
);
CREATE INDEX idx_fantasy_teams_league ON fantasy_teams (league_id);

CREATE TABLE team_players (
    team_id   BIGINT NOT NULL REFERENCES fantasy_teams (id),
    player_id BIGINT NOT NULL,
    PRIMARY KEY (team_id, player_id)
);
-- "Which teams hold player P?" runs once per incoming event.
CREATE INDEX idx_team_players_player ON team_players (player_id);
