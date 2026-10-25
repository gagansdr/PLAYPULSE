package com.playpulse.playpulse.job;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the job graph moving. Every method here only changes Postgres; callers push the returned
 * jobs to Redis AFTER the transaction commits. (Enqueuing inside the transaction would let a worker pop
 * the id and read the row before commit, see it still WAITING, and skip it, losing the job.)
 */
@Service
public class DependencyService {

    private final JobRepository jobRepository;
    private final JobDependencyRepository dependencyRepository;

    public DependencyService(JobRepository jobRepository, JobDependencyRepository dependencyRepository) {
        this.jobRepository = jobRepository;
        this.dependencyRepository = dependencyRepository;
    }

    /** A job COMPLETED: unblock its children. Returns the children that are now PENDING and ready to enqueue. */
    @Transactional
    public List<Job> onJobCompleted(UUID parentId) {
        List<UUID> childIds = dependencyRepository.findByIdDependsOnJobId(parentId).stream()
                .map(d -> d.getId().getJobId())
                .toList();
        if (childIds.isEmpty()) {
            return List.of();
        }

        jobRepository.decrementPending(childIds, Instant.now());

        List<Job> released = new ArrayList<>();
        for (UUID childId : childIds) {
            if (jobRepository.releaseIfReady(childId, JobStatus.WAITING, JobStatus.PENDING, Instant.now()) == 1) {
                jobRepository.findById(childId).ifPresent(released::add);
            }
        }
        return released;
    }

    /** A job went DEAD (or was cancelled): everything downstream can never run, so cancel it, transitively. */
    @Transactional
    public List<UUID> cancelDescendants(UUID rootId) {
        List<UUID> cancelled = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Deque<UUID> work = new ArrayDeque<>();
        work.add(rootId);

        while (!work.isEmpty()) {
            UUID current = work.poll();
            for (JobDependency edge : dependencyRepository.findByIdDependsOnJobId(current)) {
                UUID childId = edge.getId().getJobId();
                if (!seen.add(childId)) {
                    continue;
                }
                Job child = jobRepository.findById(childId).orElse(null);
                if (child != null && child.getStatus() == JobStatus.WAITING) {
                    child.markCancelled();
                    jobRepository.save(child);
                    cancelled.add(childId);
                }
                work.add(childId);
            }
        }
        return cancelled;
    }

    /** Sweeper: release WAITING jobs whose parents all COMPLETED but whose release was missed (e.g. a crash). */
    @Transactional
    public List<Job> releaseMissedWaiting(Instant cutoff) {
        List<Job> released = new ArrayList<>();
        for (Job job : jobRepository.findWaitingWithAllParentsCompleted(cutoff)) {
            job.markPending();
            released.add(jobRepository.save(job));
        }
        return released;
    }

    /** Sweeper: cancel WAITING jobs that have a DEAD or CANCELLED parent, and everything below them. */
    @Transactional
    public List<UUID> cancelBlockedWaiting(Instant cutoff) {
        List<UUID> cancelled = new ArrayList<>();
        for (Job job : jobRepository.findWaitingWithDeadParent(cutoff)) {
            job.markCancelled();
            jobRepository.save(job);
            cancelled.add(job.getId());
            cancelled.addAll(cancelDescendants(job.getId()));
        }
        return cancelled;
    }
}
