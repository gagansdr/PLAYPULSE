package com.playpulse.playpulse.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByStatusAndUpdatedAtBefore(JobStatus status, Instant cutoff);

    /**
     * A parent finished: lower each child's unfinished-parent counter by one.
     * Done as a single SQL UPDATE so two parents finishing at once cannot overwrite each other.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Job j set j.pendingDependencies = j.pendingDependencies - 1, j.updatedAt = :now "
            + "where j.id in :ids and j.pendingDependencies > 0")
    int decrementPending(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);

    /**
     * WAITING -> PENDING, but only if the counter is 0. The status check makes this succeed for exactly one
     * caller, so a job is never released (and enqueued) twice. Returns 1 if this call released it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Job j set j.status = :pending, j.updatedAt = :now "
            + "where j.id = :id and j.status = :waiting and j.pendingDependencies = 0")
    int releaseIfReady(@Param("id") UUID id, @Param("waiting") JobStatus waiting,
                       @Param("pending") JobStatus pending, @Param("now") Instant now);

    /** WAITING jobs (idle since before cutoff) whose parents have all COMPLETED: a release was missed. */
    @Query(value = "select j.* from jobs j where j.status = 'WAITING' and j.updated_at < :cutoff "
            + "and not exists (select 1 from job_dependencies d join jobs p on p.id = d.depends_on_job_id "
            + "where d.job_id = j.id and p.status <> 'COMPLETED')", nativeQuery = true)
    List<Job> findWaitingWithAllParentsCompleted(@Param("cutoff") Instant cutoff);

    /** WAITING jobs (idle since before cutoff) with a DEAD or CANCELLED parent: they can never run. */
    @Query(value = "select j.* from jobs j where j.status = 'WAITING' and j.updated_at < :cutoff "
            + "and exists (select 1 from job_dependencies d join jobs p on p.id = d.depends_on_job_id "
            + "where d.job_id = j.id and p.status in ('DEAD', 'CANCELLED'))", nativeQuery = true)
    List<Job> findWaitingWithDeadParent(@Param("cutoff") Instant cutoff);
}
