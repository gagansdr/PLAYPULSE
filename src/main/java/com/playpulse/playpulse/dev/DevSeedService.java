package com.playpulse.playpulse.dev;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Dev-only: generates fantasy teams for event fan-out and wipes scores between experiments. */
@Service
@ConditionalOnProperty(name = "playpulse.dev-endpoints.enabled", havingValue = "true")
public class DevSeedService {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public DevSeedService(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    /** Clears notifications, per-event scores, team totals and the Redis leaderboards. Teams are kept. */
    @Transactional
    public void resetScores() {
        jdbc.update("delete from notifications");
        jdbc.update("delete from team_event_scores");
        jdbc.update("delete from team_scores");
        Set<String> keys = redis.keys("playpulse:leaderboard:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Transactional
    public int[] seed(int teams, int leagues, int players, int playersPerTeam) {
        resetScores();
        jdbc.update("delete from team_players");
        jdbc.update("delete from fantasy_teams");

        jdbc.batchUpdate("insert into fantasy_teams (id, league_id, name) values (?, ?, ?)",
                teamRows(teams, leagues), 1000, (ps, row) -> {
                    ps.setLong(1, row[0]);
                    ps.setLong(2, row[1]);
                    ps.setString(3, "Team " + row[0]);
                });

        // Fixed seed so a given (teams, players, playersPerTeam) always produces the same squads.
        Random random = new Random(42);
        List<Integer> pool = new ArrayList<>();
        for (int p = 1; p <= players; p++) {
            pool.add(p);
        }
        List<long[]> squadRows = new ArrayList<>();
        for (long team = 1; team <= teams; team++) {
            Collections.shuffle(pool, random);
            for (int i = 0; i < playersPerTeam; i++) {
                squadRows.add(new long[] {team, pool.get(i)});
            }
        }
        jdbc.batchUpdate("insert into team_players (team_id, player_id) values (?, ?)", squadRows, 1000, (ps, row) -> {
            ps.setLong(1, row[0]);
            ps.setLong(2, row[1]);
        });
        return new int[] {teams, squadRows.size()};
    }

    private static List<long[]> teamRows(int teams, int leagues) {
        List<long[]> rows = new ArrayList<>();
        for (long t = 1; t <= teams; t++) {
            rows.add(new long[] {t, (t % leagues) + 1});
        }
        return rows;
    }
}
