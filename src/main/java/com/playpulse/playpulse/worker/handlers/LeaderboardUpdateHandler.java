package com.playpulse.playpulse.worker.handlers;

import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.worker.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Placeholder: simulates refreshing the leaderboard. */
@Component
public class LeaderboardUpdateHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardUpdateHandler.class);

    @Override
    public String type() {
        return "LEADERBOARD_UPDATE";
    }

    @Override
    public void handle(Job job) throws Exception {
        log.info("Updating leaderboard, payload={}", job.getPayload());
        Thread.sleep(300);
    }
}
