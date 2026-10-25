package com.playpulse.playpulse.job;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String type,
        JobPriority priority,
        JobStatus status,
        int attempts,
        int pendingDependencies,
        Instant createdAt) {

    public static JobResponse from(Job job) {
        return new JobResponse(job.getId(), job.getType(), job.getPriority(), job.getStatus(),
                job.getAttempts(), job.getPendingDependencies(), job.getCreatedAt());
    }
}
