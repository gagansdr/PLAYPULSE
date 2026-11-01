package com.playpulse.playpulse.queue;

import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.job.JobPriority;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed queues of job ids.
 * <ul>
 *   <li>pending: priority queue. score = priorityRank * BAND + enqueueTimeMillis, lowest first,
 *       so HIGH beats MEDIUM beats LOW and, within one priority, the oldest job goes first.</li>
 *   <li>delayed: jobs waiting out a retry backoff. score = epoch millis when the job is due.</li>
 * </ul>
 */
@Component
public class JobQueue {

    static final String PENDING_KEY = "playpulse:queue:pending";
    static final String DELAYED_KEY = "playpulse:queue:delayed";

    // Epoch millis is ~1.7e12, so a 1e13 band keeps priorities from overlapping,
    // and the score stays well below 2^53 (the limit for exact doubles).
    private static final double PRIORITY_BAND = 1e13;

    private final StringRedisTemplate redis;

    public JobQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void enqueue(Job job) {
        enqueue(job.getId(), job.getPriority());
    }

    public void enqueue(UUID id, JobPriority priority) {
        // ordinal(): HIGH=0, MEDIUM=1, LOW=2 (declaration order in JobPriority)
        double score = priority.ordinal() * PRIORITY_BAND + System.currentTimeMillis();
        redis.opsForZSet().add(PENDING_KEY, id.toString(), score);
    }

    /** Atomically removes and returns the most urgent job id, or empty if the queue is empty. */
    public Optional<UUID> dequeue() {
        ZSetOperations.TypedTuple<String> head = redis.opsForZSet().popMin(PENDING_KEY);
        if (head == null || head.getValue() == null) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(head.getValue()));
    }

    public long size() {
        Long size = redis.opsForZSet().zCard(PENDING_KEY);
        return size == null ? 0 : size;
    }

    /** Parks a job in the delayed set until delayMs from now. */
    public void scheduleRetry(UUID id, long delayMs) {
        redis.opsForZSet().add(DELAYED_KEY, id.toString(), System.currentTimeMillis() + delayMs);
    }

    /**
     * Removes and returns up to {@code limit} delayed jobs that are now due.
     * An id is only returned if this call's remove succeeded, so concurrent callers never get the same id.
     */
    public List<UUID> claimDue(int limit) {
        Set<String> due = redis.opsForZSet().rangeByScore(DELAYED_KEY, 0, System.currentTimeMillis(), 0, limit);
        List<UUID> claimed = new ArrayList<>();
        if (due == null) {
            return claimed;
        }
        for (String id : due) {
            Long removed = redis.opsForZSet().remove(DELAYED_KEY, id);
            if (removed != null && removed > 0) {
                claimed.add(UUID.fromString(id));
            }
        }
        return claimed;
    }

    /** True if the job id is in the pending queue or waiting in the delayed set. */
    public boolean isQueued(UUID id) {
        String member = id.toString();
        return redis.opsForZSet().score(PENDING_KEY, member) != null
                || redis.opsForZSet().score(DELAYED_KEY, member) != null;
    }
}
