package com.playpulse.playpulse.fantasy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read side: what a user sees in the app. */
@RestController
@RequestMapping("/api")
public class FantasyController {

    private final LeaderboardService leaderboardService;
    private final NotificationService notificationService;

    public FantasyController(LeaderboardService leaderboardService, NotificationService notificationService) {
        this.leaderboardService = leaderboardService;
        this.notificationService = notificationService;
    }

    @GetMapping("/leagues/{leagueId}/leaderboard")
    public List<LeaderboardService.Entry> leaderboard(@PathVariable long leagueId,
                                                      @RequestParam(defaultValue = "10") int top) {
        return leaderboardService.top(leagueId, Math.max(1, Math.min(top, 100)));
    }

    @GetMapping("/teams/{teamId}/notifications")
    public List<NotificationService.Notification> notifications(@PathVariable long teamId) {
        return notificationService.forTeam(teamId);
    }
}
