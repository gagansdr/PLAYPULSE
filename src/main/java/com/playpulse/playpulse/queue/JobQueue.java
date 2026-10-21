package com.playpulse.playpulse.queue;

import com.playpulse.playpulse.job.Job;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Priority queue of job ids, backed by a Redis sorted set.
 * Lowest score is served first: score = priorityRank * BAND + enqueueTimeMillis,
 * so HIGH beats MEDIUM beats LOW, and within one priority the oldest job goes first.
 */
@Component
public class JobQueue {

    static final String KEY = "playpulse:queue:pending";

    // Epoch millis is ~1.7e12, so a 1e13 band keeps priorities from overlapping,
    // and the score stays well below 2^53 (the limit for exact doubles).
    private static final double PRIORITY_BAND = 1e13;

    private final StringRedisTemplate redis;

    public JobQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void enqueue(Job job) {
        // ordinal(): HIGH=0, MEDIUM=1, LOW=2 (declaration order in JobPriority)
        double score = job.getPriority().ordinal() * PRIORITY_BAND + System.currentTimeMillis();
        redis.opsForZSet().add(KEY, job.getId().toString(), score);
    }

    /** Atomically removes and returns the most urgent job id, or empty if the queue is empty. */
    public Optional<UUID> dequeue() {
        ZSetOperations.TypedTuple<String> head = redis.opsForZSet().popMin(KEY);
        if (head == null || head.getValue() == null) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(head.getValue()));
    }

    public long size() {
        Long size = redis.opsForZSet().zCard(KEY);
        return size == null ? 0 : size;
    }
}
