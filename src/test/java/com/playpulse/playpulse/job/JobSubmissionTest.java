package com.playpulse.playpulse.job;

import com.playpulse.playpulse.support.IntegrationTestBase;
import com.playpulse.playpulse.support.TestApi.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Area 1: the submit API. Workers are off so submitted jobs stay where they land and can be inspected. */
@TestPropertySource(properties = "playpulse.worker.count=0")
class JobSubmissionTest extends IntegrationTestBase {

    private static final String VALID = "{\"type\":\"FANTASY_RECALC\",\"payload\":\"x\",\"priority\":\"HIGH\"}";

    @Test
    void validSubmitReturns202AndSavesAPendingJob() {
        Response response = api.post("/api/jobs/submit", VALID);

        assertThat(response.status()).isEqualTo(202);
        assertThat(response.map()).containsEntry("status", "PENDING").containsEntry("attempts", 0);
        UUID id = idOf(response.map());
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(count("select count(*) from jobs")).isEqualTo(1);
    }

    @Test
    void validSubmitPutsTheJobIdInTheRedisQueue() {
        UUID id = idOf(api.post("/api/jobs/submit", VALID).map());

        Set<String> queued = redis.opsForZSet().range("playpulse:queue:pending", 0, -1);
        assertThat(queued).containsExactly(id.toString());
    }

    @Test
    void blankTypeAndMissingPriorityReturn400AndSaveNothing() {
        Response response = api.post("/api/jobs/submit", "{\"type\":\"\",\"priority\":null}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(count("select count(*) from jobs")).isZero();
        assertThat(redis.opsForZSet().zCard("playpulse:queue:pending")).isZero();
    }

    @Test
    void anUnknownPriorityValueReturns400() {
        Response response = api.post("/api/jobs/submit", "{\"type\":\"T\",\"priority\":\"URGENT\"}");
        assertThat(response.status()).isEqualTo(400);
        assertThat(count("select count(*) from jobs")).isZero();
    }

    @Test
    void malformedJsonReturns400() {
        assertThat(api.post("/api/jobs/submit", "{not json").status()).isEqualTo(400);
    }

    @Test
    void typeLongerThanTheColumnReturns400() {
        String tooLong = "x".repeat(101);
        Response response = api.post("/api/jobs/submit", "{\"type\":\"" + tooLong + "\",\"priority\":\"LOW\"}");
        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void clientCannotChooseStatusOrAttempts() {
        Response response = api.post("/api/jobs/submit",
                "{\"type\":\"T\",\"priority\":\"LOW\",\"status\":\"COMPLETED\",\"attempts\":99}");

        assertThat(response.status()).isEqualTo(202);
        UUID id = idOf(response.map());
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(attemptsOf(id)).isZero();
    }

    @Test
    void getReturnsTheCurrentStateOfAJob() {
        UUID id = idOf(api.post("/api/jobs/submit", VALID).map());

        Response response = api.get("/api/jobs/" + id);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.map()).containsEntry("id", id.toString()).containsEntry("status", "PENDING");
    }

    @Test
    void getAnUnknownJobReturns404() {
        assertThat(api.get("/api/jobs/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    @Test
    void getWithAnIdThatIsNotAUuidReturns400() {
        assertThat(api.get("/api/jobs/not-a-uuid").status()).isEqualTo(400);
    }
}
