package com.playpulse.playpulse.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByStatusAndUpdatedAtBefore(JobStatus status, Instant cutoff);

    long countByEventId(UUID eventId);

    interface StatusCount {
        JobStatus getStatus();
        long getCount();
    }

    /** How many of an event's jobs are in each status: the progress of that event's fan-out. */
    @Query("select j.status as status, count(j) as count from Job j where j.eventId = :eventId group by j.status")
    List<StatusCount> countByStatusForEvent(@Param("eventId") UUID eventId);

    /**
     * Atomically takes a job for execution: PENDING/FAILED -> RUNNING, attempts + 1, and returns the updated row.
     * One statement, so two workers can never both claim the same job, and no separate read or merge is needed.
     * Returns empty if the job does not exist or is not claimable (already running, completed, cancelled, ...).
     */
    @Transactional
    @Query(value = "update jobs set status = 'RUNNING', attempts = attempts + 1, updated_at = :now "
            + "where id = :id and status in ('PENDING', 'FAILED') returning *", nativeQuery = true)
    Optional<Job> claim(@Param("id") UUID id, @Param("now") Instant now);

    /** RUNNING -> COMPLETED as a single UPDATE (no read, no merge). */
    @Transactional
    @Modifying
    @Query("update Job j set j.status = com.playpulse.playpulse.job.JobStatus.COMPLETED, j.updatedAt = :now where j.id = :id")
    int markCompleted(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * A parent finished: for each child, lower the unfinished-parent counter and, if that was the last one, flip
     * WAITING -> PENDING, all in one atomic UPDATE. Postgres locks each row, so two parents finishing at the same
     * moment cannot lose an update, and exactly one of them sees the counter reach 0. Returns the updated rows;
     * the caller enqueues those whose status is now PENDING.
     */
    @Transactional
    @Query(value = "update jobs set pending_dependencies = pending_dependencies - 1, "
            + "status = case when status = 'WAITING' and pending_dependencies = 1 then 'PENDING' else status end, "
            + "updated_at = :now where id in (:ids) and pending_dependencies > 0 returning *", nativeQuery = true)
    List<Job> decrementAndRelease(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);

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
