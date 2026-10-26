package com.playpulse.playpulse.event;

import com.playpulse.playpulse.event.AffectedTeamFinder.AffectedTeam;
import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.job.JobPriority;
import com.playpulse.playpulse.job.JobRepository;
import com.playpulse.playpulse.job.JobService;
import com.playpulse.playpulse.job.JobStatus;
import com.playpulse.playpulse.job.SubmitGraphRequest;
import com.playpulse.playpulse.job.SubmitGraphRequest.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns one match event into a job graph:
 * <pre>
 *   FANTASY_RECALC (one per affected team) --> LEADERBOARD_UPDATE (one per league) --> PUSH_NOTIFICATION (one per team)
 * </pre>
 * The event row and all its jobs commit together; only then are the runnable jobs pushed to Redis.
 */
@Service
public class EventFanoutService {

    private static final Logger log = LoggerFactory.getLogger(EventFanoutService.class);

    public record IngestResult(MatchEvent event, boolean duplicate, long jobsCreated) {}

    private final MatchEventRepository eventRepository;
    private final AffectedTeamFinder teamFinder;
    private final JobService jobService;
    private final JobRepository jobRepository;
    private final TransactionTemplate tx;

    public EventFanoutService(MatchEventRepository eventRepository, AffectedTeamFinder teamFinder,
                              JobService jobService, JobRepository jobRepository, TransactionTemplate tx) {
        this.eventRepository = eventRepository;
        this.teamFinder = teamFinder;
        this.jobService = jobService;
        this.jobRepository = jobRepository;
        this.tx = tx;
    }

    private record Created(MatchEvent event, Map<String, Job> jobs) {}

    public IngestResult ingest(IngestEventRequest request) {
        var existing = eventRepository.findByExternalEventId(request.eventId());
        if (existing.isPresent()) {
            return duplicate(existing.get());
        }

        Created created;
        try {
            created = tx.execute(status -> {
                MatchEvent event = eventRepository.save(new MatchEvent(request.eventId(), request.matchId(),
                        request.type(), request.playerId(), request.minute()));
                SubmitGraphRequest graph = buildGraph(event);
                Map<String, Job> jobs = graph.nodes().isEmpty()
                        ? Map.of()
                        : jobService.createGraph(graph, event.getId());
                return new Created(event, jobs);
            });
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same eventId raced past the check above; the UNIQUE constraint let one win.
            return eventRepository.findByExternalEventId(request.eventId())
                    .map(this::duplicate)
                    .orElseThrow(() -> e);
        }

        jobService.enqueueRunnable(created.jobs().values());   // after commit
        log.info("Event {} ({} by player {}) fanned out into {} jobs",
                created.event().getExternalEventId(), created.event().getType(),
                created.event().getPlayerId(), created.jobs().size());
        return new IngestResult(created.event(), false, created.jobs().size());
    }

    public EventResponse describe(UUID eventId) {
        MatchEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId));
        return toResponse(event, false, jobRepository.countByEventId(eventId));
    }

    public EventResponse toResponse(IngestResult result) {
        return toResponse(result.event(), result.duplicate(), result.jobsCreated());
    }

    private EventResponse toResponse(MatchEvent event, boolean duplicate, long jobsCreated) {
        Map<JobStatus, Long> byStatus = new EnumMap<>(JobStatus.class);
        jobRepository.countByStatusForEvent(event.getId()).forEach(c -> byStatus.put(c.getStatus(), c.getCount()));
        return new EventResponse(event.getId(), event.getExternalEventId(), event.getMatchId(), event.getType(),
                event.getPlayerId(), duplicate, jobsCreated, byStatus);
    }

    private IngestResult duplicate(MatchEvent event) {
        return new IngestResult(event, true, jobRepository.countByEventId(event.getId()));
    }

    private SubmitGraphRequest buildGraph(MatchEvent event) {
        List<AffectedTeam> teams = teamFinder.findByPlayer(event.getPlayerId());
        List<Node> nodes = new ArrayList<>();
        if (teams.isEmpty()) {
            return new SubmitGraphRequest(nodes);
        }

        Map<Long, List<AffectedTeam>> byLeague = teams.stream()
                .collect(Collectors.groupingBy(AffectedTeam::leagueId, LinkedHashMap::new, Collectors.toList()));

        for (AffectedTeam team : teams) {
            nodes.add(new Node("recalc-" + team.teamId(), "FANTASY_RECALC", payload(event, team.teamId(), team.leagueId()),
                    JobPriority.HIGH, null));
        }
        for (var league : byLeague.entrySet()) {
            List<String> recalcKeys = league.getValue().stream().map(t -> "recalc-" + t.teamId()).toList();
            nodes.add(new Node("board-" + league.getKey(), "LEADERBOARD_UPDATE",
                    payload(event, null, league.getKey()), JobPriority.HIGH, recalcKeys));
        }
        for (AffectedTeam team : teams) {
            nodes.add(new Node("notify-" + team.teamId(), "PUSH_NOTIFICATION", payload(event, team.teamId(), team.leagueId()),
                    JobPriority.MEDIUM, List.of("board-" + team.leagueId())));
        }
        return new SubmitGraphRequest(nodes);
    }

    // Hand-built JSON: every value is a number, a UUID or an enum name, so nothing needs escaping.
    private static String payload(MatchEvent event, Long teamId, long leagueId) {
        return "{\"eventId\":\"" + event.getId() + "\",\"matchId\":" + event.getMatchId()
                + ",\"type\":\"" + event.getType() + "\",\"playerId\":" + event.getPlayerId()
                + (teamId == null ? "" : ",\"teamId\":" + teamId)
                + ",\"leagueId\":" + leagueId + "}";
    }
}
