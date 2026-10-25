package com.playpulse.playpulse.job;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/** One arrow in the job graph: {@code id.jobId} (child) cannot start until {@code id.dependsOnJobId} (parent) completes. */
@Entity
@Table(name = "job_dependencies")
public class JobDependency {

    @EmbeddedId
    private JobDependencyId id;

    protected JobDependency() {}

    public JobDependency(UUID childId, UUID parentId) {
        this.id = new JobDependencyId(childId, parentId);
    }

    public JobDependencyId getId() { return id; }
}
