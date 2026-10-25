package com.playpulse.playpulse.worker.handlers;

import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.worker.JobHandler;
import org.springframework.stereotype.Component;

/** Demo only: fails on the first two attempts and succeeds on the third, to exercise retries. */
@Component
public class FlakyDemoHandler implements JobHandler {

    @Override
    public String type() {
        return "DEMO_FLAKY";
    }

    @Override
    public void handle(Job job) {
        if (job.getAttempts() < 3) {
            throw new IllegalStateException("simulated transient failure on attempt " + job.getAttempts());
        }
    }
}
