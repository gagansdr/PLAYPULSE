package com.playpulse.playpulse.job;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key of job_dependencies: (child, parent). */
@Embeddable
public class JobDependencyId implements Serializable {

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "depends_on_job_id", nullable = false)
    private UUID dependsOnJobId;

    protected JobDependencyId() {}

    public JobDependencyId(UUID jobId, UUID dependsOnJobId) {
        this.jobId = jobId;
        this.dependsOnJobId = dependsOnJobId;
    }

    public UUID getJobId() { return jobId; }
    public UUID getDependsOnJobId() { return dependsOnJobId; }

    @Override
    public boolean equals(Object o) {
        return o instanceof JobDependencyId other
                && Objects.equals(jobId, other.jobId)
                && Objects.equals(dependsOnJobId, other.dependsOnJobId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId, dependsOnJobId);
    }
}
