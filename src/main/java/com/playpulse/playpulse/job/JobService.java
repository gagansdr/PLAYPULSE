package com.playpulse.playpulse.job;

import com.playpulse.playpulse.queue.JobQueue;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final JobQueue jobQueue;

    public JobService(JobRepository jobRepository, JobQueue jobQueue) {
        this.jobRepository = jobRepository;
        this.jobQueue = jobQueue;
    }

    public Job submit(SubmitJobRequest request) {
        Job job = new Job(request.type(), request.payload(), request.priority());
        Job saved = jobRepository.save(job);   // durable record first
        jobQueue.enqueue(saved);               // then make it visible to workers
        return saved;
    }

    public Job get(UUID id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + id));
    }
}
