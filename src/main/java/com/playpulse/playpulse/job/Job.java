package com.playpulse.playpulse.job;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String type;

    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(nullable = false)
    private int attempts;

    /** Number of parent jobs that have not COMPLETED yet. Only meaningful while WAITING. */
    @Column(name = "pending_dependencies", nullable = false)
    private int pendingDependencies;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Job() {}

    public Job(String type, String payload, JobPriority priority) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.payload = payload;
        this.priority = priority;
        this.status = JobStatus.PENDING;
    }

    /** Blocks this job until {@code unfinishedParents} parent jobs complete. */
    public void markWaiting(int unfinishedParents) {
        this.status = JobStatus.WAITING;
        this.pendingDependencies = unfinishedParents;
        this.updatedAt = Instant.now();
    }

    /** Releases a blocked job so it can be queued. */
    public void markPending() {
        this.status = JobStatus.PENDING;
        this.pendingDependencies = 0;
        this.updatedAt = Instant.now();
    }

    public void markCancelled() {
        this.status = JobStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    public void markRunning() {
        this.status = JobStatus.RUNNING;
        this.attempts++;
        this.updatedAt = Instant.now();
    }

    public void markCompleted() {
        this.status = JobStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = JobStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    public void markDead() {
        this.status = JobStatus.DEAD;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public JobPriority getPriority() { return priority; }
    public JobStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getPendingDependencies() { return pendingDependencies; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
