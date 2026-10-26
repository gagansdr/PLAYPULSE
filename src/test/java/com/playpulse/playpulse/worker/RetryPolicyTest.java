package com.playpulse.playpulse.worker;

import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.job.JobPriority;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(3, 1_000, 60_000);

    private static Job jobWithAttempts(int attempts) {
        Job job = new Job("T", null, JobPriority.HIGH);
        for (int i = 0; i < attempts; i++) {
            job.markRunning();   // each start of a run counts as one attempt
        }
        return job;
    }

    @Test
    void retriesWhileAttemptsRemain() {
        assertThat(policy.canRetry(jobWithAttempts(1))).isTrue();
        assertThat(policy.canRetry(jobWithAttempts(2))).isTrue();
    }

    @Test
    void givesUpAfterMaxAttempts() {
        assertThat(policy.canRetry(jobWithAttempts(3))).isFalse();
        assertThat(policy.canRetry(jobWithAttempts(4))).isFalse();
    }

    @Test
    void delayGrowsExponentiallyWithinJitterBounds() {
        // delay = base * 2^(attempt-1), then jittered to a random 50%..100% of that
        for (int i = 0; i < 200; i++) {
            assertThat(policy.nextDelayMs(1)).isBetween(500L, 1_000L);
            assertThat(policy.nextDelayMs(2)).isBetween(1_000L, 2_000L);
            assertThat(policy.nextDelayMs(3)).isBetween(2_000L, 4_000L);
        }
    }

    @Test
    void delayIsCappedAtTheMaximum() {
        for (int i = 0; i < 200; i++) {
            assertThat(policy.nextDelayMs(30)).isBetween(30_000L, 60_000L);   // cap 60s, jitter keeps >= half
        }
    }

    @Test
    void jitterSpreadsRetriesInsteadOfSynchronizingThem() {
        Set<Long> distinct = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            distinct.add(policy.nextDelayMs(3));
        }
        assertThat(distinct.size()).isGreaterThan(20);   // identical delays would mean a thundering herd
    }
}
