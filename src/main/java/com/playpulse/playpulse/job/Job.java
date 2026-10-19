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

    public UUID getId() { return id; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public JobPriority getPriority() { return priority; }
    public JobStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
