package com.playpulse.playpulse.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MatchEventRepository extends JpaRepository<MatchEvent, UUID> {

    Optional<MatchEvent> findByExternalEventId(String externalEventId);
}
