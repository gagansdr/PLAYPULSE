package com.playpulse.playpulse.worker.handlers;

import com.playpulse.playpulse.fantasy.ScoringService;
import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.worker.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Adds the points a match event is worth to one fantasy team. Idempotent per (team, event). */
@Component
public class FantasyRecalcHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(FantasyRecalcHandler.class);

    private final ScoringService scoringService;

    public FantasyRecalcHandler(ScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @Override
    public String type() {
        return "FANTASY_RECALC";
    }

    @Override
    public void handle(Job job) {
        JobPayload payload = JobPayload.parse(job.getPayload());
        long teamId = payload.requireLong("teamId");
        int applied = scoringService.applyEvent(teamId, payload.requireUuid("eventId"), payload.requireString("type"));
        if (applied == 0) {
            log.info("Team {} already had event {} applied; nothing to do", teamId, payload.requireString("eventId"));
        }
    }
}
