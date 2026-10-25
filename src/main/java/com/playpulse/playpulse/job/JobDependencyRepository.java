package com.playpulse.playpulse.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobDependencyRepository extends JpaRepository<JobDependency, JobDependencyId> {

    /** The children of a parent job: every row whose depends_on_job_id is the parent. */
    List<JobDependency> findByIdDependsOnJobId(java.util.UUID parentId);
}
