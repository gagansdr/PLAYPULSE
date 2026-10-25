package com.playpulse.playpulse.job;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/submit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse submit(@Valid @RequestBody SubmitJobRequest request) {
        return JobResponse.from(jobService.submit(request));
    }

    /** Submits several jobs with dependencies between them. Returns each node's job, keyed by the request's node key. */
    @PostMapping("/submit-graph")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, JobResponse> submitGraph(@Valid @RequestBody SubmitGraphRequest request) {
        Map<String, JobResponse> response = new LinkedHashMap<>();
        jobService.submitGraph(request).forEach((key, job) -> response.put(key, JobResponse.from(job)));
        return response;
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(jobService.get(id));
    }
}
