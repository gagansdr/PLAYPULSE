package com.playpulse.playpulse.job;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JobStateTest {

    private static Job newJob() {
        return new Job("FANTASY_RECALC", "{}", JobPriority.MEDIUM);
    }

    @Test
    void aNewJobIsPendingWithNoAttemptsAndAFreshId() {
        Job job = newJob();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getPendingDependencies()).isZero();
        assertThat(job.getId()).isNotNull().isNotEqualTo(newJob().getId());
    }

    @Test
    void startingARunCountsAnAttempt() {
        Job job = newJob();
        job.markRunning();
        job.markFailed();
        job.markRunning();
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getAttempts()).isEqualTo(2);
    }

    @Test
    void markWaitingRecordsHowManyParentsAreUnfinished() {
        Job job = newJob();
        job.markWaiting(3);
        assertThat(job.getStatus()).isEqualTo(JobStatus.WAITING);
        assertThat(job.getPendingDependencies()).isEqualTo(3);
    }

    @Test
    void markPendingReleasesAWaitingJob() {
        Job job = newJob();
        job.markWaiting(2);
        job.markPending();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPendingDependencies()).isZero();
    }

    @Test
    void terminalStatusesAreRecorded() {
        Job completed = newJob();
        completed.markCompleted();
        Job dead = newJob();
        dead.markDead();
        Job cancelled = newJob();
        cancelled.markCancelled();
        assertThat(completed.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(dead.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(cancelled.getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void everyTransitionRefreshesUpdatedAt() throws Exception {
        Job job = newJob();
        Instant before = job.getUpdatedAt();
        Thread.sleep(5);
        job.markRunning();
        assertThat(job.getUpdatedAt()).isAfter(before);
    }

    @Test
    void jobsCanBeTaggedWithTheEventThatCreatedThem() {
        Job job = newJob();
        assertThat(job.getEventId()).isNull();
        java.util.UUID event = java.util.UUID.randomUUID();
        job.attachToEvent(event);
        assertThat(job.getEventId()).isEqualTo(event);
    }
}
