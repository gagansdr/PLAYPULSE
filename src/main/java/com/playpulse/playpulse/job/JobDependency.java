package com.playpulse.playpulse.job;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/** One arrow in the job graph: {@code id.jobId} (child) cannot start until {@code id.dependsOnJobId} (parent) completes. */
@Entity
@Table(name = "job_dependencies")
public class JobDependency implements Persistable<JobDependencyId> {

    @EmbeddedId
    private JobDependencyId id;

    // See Job: lets Spring Data INSERT without a preceding SELECT, and lets Hibernate batch the inserts.
    @Transient
    private boolean isNew = true;

    protected JobDependency() {}

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public JobDependency(UUID childId, UUID parentId) {
        this.id = new JobDependencyId(childId, parentId);
    }

    @Override
    public JobDependencyId getId() { return id; }
}
