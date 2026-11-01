package com.playpulse.playpulse.worker;

import com.playpulse.playpulse.job.Job;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/** Decides whether a failed job gets another try and how long to wait first. */
@Component
public class RetryPolicy {

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;

    public RetryPolicy(@Value("${playpulse.retry.max-attempts:3}") int maxAttempts,
                       @Value("${playpulse.retry.base-delay-ms:1000}") long baseDelayMs,
                       @Value("${playpulse.retry.max-delay-ms:60000}") long maxDelayMs) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    /** attempts is already incremented when a job starts running, so it counts tries used so far. */
    public boolean canRetry(Job job) {
        return job.getAttempts() < maxAttempts;
    }

    /** Exponential backoff (base * 2^(attempts-1), capped), then jitter to a random 50-100% of that. */
    public long nextDelayMs(int attemptsSoFar) {
        long exponential = baseDelayMs * (1L << Math.min(Math.max(attemptsSoFar - 1, 0), 20));
        long capped = Math.min(exponential, maxDelayMs);
        return ThreadLocalRandom.current().nextLong(capped / 2, capped + 1);
    }
}
