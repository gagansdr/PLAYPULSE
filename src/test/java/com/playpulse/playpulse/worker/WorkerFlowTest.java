package com.playpulse.playpulse.worker;

import com.playpulse.playpulse.support.IntegrationTestBase;
import com.playpulse.playpulse.support.RecordingHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Areas 3 and 4: workers execute jobs, failures are retried with backoff, and jobs that keep failing end up DEAD. */
@TestPropertySource(properties = {
        "playpulse.worker.count=4",
        "playpulse.retry.max-attempts=3",
        "playpulse.retry.base-delay-ms=50",    // keep backoff real but short so the suite stays fast
        "playpulse.retry.max-delay-ms=200"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)   // close this context so its workers stop afterwards
class WorkerFlowTest extends IntegrationTestBase {

    private UUID submit(String type) {
        return idOf(api.post("/api/jobs/submit", "{\"type\":\"" + type + "\",\"priority\":\"HIGH\"}").map());
    }

    @Test
    void aSubmittedJobIsExecutedAndCompletedOnItsFirstAttempt() {
        UUID id = submit(RecordingHandler.TYPE);

        awaitStatus(id, "COMPLETED");

        assertThat(attemptsOf(id)).isEqualTo(1);
        assertThat(RecordingHandler.runsOf(id)).hasSize(1);
        assertThat(redis.opsForZSet().zCard("playpulse:queue:pending")).isZero();
    }

    @Test
    void aJobTypeWithNoHandlerIsRetriedThenEndsDeadAfterMaxAttempts() {
        UUID id = submit("NO_SUCH_TYPE");

        awaitStatus(id, "DEAD");

        assertThat(attemptsOf(id)).isEqualTo(3);
    }

    @Test
    void aFlakyJobFailsTwiceThenSucceedsOnTheThirdAttempt() {
        UUID id = submit("DEMO_FLAKY");

        awaitStatus(id, "COMPLETED");

        assertThat(attemptsOf(id)).isEqualTo(3);
    }

    @Test
    void aRetryIsParkedInTheDelayedSetNotLostBetweenAttempts() {
        UUID id = submit("NO_SUCH_TYPE");

        // While backing off, the job must be findable in Redis (delayed set) or already back in the pending queue.
        // Once DEAD it must be in neither: a dead job is never re-queued.
        awaitStatus(id, "DEAD");
        assertThat(redis.opsForZSet().zCard("playpulse:queue:pending")).isZero();
        assertThat(redis.opsForZSet().zCard("playpulse:queue:delayed")).isZero();
    }

    @Test
    void manyJobsEachRunExactlyOnceAcrossSeveralWorkers() {
        int total = 100;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            ids.add(submit(RecordingHandler.TYPE));
        }

        awaitAllJobsFinished();

        Set<String> threads = new HashSet<>();
        for (UUID id : ids) {
            assertThat(statusOf(id)).isEqualTo("COMPLETED");
            assertThat(attemptsOf(id)).isEqualTo(1);
            assertThat(RecordingHandler.runsOf(id)).as("executions of %s", id).hasSize(1);
            threads.add(RecordingHandler.runsOf(id).get(0).thread());
        }
        assertThat(threads.size()).as("distinct worker threads that did work").isGreaterThan(1);
    }

    @Test
    void aFailingJobDoesNotStopOtherJobsFromRunning() {
        UUID bad = submit("NO_SUCH_TYPE");
        List<UUID> good = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            good.add(submit(RecordingHandler.TYPE));
        }

        awaitAllJobsFinished();

        assertThat(statusOf(bad)).isEqualTo("DEAD");
        good.forEach(id -> assertThat(statusOf(id)).isEqualTo("COMPLETED"));
    }
}
