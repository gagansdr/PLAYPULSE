package com.playpulse.playpulse.fantasy;

import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * League rankings, kept in a Redis sorted set (member = team id, score = points) so "top 10" and "my rank"
 * are fast. Postgres (team_scores) is the source of truth; Redis is a read model that can always be rebuilt.
 */
@Service
public class LeaderboardService {

    public record Entry(long rank, long teamId, long points) {}

    static final String KEY_PREFIX = "playpulse:leaderboard:";

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public LeaderboardService(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    public static String key(long leagueId) {
        return KEY_PREFIX + leagueId;
    }

    /** Rewrites every team of the league from Postgres. ZADD sets absolute scores, so repeating it changes nothing. */
    public int rebuildLeague(long leagueId) {
        List<TypedTuple<String>> rows = jdbc.query(
                "select t.id, coalesce(ts.points, 0) from fantasy_teams t "
                        + "left join team_scores ts on ts.team_id = t.id where t.league_id = ?",
                (rs, i) -> (TypedTuple<String>) new DefaultTypedTuple<>(String.valueOf(rs.getLong(1)), (double) rs.getInt(2)),
                leagueId);
        if (rows.isEmpty()) {
            return 0;
        }
        Set<TypedTuple<String>> tuples = new HashSet<>(rows);
        redis.opsForZSet().add(key(leagueId), tuples);
        return tuples.size();
    }

    /** Best first. rank starts at 1. */
    public List<Entry> top(long leagueId, int n) {
        Set<TypedTuple<String>> range = redis.opsForZSet().reverseRangeWithScores(key(leagueId), 0, n - 1);
        List<Entry> entries = new java.util.ArrayList<>();
        if (range == null) {
            return entries;
        }
        long rank = 1;
        for (TypedTuple<String> t : range) {
            entries.add(new Entry(rank++, Long.parseLong(t.getValue()), t.getScore().longValue()));
        }
        return entries;
    }

    /** 1-based rank of a team in its league, or null if the league has no leaderboard yet. */
    public Long rankOf(long leagueId, long teamId) {
        Long zeroBased = redis.opsForZSet().reverseRank(key(leagueId), String.valueOf(teamId));
        return zeroBased == null ? null : zeroBased + 1;
    }
}
