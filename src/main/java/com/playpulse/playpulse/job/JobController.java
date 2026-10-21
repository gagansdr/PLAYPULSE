package com.playpulse.playpulse.job;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(jobService.get(id));
    }
}
