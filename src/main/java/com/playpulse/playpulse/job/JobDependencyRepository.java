package com.playpulse.playpulse.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface JobDependencyRepository extends JpaRepository<JobDependency, JobDependencyId> {

    /** The children of a parent job: every row whose depends_on_job_id is the parent. */
    List<JobDependency> findByIdDependsOnJobId(java.util.UUID parentId);

    /** Just the child ids of a parent: all the release path needs, without loading entities. */
    @Query("select d.id.jobId from JobDependency d where d.id.dependsOnJobId = :parentId")
    List<UUID> findChildIds(@Param("parentId") UUID parentId);
}
