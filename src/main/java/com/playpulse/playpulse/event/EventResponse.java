package com.playpulse.playpulse.event;

import com.playpulse.playpulse.job.JobStatus;

import java.util.Map;
import java.util.UUID;

/** What the API returns for an event. duplicate=true means this eventId was already ingested; nothing new was created. */
public record EventResponse(
        UUID id,
        String externalEventId,
        long matchId,
        MatchEventType type,
        long playerId,
        boolean duplicate,
        long jobsCreated,
        Map<JobStatus, Long> jobsByStatus) {
}
