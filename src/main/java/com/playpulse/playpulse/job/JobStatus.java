package com.playpulse.playpulse.job;

public enum JobStatus {
    /** Blocked: at least one parent job has not COMPLETED. Not in Redis. */
    WAITING,
    PENDING,
    RUNNING,
    COMPLETED,
    /** An attempt failed and a retry is scheduled. */
    FAILED,
    /** Out of attempts (dead-letter). */
    DEAD,
    /** A parent job went DEAD (or was cancelled), so this job can never run. */
    CANCELLED
}
