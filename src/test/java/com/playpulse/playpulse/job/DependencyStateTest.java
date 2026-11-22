package com.playpulse.playpulse.job;

import com.playpulse.playpulse.support.IntegrationTestBase;
import com.playpulse.playpulse.support.TestApi.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Area 5, part 1: what the dependency API stores and what it puts in Redis. Workers are off, so nothing runs. */
@TestPropertySource(properties = "playpulse.worker.count=0")
class DependencyStateTest extends IntegrationTestBase {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> job(Response response, String key) {
        return (Map<String, Object>) response.map().get(key);
    }

    @Test
    void aGraphStoresBlockedJobsAsWaitingAndQueuesOnlyTheRoot() {
        Response response = api.post("/api/jobs/submit-graph",
                graph(node("points", "T"), node("board", "T", "points"), node("notify", "T", "board")));

        assertThat(response.status()).isEqualTo(202);
        assertThat(job(response, "points")).containsEntry("status", "PENDING").containsEntry("pendingDependencies", 0);
        assertThat(job(response, "board")).containsEntry("status", "WAITING").containsEntry("pendingDependencies", 1);
        assertThat(job(response, "notify")).containsEntry("status", "WAITING").containsEntry("pendingDependencies", 1);
        assertThat(count("select count(*) from job_dependencies")).isEqualTo(2);
        assertThat(redis.opsForZSet().range("playpulse:queue:pending", 0, -1))
                .containsExactly(idOf(job(response, "points")).toString());
    }

    @Test
    void aJoinRecordsOneUnfinishedParentPerArrow() {
        Response response = api.post("/api/jobs/submit-graph",
                graph(node("a", "T"), node("b", "T"), node("c", "T"), node("join", "T", "a", "b", "c")));

        assertThat(job(response, "join")).containsEntry("status", "WAITING").containsEntry("pendingDependencies", 3);
        assertThat(redis.opsForZSet().zCard("playpulse:queue:pending")).isEqualTo(3);   // the three roots
    }

    @Test
    void repeatedParentKeysCountOnce() {
        Response response = api.post("/api/jobs/submit-graph", graph(node("a", "T"), node("b", "T", "a", "a")));
        assertThat(job(response, "b")).containsEntry("pendingDependencies", 1);
        assertThat(count("select count(*) from job_dependencies")).isEqualTo(1);
    }

    @Test
    void rejectedGraphsReturn400AndSaveNothing() {
        String[] bad = {
                graph(node("a", "T", "b"), node("b", "T", "a")),     // cycle
                graph(node("a", "T", "a")),                          // depends on itself
                graph(node("a", "T", "ghost")),                      // unknown key
                graph(node("a", "T"), node("a", "T")),               // duplicate key
                "{\"nodes\":[]}"                                     // empty
        };
        for (String body : bad) {
            assertThat(api.post("/api/jobs/submit-graph", body).status()).as(body).isEqualTo(400);
        }
        assertThat(count("select count(*) from jobs")).isZero();
        assertThat(count("select count(*) from job_dependencies")).isZero();
        assertThat(redis.opsForZSet().zCard("playpulse:queue:pending")).isZero();
    }

    @Test
    void aJobDependingOnAnUnfinishedJobWaits() {
        UUID parent = idOf(api.post("/api/jobs/submit", "{\"type\":\"T\",\"priority\":\"HIGH\"}").map());

        Response child = api.post("/api/jobs/submit",
                "{\"type\":\"T\",\"priority\":\"HIGH\",\"dependsOn\":[\"" + parent + "\"]}");

        assertThat(child.status()).isEqualTo(202);
        assertThat(child.map()).containsEntry("status", "WAITING").containsEntry("pendingDependencies", 1);
        assertThat(redis.opsForZSet().zCard("playpulse:queue:pending")).isEqualTo(1);   // only the parent
    }

    @Test
    void aJobDependingOnACompletedJobStartsImmediately() {
        UUID done = insertJob("T", "COMPLETED", 1, 0, Duration.ofMinutes(1));

        Response child = api.post("/api/jobs/submit",
                "{\"type\":\"T\",\"priority\":\"HIGH\",\"dependsOn\":[\"" + done + "\"]}");

        assertThat(child.map()).containsEntry("status", "PENDING").containsEntry("pendingDependencies", 0);
        assertThat(redis.opsForZSet().zCard("playpulse:queue:pending")).isEqualTo(1);
    }

    @Test
    void aJobDependingOnADeadOrCancelledJobIsRejectedWith409() {
        UUID dead = insertJob("T", "DEAD", 3, 0, Duration.ofMinutes(1));
        UUID cancelled = insertJob("T", "CANCELLED", 0, 1, Duration.ofMinutes(1));

        for (UUID parent : new UUID[] {dead, cancelled}) {
            Response response = api.post("/api/jobs/submit",
                    "{\"type\":\"T\",\"priority\":\"HIGH\",\"dependsOn\":[\"" + parent + "\"]}");
            assertThat(response.status()).isEqualTo(409);
        }
        assertThat(count("select count(*) from jobs")).isEqualTo(2);   // only the two staged parents
    }

    @Test
    void aJobDependingOnAnUnknownIdIsRejectedWith400() {
        Response response = api.post("/api/jobs/submit",
                "{\"type\":\"T\",\"priority\":\"HIGH\",\"dependsOn\":[\"" + UUID.randomUUID() + "\"]}");
        assertThat(response.status()).isEqualTo(400);
        assertThat(count("select count(*) from jobs")).isZero();
    }
}
