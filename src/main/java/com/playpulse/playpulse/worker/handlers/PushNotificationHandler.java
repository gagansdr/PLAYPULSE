package com.playpulse.playpulse.worker.handlers;

import com.playpulse.playpulse.fantasy.NotificationService;
import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.worker.JobHandler;
import org.springframework.stereotype.Component;

/** Writes one notification (with the team's new rank) to the outbox. Idempotent per (team, event). */
@Component
public class PushNotificationHandler implements JobHandler {

    private final NotificationService notificationService;

    public PushNotificationHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public String type() {
        return "PUSH_NOTIFICATION";
    }

    @Override
    public void handle(Job job) {
        JobPayload payload = JobPayload.parse(job.getPayload());
        notificationService.notifyTeam(payload.requireLong("teamId"), payload.requireLong("leagueId"),
                payload.requireUuid("eventId"), payload.requireString("type"), payload.requireLong("playerId"));
    }
}
