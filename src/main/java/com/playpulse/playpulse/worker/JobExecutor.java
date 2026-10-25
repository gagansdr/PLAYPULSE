package com.playpulse.playpulse.worker;

import com.playpulse.playpulse.job.DependencyService;
import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.job.JobRepository;
import com.playpulse.playpulse.job.JobStatus;
import com.playpulse.playpulse.queue.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Runs a single job end to end and records its lifecycle in Postgres. */
@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final JobRepository jobRepository;
    private final JobQueue jobQueue;
    private final RetryPolicy retryPolicy;
    private final DependencyService dependencyService;
    private final Map<String, JobHandler> handlers;

    public JobExecutor(JobRepository jobRepository, JobQueue jobQueue, RetryPolicy retryPolicy,
                       DependencyService dependencyService, List<JobHandler> handlerList) {
        this.jobRepository = jobRepository;
        this.jobQueue = jobQueue;
        this.retryPolicy = retryPolicy;
        this.dependencyService = dependencyService;
        this.handlers = handlerList.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
    }

    public void execute(UUID jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Dequeued job {} but it is not in the database; skipping", jobId);
            return;
        }
        if (job.getStatus() != JobStatus.PENDING && job.getStatus() != JobStatus.FAILED) {
            log.warn("Dequeued job {} but its status is {}; skipping", jobId, job.getStatus());
            return;
        }

        job.markRunning();
        jobRepository.save(job);

        try {
            JobHandler handler = handlers.get(job.getType());
            if (handler == null) {
                throw new IllegalStateException("No handler registered for job type " + job.getType());
            }
            handler.handle(job);
        } catch (Exception e) {
            recordFailure(job, e.toString());
            return;
        }

        job.markCompleted();
        jobRepository.save(job);
        log.info("Job {} ({}, {}) COMPLETED on attempt {}", job.getId(), job.getType(), job.getPriority(),
                job.getAttempts());
        releaseChildren(job);
    }

    /**
     * Called when an attempt failed (handler threw, or the job was found stuck RUNNING).
     * Retries with backoff while attempts remain, otherwise parks the job as DEAD and cancels what depends on it.
     */
    public void recordFailure(Job job, String reason) {
        if (retryPolicy.canRetry(job)) {
            long delayMs = retryPolicy.nextDelayMs(job.getAttempts());
            job.markFailed();
            jobRepository.save(job);
            jobQueue.scheduleRetry(job.getId(), delayMs);
            log.warn("Job {} ({}) FAILED attempt {}: {}. Retrying in {} ms",
                    job.getId(), job.getType(), job.getAttempts(), reason, delayMs);
        } else {
            job.markDead();
            jobRepository.save(job);
            log.error("Job {} ({}) is DEAD after {} attempts: {}",
                    job.getId(), job.getType(), job.getAttempts(), reason);
            cancelDownstream(job);
        }
    }

    // The job itself is already COMPLETED, so a failure here must not turn it into a failed job.
    // The recovery sweeper re-checks WAITING jobs and releases any that were missed.
    private void releaseChildren(Job parent) {
        try {
            for (Job child : dependencyService.onJobCompleted(parent.getId())) {
                jobQueue.enqueue(child);
                log.info("Job {} ({}) released: parent {} completed", child.getId(), child.getType(), parent.getId());
            }
        } catch (Exception e) {
            log.error("Could not release children of job {}; the recovery sweeper will retry", parent.getId(), e);
        }
    }

    private void cancelDownstream(Job dead) {
        try {
            for (UUID id : dependencyService.cancelDescendants(dead.getId())) {
                log.warn("Job {} CANCELLED: depends on DEAD job {}", id, dead.getId());
            }
        } catch (Exception e) {
            log.error("Could not cancel descendants of job {}; the recovery sweeper will retry", dead.getId(), e);
        }
    }
}
