package com.playpulse.playpulse.job;

import com.playpulse.playpulse.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two atomic SQL statements that replaced "read the job, check it, then write it": claiming a job for execution
 * and releasing a waiting job when its last parent finishes. Each is a single UPDATE, so races must have exactly one winner.
 */
@TestPropertySource(properties = "playpulse.worker.count=0")
class JobClaimTest extends IntegrationTestBase {

    @Autowired
    JobRepository jobs;

    @Test
    void claimingAPendingJobMakesItRunningAndCountsTheAttempt() {
        UUID id = insertJob("T", "PENDING", 0, 0, Duration.ZERO);

        Optional<Job> claimed = jobs.claim(id, Instant.now());

        assertThat(claimed).isPresent();
        assertThat(claimed.get().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.get().getAttempts()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("RUNNING");
    }

    @Test
    void aFailedJobCanBeClaimedForItsRetry() {
        UUID id = insertJob("T", "FAILED", 1, 0, Duration.ZERO);

        Optional<Job> claimed = jobs.claim(id, Instant.now());

        assertThat(claimed).isPresent();
        assertThat(claimed.get().getAttempts()).isEqualTo(2);
    }

    @Test
    void aClaimedJobCannotBeClaimedAgain() {
        UUID id = insertJob("T", "PENDING", 0, 0, Duration.ZERO);

        assertThat(jobs.claim(id, Instant.now())).isPresent();
        assertThat(jobs.claim(id, Instant.now())).isEmpty();
        assertThat(attemptsOf(id)).isEqualTo(1);
    }

    @Test
    void jobsThatAreNotReadyToRunCannotBeClaimed() {
        for (String status : List.of("WAITING", "RUNNING", "COMPLETED", "DEAD", "CANCELLED")) {
            UUID id = insertJob("T", status, 0, 0, Duration.ZERO);

            assertThat(jobs.claim(id, Instant.now())).as("claim of a %s job", status).isEmpty();
            assertThat(statusOf(id)).isEqualTo(status);      // untouched
            assertThat(attemptsOf(id)).isZero();
        }
    }

    @Test
    void claimingAJobThatDoesNotExistReturnsEmpty() {
        assertThat(jobs.claim(UUID.randomUUID(), Instant.now())).isEmpty();
    }

    @Test
    void thirtyTwoThreadsRacingForOneJobExactlyOneWins() throws Exception {
        UUID id = insertJob("T", "PENDING", 0, 0, Duration.ZERO);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return jobs.claim(id, Instant.now()).isPresent();
            }));
        }
        go.countDown();
        int winners = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                winners++;
            }
        }
        pool.shutdownNow();

        assertThat(winners).isEqualTo(1);
        assertThat(attemptsOf(id)).isEqualTo(1);
    }

    @Test
    void finishingAParentLowersTheCounterAndOnlyTheLastOneReleasesTheChild() {
        UUID child = insertJob("T", "WAITING", 0, 3, Duration.ZERO);

        List<Job> first = jobs.decrementAndRelease(List.of(child), Instant.now());
        assertThat(first.get(0).getStatus()).isEqualTo(JobStatus.WAITING);
        assertThat(first.get(0).getPendingDependencies()).isEqualTo(2);

        List<Job> second = jobs.decrementAndRelease(List.of(child), Instant.now());
        assertThat(second.get(0).getStatus()).isEqualTo(JobStatus.WAITING);
        assertThat(second.get(0).getPendingDependencies()).isEqualTo(1);

        List<Job> third = jobs.decrementAndRelease(List.of(child), Instant.now());
        assertThat(third.get(0).getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(third.get(0).getPendingDependencies()).isZero();
        assertThat(statusOf(child)).isEqualTo("PENDING");
    }

    @Test
    void aCancelledChildIsNeverResurrectedByAFinishingParent() {
        UUID child = insertJob("T", "CANCELLED", 0, 1, Duration.ZERO);

        jobs.decrementAndRelease(List.of(child), Instant.now());

        assertThat(statusOf(child)).isEqualTo("CANCELLED");
    }

    @Test
    void parentsFinishingAtTheSameInstantReleaseTheChildExactlyOnce() throws Exception {
        int parents = 20;
        UUID child = insertJob("T", "WAITING", 0, parents, Duration.ZERO);
        ExecutorService pool = Executors.newFixedThreadPool(parents);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> releasedIt = new ArrayList<>();
        for (int i = 0; i < parents; i++) {
            releasedIt.add(pool.submit(() -> {
                go.await();
                // Did THIS parent's update turn the child PENDING? (the caller that sees that is the one that enqueues it)
                return jobs.decrementAndRelease(List.of(child), Instant.now()).stream()
                        .anyMatch(j -> j.getStatus() == JobStatus.PENDING);
            }));
        }
        go.countDown();
        int releasers = 0;
        for (Future<Boolean> f : releasedIt) {
            if (f.get(30, TimeUnit.SECONDS)) {
                releasers++;
            }
        }
        pool.shutdownNow();

        assertThat(releasers).as("parents that observed the release").isEqualTo(1);
        assertThat(statusOf(child)).isEqualTo("PENDING");
        assertThat(count("select pending_dependencies from jobs where id = ?", child)).isZero();
    }
}
