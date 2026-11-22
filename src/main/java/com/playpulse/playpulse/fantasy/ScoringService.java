package com.playpulse.playpulse.fantasy;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Applies the points of a match event to a fantasy team's total. Safe to call twice for the same (team, event). */
@Service
public class ScoringService {

    private final JdbcTemplate jdbc;

    public ScoringService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int pointsFor(String eventType) {
        try {
            return jdbc.queryForObject("select points from scoring_rules where event_type = ?", Integer.class, eventType);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException("No scoring rule for event type " + eventType);
        }
    }

    /**
     * Records the event's points for the team and adds them to the team's total, in one transaction.
     * Returns the points applied, or 0 if this (team, event) was already applied (a repeated job).
     */
    @Transactional
    public int applyEvent(long teamId, UUID eventId, String eventType) {
        int points = pointsFor(eventType);
        int inserted = jdbc.update(
                "insert into team_event_scores (team_id, event_id, points) values (?, ?, ?) on conflict do nothing",
                teamId, eventId, points);
        if (inserted == 0) {
            return 0;
        }
        jdbc.update("insert into team_scores (team_id, points, updated_at) values (?, ?, now()) "
                + "on conflict (team_id) do update set points = team_scores.points + excluded.points, updated_at = now()",
                teamId, points);
        return points;
    }
}
