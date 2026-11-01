package com.playpulse.playpulse.worker.handlers;

import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.worker.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Placeholder: simulates recalculating fantasy points after a match event. */
@Component
public class FantasyRecalcHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(FantasyRecalcHandler.class);

    @Override
    public String type() {
        return "FANTASY_RECALC";
    }

    @Override
    public void handle(Job job) throws Exception {
        log.info("Recalculating fantasy points, payload={}", job.getPayload());
        Thread.sleep(300);
    }
}
