package com.playpulse.playpulse.fantasy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The notification outbox. A real push provider would read these rows later; for now the row IS the notification. */
@Service
public class NotificationService {

    public record Notification(UUID eventId, String message, Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final LeaderboardService leaderboard;

    public NotificationService(JdbcTemplate jdbc, LeaderboardService leaderboard) {
        this.jdbc = jdbc;
        this.leaderboard = leaderboard;
    }

    /**
     * Tells a team what the event did to it. Needs the team's points for the event and the league's leaderboard
     * to already exist, which the job graph guarantees (notify runs after recalc and leaderboard).
     * Returns false if this (team, event) was already notified.
     */
    public boolean notifyTeam(long teamId, long leagueId, UUID eventId, String eventType, long playerId) {
        Integer earned = jdbc.query("select points from team_event_scores where team_id = ? and event_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, teamId, eventId);
        if (earned == null) {
            throw new IllegalStateException("Team " + teamId + " has no score for event " + eventId + " yet");
        }
        Integer total = jdbc.queryForObject("select points from team_scores where team_id = ?", Integer.class, teamId);
        Long rank = leaderboard.rankOf(leagueId, teamId);

        String message = String.format("%s by player %d: %+d points. You now have %d points%s.",
                eventType, playerId, earned, total, rank == null ? "" : " and rank #" + rank + " in league " + leagueId);
        int inserted = jdbc.update("insert into notifications (id, team_id, event_id, message) "
                + "values (gen_random_uuid(), ?, ?, ?) on conflict (team_id, event_id) do nothing", teamId, eventId, message);
        return inserted == 1;
    }

    public List<Notification> forTeam(long teamId) {
        return jdbc.query("select event_id, message, created_at from notifications where team_id = ? order by created_at",
                (rs, i) -> new Notification(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant()),
                teamId);
    }
}
