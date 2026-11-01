package com.playpulse.playpulse.worker;

import com.playpulse.playpulse.job.DependencyService;
import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.job.JobRepository;
import com.playpulse.playpulse.job.JobStatus;
import com.playpulse.playpulse.queue.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Safety net for jobs stranded by crashes. Postgres is the source of truth, Redis is only a fast queue,
 * so this reconciles the two:
 * <ul>
 *   <li>PENDING / FAILED jobs that have sat a while but are in neither Redis structure get re-queued
 *       (covers "saved to Postgres but Redis was down" and "app died between steps").</li>
 *   <li>RUNNING jobs older than the running timeout are treated as a failed attempt
 *       (covers "worker died mid-job"). The timeout must exceed your slowest handler.</li>
 *   <li>WAITING jobs whose parents all COMPLETED are released, and WAITING jobs with a DEAD/CANCELLED
 *       parent are cancelled (covers "app died between a parent completing and its children being released").</li>
 * </ul>
 */
@Component
public class RecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(RecoverySweeper.class);

    private final JobRepository jobRepository;
    private final JobQueue jobQueue;
    private final JobExecutor jobExecutor;
    private final DependencyService dependencyService;
    private final Duration staleAfter;
    private final Duration runningTimeout;

    public RecoverySweeper(JobRepository jobRepository, JobQueue jobQueue, JobExecutor jobExecutor,
                           DependencyService dependencyService,
                           @Value("${playpulse.recovery.stale-after-ms:30000}") long staleAfterMs,
                           @Value("${playpulse.recovery.running-timeout-ms:60000}") long runningTimeoutMs) {
        this.jobRepository = jobRepository;
        this.jobQueue = jobQueue;
        this.jobExecutor = jobExecutor;
        this.dependencyService = dependencyService;
        this.staleAfter = Duration.ofMillis(staleAfterMs);
        this.runningTimeout = Duration.ofMillis(runningTimeoutMs);
    }

    @Scheduled(fixedDelayString = "${playpulse.recovery.interval-ms:15000}")
    public void sweep() {
        Instant now = Instant.now();

        for (JobStatus status : List.of(JobStatus.PENDING, JobStatus.FAILED)) {
            for (Job job : jobRepository.findByStatusAndUpdatedAtBefore(status, now.minus(staleAfter))) {
                if (!jobQueue.isQueued(job.getId())) {
                    jobQueue.enqueue(job);
                    log.warn("Recovery: re-queued {} job {} ({}) that was missing from Redis",
                            status, job.getId(), job.getType());
                }
            }
        }

        for (Job job : jobRepository.findByStatusAndUpdatedAtBefore(JobStatus.RUNNING, now.minus(runningTimeout))) {
            log.warn("Recovery: job {} ({}) stuck RUNNING since {}", job.getId(), job.getType(), job.getUpdatedAt());
            jobExecutor.recordFailure(job, "stuck in RUNNING beyond " + runningTimeout.toMillis() + " ms");
        }

        Instant waitingCutoff = now.minus(staleAfter);
        for (Job job : dependencyService.releaseMissedWaiting(waitingCutoff)) {
            jobQueue.enqueue(job);
            log.warn("Recovery: released WAITING job {} ({}): all parents had completed", job.getId(), job.getType());
        }
        for (var id : dependencyService.cancelBlockedWaiting(waitingCutoff)) {
            log.warn("Recovery: cancelled WAITING job {}: a parent is DEAD or CANCELLED", id);
        }
    }
}
