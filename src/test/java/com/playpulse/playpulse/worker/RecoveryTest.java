package com.playpulse.playpulse.worker;

import com.playpulse.playpulse.support.IntegrationTestBase;
import com.playpulse.playpulse.support.RecordingHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Area 7: the recovery sweeper. Crashes are simulated by writing the half-finished state a crash would leave
 * straight into Postgres (an old job that Redis does not know about), then checking the sweeper repairs it.
 */
@TestPropertySource(properties = {
        "playpulse.worker.count=4",
        "playpulse.retry.max-attempts=3",
        "playpulse.retry.base-delay-ms=50",
        "playpulse.retry.max-delay-ms=200",
        "playpulse.recovery.interval-ms=300",
        "playpulse.recovery.stale-after-ms=1000",
        "playpulse.recovery.running-timeout-ms=2000"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecoveryTest extends IntegrationTestBase {

    private static final String T = RecordingHandler.TYPE;
    private static final Duration LONG_AGO = Duration.ofMinutes(5);

    @Test
    void aPendingJobMissingFromRedisIsRequeuedAndRun() {
        // "Saved to Postgres, but the app died before pushing it to Redis."
        UUID lost = insertJob(T, "PENDING", 0, 0, LONG_AGO);
        assertThat(redis.opsForZSet().zCard("playpulse:queue:pending")).isZero();

        awaitStatus(lost, "COMPLETED");

        assertThat(attemptsOf(lost)).isEqualTo(1);
        assertThat(RecordingHandler.runsOf(lost)).hasSize(1);
    }

    @Test
    void aJobStuckRunningIsTreatedAsAFailedAttemptAndRetried() {
        // "A worker died mid-job": status RUNNING, attempt already counted, nobody is working on it.
        UUID stuck = insertJob(T, "RUNNING", 1, 0, LONG_AGO);

        awaitStatus(stuck, "COMPLETED");

        assertThat(attemptsOf(stuck)).isEqualTo(2);   // the stuck attempt counted, then the retry succeeded
    }

    @Test
    void aStuckRunningJobThatHasUsedAllItsAttemptsBecomesDead() {
        UUID stuck = insertJob(T, "RUNNING", 3, 0, LONG_AGO);

        awaitStatus(stuck, "DEAD");

        assertThat(RecordingHandler.runsOf(stuck)).isEmpty();
    }

    @Test
    void aWaitingJobWhoseParentsAllCompletedIsReleasedAndRun() {
        // "The parent finished, but the app died before releasing its child."
        UUID parent = insertJob(T, "COMPLETED", 1, 0, LONG_AGO);
        UUID child = insertJob(T, "WAITING", 0, 1, LONG_AGO);
        insertEdge(child, parent);

        awaitStatus(child, "COMPLETED");

        assertThat(RecordingHandler.runsOf(child)).hasSize(1);
    }

    @Test
    void aWaitingJobWithADeadParentIsCancelled() {
        UUID parent = insertJob(T, "DEAD", 3, 0, LONG_AGO);
        UUID child = insertJob(T, "WAITING", 0, 1, LONG_AGO);
        UUID grandchild = insertJob(T, "WAITING", 0, 1, LONG_AGO);
        insertEdge(child, parent);
        insertEdge(grandchild, child);

        awaitStatus(child, "CANCELLED");
        awaitStatus(grandchild, "CANCELLED");

        assertThat(RecordingHandler.runsOf(child)).isEmpty();
        assertThat(RecordingHandler.runsOf(grandchild)).isEmpty();
    }

    @Test
    void aWaitingJobWithAnUnfinishedParentIsLeftAlone() {
        UUID parent = insertJob(T, "RUNNING", 1, 0, Duration.ofSeconds(0));   // fresh: not stuck, still working
        UUID child = insertJob(T, "WAITING", 0, 1, LONG_AGO);
        insertEdge(child, parent);

        // Give the sweeper several passes; the child must not be released while its parent is unfinished.
        try {
            Thread.sleep(700);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(statusOf(child)).isEqualTo("WAITING");
    }

    @Test
    void healthyJobsAreNotTouchedByTheSweeper() {
        UUID id = idOf(api.post("/api/jobs/submit", "{\"type\":\"" + T + "\",\"priority\":\"HIGH\"}").map());

        awaitStatus(id, "COMPLETED");

        assertThat(attemptsOf(id)).isEqualTo(1);   // not re-queued or double-run by the sweeper
        assertThat(RecordingHandler.runsOf(id)).hasSize(1);
    }
}
