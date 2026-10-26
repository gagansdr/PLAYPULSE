package com.playpulse.playpulse.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Answers "which fantasy teams hold this player?" for a match event. */
@Component
public class AffectedTeamFinder {

    public record AffectedTeam(long teamId, long leagueId) {}

    private final JdbcTemplate jdbc;

    public AffectedTeamFinder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AffectedTeam> findByPlayer(long playerId) {
        return jdbc.query(
                "select t.id, t.league_id from fantasy_teams t "
                        + "join team_players tp on tp.team_id = t.id where tp.player_id = ? order by t.id",
                (rs, i) -> new AffectedTeam(rs.getLong(1), rs.getLong(2)),
                playerId);
    }
}
