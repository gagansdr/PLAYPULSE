package com.playpulse.playpulse.job;

import com.playpulse.playpulse.queue.JobQueue;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final JobDependencyRepository dependencyRepository;
    private final JobQueue jobQueue;
    private final TransactionTemplate tx;

    public JobService(JobRepository jobRepository, JobDependencyRepository dependencyRepository,
                      JobQueue jobQueue, TransactionTemplate tx) {
        this.jobRepository = jobRepository;
        this.dependencyRepository = dependencyRepository;
        this.jobQueue = jobQueue;
        this.tx = tx;
    }

    public Job submit(SubmitJobRequest request) {
        // Save to Postgres in one transaction, THEN enqueue: a worker must never see a row that is not committed.
        Job saved = tx.execute(status -> createJob(request));
        enqueueIfRunnable(saved);
        return saved;
    }

    public Map<String, Job> submitGraph(SubmitGraphRequest request) {
        JobGraphValidator.validate(request.nodes());
        Map<String, Job> created = tx.execute(status -> createGraph(request));
        created.values().forEach(this::enqueueIfRunnable);   // only root jobs are PENDING; the rest are WAITING
        return created;
    }

    public Job get(UUID id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + id));
    }

    private void enqueueIfRunnable(Job job) {
        if (job.getStatus() == JobStatus.PENDING) {
            jobQueue.enqueue(job);
        }
    }

    private Job createJob(SubmitJobRequest request) {
        Set<UUID> parentIds = request.dependsOn() == null ? Set.of() : new HashSet<>(request.dependsOn());
        List<Job> parents = jobRepository.findAllById(parentIds);
        if (parents.size() != parentIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dependsOn contains an unknown job id");
        }
        for (Job parent : parents) {
            if (parent.getStatus() == JobStatus.DEAD || parent.getStatus() == JobStatus.CANCELLED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Parent job " + parent.getId() + " is " + parent.getStatus() + " and can never complete");
            }
        }

        // A parent that already COMPLETED does not block the new job.
        int unfinished = (int) parents.stream().filter(p -> p.getStatus() != JobStatus.COMPLETED).count();

        Job job = new Job(request.type(), request.payload(), request.priority());
        if (unfinished > 0) {
            job.markWaiting(unfinished);
        }
        jobRepository.save(job);
        for (Job parent : parents) {
            dependencyRepository.save(new JobDependency(job.getId(), parent.getId()));
        }
        return job;
    }

    private Map<String, Job> createGraph(SubmitGraphRequest request) {
        Map<String, Job> byKey = new LinkedHashMap<>();
        for (var node : request.nodes()) {
            Job job = new Job(node.type(), node.payload(), node.priority());
            int parents = JobGraphValidator.distinct(node).size();
            if (parents > 0) {
                job.markWaiting(parents);
            }
            byKey.put(node.key(), jobRepository.save(job));
        }

        // Jobs first, then arrows: job_dependencies has foreign keys to jobs.
        List<JobDependency> edges = new ArrayList<>();
        for (var node : request.nodes()) {
            for (String parentKey : JobGraphValidator.distinct(node)) {
                edges.add(new JobDependency(byKey.get(node.key()).getId(), byKey.get(parentKey).getId()));
            }
        }
        dependencyRepository.saveAll(edges);
        return byKey;
    }
}
