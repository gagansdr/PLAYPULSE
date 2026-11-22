package com.playpulse.playpulse.dev;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Dev-only. Disable with playpulse.dev-endpoints.enabled=false (it deletes all fantasy teams). */
@RestController
@RequestMapping("/api/dev")
@ConditionalOnProperty(name = "playpulse.dev-endpoints.enabled", havingValue = "true")
public class DevSeedController {

    private final DevSeedService seedService;

    public DevSeedController(DevSeedService seedService) {
        this.seedService = seedService;
    }

    /** Wipes scores, notifications and leaderboards (keeps teams) so an experiment can start from zero. */
    @PostMapping("/reset-scores")
    public Map<String, String> resetScores() {
        seedService.resetScores();
        return Map.of("status", "scores reset");
    }

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "300") int teams,
                                    @RequestParam(defaultValue = "5") int leagues,
                                    @RequestParam(defaultValue = "30") int players,
                                    @RequestParam(defaultValue = "11") int playersPerTeam) {
        if (teams < 1 || leagues < 1 || players < 1 || playersPerTeam < 1 || playersPerTeam > players) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "teams, leagues, players, playersPerTeam must be >= 1 and playersPerTeam <= players");
        }
        int[] counts = seedService.seed(teams, leagues, players, playersPerTeam);
        return Map.of("teams", counts[0], "squadRows", counts[1], "leagues", leagues, "players", players);
    }
}
