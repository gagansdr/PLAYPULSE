package com.playpulse.playpulse.event;

import com.playpulse.playpulse.support.IntegrationTestBase;
import com.playpulse.playpulse.support.TestApi.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Areas 6 to 8: a match event is ingested, fans out into a job graph, and the real handlers score teams,
 * rebuild leaderboards and write notifications. Every result is checked against an independent SQL calculation.
 */
@TestPropertySource(properties = {
        "playpulse.worker.count=4",
        "playpulse.retry.max-attempts=3",
        "playpulse.retry.base-delay-ms=50",
        "playpulse.retry.max-delay-ms=200"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventFlowTest extends IntegrationTestBase {

    private static final long GOAL_PLAYER = 3;
    private static final long CARD_PLAYER = 4;

    @BeforeEach
    void seedTeams() {
        // 30 teams in 3 leagues; each team holds 4 of 10 players, so every player is held by several teams.
        Response seeded = api.post("/api/dev/seed?teams=30&leagues=3&players=10&playersPerTeam=4", null);
        assertThat(seeded.status()).isEqualTo(200);
    }

    private Response ingest(String eventId, String type, long playerId) {
        return api.post("/api/events", "{\"eventId\":\"" + eventId + "\",\"matchId\":12,\"type\":\"" + type
                + "\",\"playerId\":" + playerId + ",\"minute\":10}");
    }

    private int holders(long playerId) {
        return count("select count(*) from team_players where player_id = ?", playerId);
    }

    private int leaguesHolding(long playerId) {
        return count("select count(distinct t.league_id) from team_players tp join fantasy_teams t on t.id = tp.team_id "
                + "where tp.player_id = ?", playerId);
    }

    /** One event creates: a recalc per holder, a leaderboard per league, a notification per holder. */
    private int expectedJobs(long playerId) {
        return 2 * holders(playerId) + leaguesHolding(playerId);
    }

    private void ingestTheStandardMatch() {
        assertThat(ingest("feed-1", "GOAL", GOAL_PLAYER).status()).isEqualTo(202);
        assertThat(ingest("feed-2", "GOAL", GOAL_PLAYER).status()).isEqualTo(202);
        assertThat(ingest("feed-3", "RED_CARD", CARD_PLAYER).status()).isEqualTo(202);
        awaitAllJobsFinished();
    }

    @Test
    void anEventFansOutIntoOneRecalcPerHolderOneLeaderboardPerLeagueAndOneNotificationPerHolder() {
        assertThat(holders(GOAL_PLAYER)).isGreaterThan(1);   // guard: the scenario must not be vacuous

        Response response = ingest("feed-1", "GOAL", GOAL_PLAYER);

        assertThat(response.status()).isEqualTo(202);
        assertThat(response.map()).containsEntry("duplicate", false);
        assertThat(((Number) response.map().get("jobsCreated")).intValue()).isEqualTo(expectedJobs(GOAL_PLAYER));
        assertThat(count("select count(*) from jobs where type = 'FANTASY_RECALC'")).isEqualTo(holders(GOAL_PLAYER));
        assertThat(count("select count(*) from jobs where type = 'LEADERBOARD_UPDATE'")).isEqualTo(leaguesHolding(GOAL_PLAYER));
        assertThat(count("select count(*) from jobs where type = 'PUSH_NOTIFICATION'")).isEqualTo(holders(GOAL_PLAYER));
    }

    @Test
    void theWholeGraphCompletesAndNothingFinishesBeforeItsParents() {
        ingestTheStandardMatch();

        assertThat(count("select count(*) from jobs where status <> 'COMPLETED'")).isZero();
        assertThat(count("select count(*) from jobs where attempts <> 1")).as("jobs that needed a retry").isZero();
        int orderViolations = count("select count(*) from job_dependencies d "
                + "join jobs c on c.id = d.job_id join jobs p on p.id = d.depends_on_job_id where c.updated_at < p.updated_at");
        assertThat(orderViolations).isZero();
    }

    @Test
    void teamScoresMatchAnIndependentSqlCalculation() {
        ingestTheStandardMatch();

        // Two goals (+6 each) for player 3 and one red card (-3) for player 4.
        int wrongTeams = count("select count(*) from fantasy_teams t left join team_scores s on s.team_id = t.id "
                + "where coalesce(s.points, 0) <> 12 * (select count(*) from team_players p where p.team_id = t.id and p.player_id = ?) "
                + "- 3 * (select count(*) from team_players p where p.team_id = t.id and p.player_id = ?)", GOAL_PLAYER, CARD_PLAYER);

        assertThat(wrongTeams).isZero();
        assertThat(count("select count(*) from team_scores where points <> 0")).as("some teams must have scored").isPositive();
    }

    @Test
    void theRedisLeaderboardMatchesTheDatabaseRanking() {
        ingestTheStandardMatch();

        for (int league = 1; league <= 3; league++) {
            List<Long> fromApi = new ArrayList<>();
            for (Object entry : api.get("/api/leagues/" + league + "/leaderboard?top=5").list()) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> row = (java.util.Map<String, Object>) entry;
                fromApi.add(((Number) row.get("points")).longValue());
            }
            List<Long> fromSql = jdbc.queryForList("select coalesce(s.points, 0) from fantasy_teams t "
                    + "left join team_scores s on s.team_id = t.id where t.league_id = ? order by 1 desc limit 5", Long.class, league);

            assertThat(fromApi).as("league %d top 5", league).isEqualTo(fromSql);
        }
        // Every team of every league is on a leaderboard, even those with 0 points.
        assertThat(redis.opsForZSet().zCard("playpulse:leaderboard:1")).isEqualTo(count("select count(*) from fantasy_teams where league_id = 1"));
    }

    @Test
    void everyAffectedTeamGetsExactlyOneNotificationPerEventWithItsRank() {
        ingestTheStandardMatch();

        int expected = 2 * holders(GOAL_PLAYER) + holders(CARD_PLAYER);
        assertThat(count("select count(*) from notifications")).isEqualTo(expected);
        assertThat(count("select count(*) from (select 1 from notifications group by team_id, event_id having count(*) > 1) d")).isZero();

        Long teamId = jdbc.queryForObject("select team_id from notifications limit 1", Long.class);
        Response response = api.get("/api/teams/" + teamId + "/notifications");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.list()).isNotEmpty();
        assertThat(response.body()).contains("rank #");
    }

    @Test
    void reDeliveringAnEventReturns200CreatesNoJobsAndChangesNoScores() {
        ingest("feed-1", "GOAL", GOAL_PLAYER);
        awaitAllJobsFinished();
        int jobsBefore = count("select count(*) from jobs");
        long pointsBefore = jdbc.queryForObject("select coalesce(sum(points), 0) from team_scores", Long.class);

        Response again = ingest("feed-1", "GOAL", GOAL_PLAYER);

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.map()).containsEntry("duplicate", true);
        assertThat(((Number) again.map().get("jobsCreated")).intValue()).isEqualTo(jobsBefore);
        assertThat(count("select count(*) from match_events")).isEqualTo(1);
        assertThat(count("select count(*) from jobs")).isEqualTo(jobsBefore);
        assertThat(jdbc.queryForObject("select coalesce(sum(points), 0) from team_scores", Long.class)).isEqualTo(pointsBefore);
    }

    @Test
    void sixSimultaneousDeliveriesOfTheSameEventProduceOneEventAndOneSetOfJobs() {
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> ingest("feed-race", "GOAL", GOAL_PLAYER).status()));
        }
        List<Integer> statuses = calls.stream().map(CompletableFuture::join).sorted().toList();

        assertThat(statuses).containsExactly(200, 200, 200, 200, 200, 202);   // exactly one winner
        assertThat(count("select count(*) from match_events")).isEqualTo(1);
        assertThat(count("select count(*) from jobs")).isEqualTo(expectedJobs(GOAL_PLAYER));
    }

    @Test
    void anEventForAPlayerNobodyHoldsIsStoredButCreatesNoJobs() {
        Response response = ingest("feed-nobody", "SAVE", 999);

        assertThat(response.status()).isEqualTo(202);
        assertThat(((Number) response.map().get("jobsCreated")).intValue()).isZero();
        assertThat(count("select count(*) from match_events")).isEqualTo(1);
        assertThat(count("select count(*) from jobs")).isZero();
    }

    @Test
    void theEventEndpointReportsProgressByJobStatus() {
        UUID eventId = idOf(ingest("feed-1", "GOAL", GOAL_PLAYER).map());
        awaitAllJobsFinished();

        Response response = api.get("/api/events/" + eventId);

        assertThat(response.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> byStatus = (java.util.Map<String, Object>) response.map().get("jobsByStatus");
        assertThat(byStatus).containsOnlyKeys("COMPLETED");
        assertThat(((Number) byStatus.get("COMPLETED")).intValue()).isEqualTo(expectedJobs(GOAL_PLAYER));
    }

    @Test
    void invalidEventBodiesReturn400AndUnknownEventsReturn404() {
        assertThat(api.post("/api/events", "{\"eventId\":\"x\",\"matchId\":1,\"playerId\":1}").status()).as("missing type").isEqualTo(400);
        assertThat(api.post("/api/events", "{\"eventId\":\"\",\"matchId\":1,\"type\":\"GOAL\",\"playerId\":1}").status()).as("blank id").isEqualTo(400);
        assertThat(api.post("/api/events", "{\"eventId\":\"x\",\"matchId\":1,\"type\":\"OFFSIDE\",\"playerId\":1}").status()).as("unknown type").isEqualTo(400);
        assertThat(api.get("/api/events/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(count("select count(*) from match_events")).isZero();
    }

    @Test
    void rerunningAnAlreadyAppliedScoringJobDoesNotScoreTwice() {
        ingest("feed-1", "GOAL", GOAL_PLAYER);
        awaitAllJobsFinished();
        UUID eventId = jdbc.queryForObject("select id from match_events", UUID.class);
        long teamId = jdbc.queryForObject("select team_id from team_event_scores limit 1", Long.class);
        long leagueId = jdbc.queryForObject("select league_id from fantasy_teams where id = ?", Long.class, teamId);
        int pointsBefore = count("select points from team_scores where team_id = ?", teamId);

        // Simulate at-least-once delivery: the same scoring and notification jobs run a second time.
        String payload = ("{\"eventId\":\"%s\",\"matchId\":12,\"type\":\"GOAL\",\"playerId\":%d,\"teamId\":%d,\"leagueId\":%d}")
                .formatted(eventId, GOAL_PLAYER, teamId, leagueId).replace("\"", "\\\"");
        for (String type : List.of("FANTASY_RECALC", "PUSH_NOTIFICATION", "LEADERBOARD_UPDATE")) {
            assertThat(api.post("/api/jobs/submit", "{\"type\":\"" + type + "\",\"priority\":\"HIGH\",\"payload\":\"" + payload + "\"}").status())
                    .isEqualTo(202);
        }
        awaitAllJobsFinished();

        assertThat(count("select points from team_scores where team_id = ?", teamId)).isEqualTo(pointsBefore);
        assertThat(count("select count(*) from team_event_scores where team_id = ? and event_id = ?", teamId, eventId)).isEqualTo(1);
        assertThat(count("select count(*) from notifications where team_id = ? and event_id = ?", teamId, eventId)).isEqualTo(1);
    }

    @Test
    void aJobWithAMalformedPayloadFailsLoudlyAndEndsDead() {
        UUID id = idOf(api.post("/api/jobs/submit",
                "{\"type\":\"FANTASY_RECALC\",\"priority\":\"HIGH\",\"payload\":\"{\\\"x\\\":1}\"}").map());

        awaitStatus(id, "DEAD");

        assertThat(attemptsOf(id)).isEqualTo(3);
        assertThat(count("select count(*) from team_scores")).isZero();
    }
}
