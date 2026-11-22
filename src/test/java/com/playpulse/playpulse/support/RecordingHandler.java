package com.playpulse.playpulse.support;

import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.worker.JobHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only job type "TEST_NOOP". It does nothing except record when it ran and on which worker thread, which lets
 * tests prove "ran exactly once" and "the child started after the parent finished". Only exists on the test classpath.
 */
@Component
public class RecordingHandler implements JobHandler {

    public static final String TYPE = "TEST_NOOP";

    public record Run(long startNanos, long endNanos, String thread) {}

    private static final Map<UUID, List<Run>> RUNS = new ConcurrentHashMap<>();

    public static void clear() {
        RUNS.clear();
    }

    public static List<Run> runsOf(UUID jobId) {
        return RUNS.getOrDefault(jobId, List.of());
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(Job job) throws Exception {
        long start = System.nanoTime();
        // The payload may be a number of milliseconds to take (lets a test make one job deliberately slow);
        // otherwise 20 ms, long enough that parallel workers genuinely overlap.
        String payload = job.getPayload();
        Thread.sleep(payload != null && payload.matches("\\d+") ? Long.parseLong(payload) : 20);
        RUNS.computeIfAbsent(job.getId(), id -> new CopyOnWriteArrayList<>())
                .add(new Run(start, System.nanoTime(), Thread.currentThread().getName()));
    }
}
