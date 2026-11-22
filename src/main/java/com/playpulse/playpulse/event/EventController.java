package com.playpulse.playpulse.event;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventFanoutService fanoutService;

    public EventController(EventFanoutService fanoutService) {
        this.fanoutService = fanoutService;
    }

    /** 202 Accepted for a new event (jobs were created); 200 OK if this eventId was already ingested. */
    @PostMapping
    public ResponseEntity<EventResponse> ingest(@Valid @RequestBody IngestEventRequest request) {
        var result = fanoutService.ingest(request);
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(fanoutService.toResponse(result));
    }

    /** The event plus how many of its jobs are in each status, i.e. how far the fan-out has progressed. */
    @GetMapping("/{id}")
    public EventResponse get(@PathVariable UUID id) {
        return fanoutService.describe(id);
    }
}
