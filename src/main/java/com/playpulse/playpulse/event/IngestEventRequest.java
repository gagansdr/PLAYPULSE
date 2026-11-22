package com.playpulse.playpulse.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** eventId is the feed's own id for the event; sending the same eventId twice is safe (it is deduplicated). */
public record IngestEventRequest(
        @NotBlank @Size(max = 100) String eventId,
        @NotNull Long matchId,
        @NotNull MatchEventType type,
        @NotNull Long playerId,
        Integer minute) {
}
