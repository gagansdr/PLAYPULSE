package com.playpulse.playpulse.worker;

import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.job.JobRepository;
import com.playpulse.playpulse.queue.JobQueue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Moves jobs whose retry backoff has elapsed from the delayed set back into the priority queue. */
@Component
public class RetryScheduler {

    private final JobQueue jobQueue;
    private final JobRepository jobRepository;

    public RetryScheduler(JobQueue jobQueue, JobRepository jobRepository) {
        this.jobQueue = jobQueue;
        this.jobRepository = jobRepository;
    }

    @Scheduled(fixedDelay = 500)
    public void promoteDueRetries() {
        List<UUID> due = jobQueue.claimDue(100);
        if (due.isEmpty()) {
            return;
        }
        // Priority lives on the Job row, so load it to put the job back at the right place in the queue.
        for (Job job : jobRepository.findAllById(due)) {
            jobQueue.enqueue(job);
        }
    }
}
