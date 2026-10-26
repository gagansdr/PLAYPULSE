package com.playpulse.playpulse.event;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Something that happened in a match, as reported by a feed. One row per real-world occurrence. */
@Entity
@Table(name = "match_events")
public class MatchEvent {

    @Id
    private UUID id;

    @Column(name = "external_event_id", nullable = false, unique = true)
    private String externalEventId;

    @Column(name = "match_id", nullable = false)
    private long matchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchEventType type;

    @Column(name = "player_id", nullable = false)
    private long playerId;

    @Column(name = "match_minute")
    private Integer minute;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    protected MatchEvent() {}

    public MatchEvent(String externalEventId, long matchId, MatchEventType type, long playerId, Integer minute) {
        this.id = UUID.randomUUID();
        this.externalEventId = externalEventId;
        this.matchId = matchId;
        this.type = type;
        this.playerId = playerId;
        this.minute = minute;
    }

    public UUID getId() { return id; }
    public String getExternalEventId() { return externalEventId; }
    public long getMatchId() { return matchId; }
    public MatchEventType getType() { return type; }
    public long getPlayerId() { return playerId; }
    public Integer getMinute() { return minute; }
    public Instant getReceivedAt() { return receivedAt; }
}
