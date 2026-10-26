package com.playpulse.playpulse.worker.handlers;

import com.playpulse.playpulse.fantasy.LeaderboardService;
import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.worker.JobHandler;
import org.springframework.stereotype.Component;

/** Rebuilds one league's Redis leaderboard from the committed team totals. Idempotent. */
@Component
public class LeaderboardUpdateHandler implements JobHandler {

    private final LeaderboardService leaderboardService;

    public LeaderboardUpdateHandler(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    @Override
    public String type() {
        return "LEADERBOARD_UPDATE";
    }

    @Override
    public void handle(Job job) {
        long leagueId = JobPayload.parse(job.getPayload()).requireLong("leagueId");
        leaderboardService.rebuildLeague(leagueId);
    }
}
