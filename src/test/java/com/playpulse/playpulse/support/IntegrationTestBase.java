package com.playpulse.playpulse.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

/**
 * Starts the real application against throwaway Postgres 16 and Redis 7 containers (never your dev database).
 * The containers are started once per test JVM and shared; every test begins from empty tables and an empty Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    protected TestApi api;

    @BeforeEach
    protected void resetState() {
        api = new TestApi(port);
        RecordingHandler.clear();
        // scoring_rules is reference data seeded by a migration, so it is deliberately not truncated.
        jdbc.execute("truncate notifications, team_event_scores, team_scores, team_players, fantasy_teams, "
                + "job_dependencies, jobs, match_events cascade");
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    // ---- helpers shared by the integration tests ----

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    protected String statusOf(UUID jobId) {
        return jdbc.queryForObject("select status from jobs where id = ?", String.class, jobId);
    }

    protected int attemptsOf(UUID jobId) {
        return count("select attempts from jobs where id = ?", jobId);
    }

    /** Inserts a job straight into Postgres, aged by {@code age}, to stage a situation (e.g. a crash) the API cannot create. */
    protected UUID insertJob(String type, String status, int attempts, int pendingDependencies, Duration age) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into jobs (id, type, payload, priority, status, attempts, pending_dependencies, created_at, updated_at) "
                        + "values (?, ?, null, 'HIGH', ?, ?, ?, now() - make_interval(secs => ?), now() - make_interval(secs => ?))",
                id, type, status, attempts, pendingDependencies, (double) age.toSeconds(), (double) age.toSeconds());
        return id;
    }

    protected void insertEdge(UUID child, UUID parent) {
        jdbc.update("insert into job_dependencies (job_id, depends_on_job_id) values (?, ?)", child, parent);
    }

    protected void awaitStatus(UUID jobId, String expected) {
        Await.until("job " + jobId + " to be " + expected, Duration.ofSeconds(20), () -> expected.equals(statusOf(jobId)));
    }

    /** Waits until at least one job exists and none is still waiting to run, running, or waiting to retry. */
    protected void awaitAllJobsFinished() {
        Await.until("every job to reach a final status", Duration.ofSeconds(30), () ->
                count("select count(*) from jobs") > 0
                        && count("select count(*) from jobs where status in ('WAITING','PENDING','RUNNING','FAILED')") == 0);
    }

    protected static UUID idOf(java.util.Map<String, Object> job) {
        return UUID.fromString((String) job.get("id"));
    }

    // ---- JSON builders for graph requests ----

    protected static String node(String key, String type, String... dependsOn) {
        String deps = dependsOn.length == 0 ? "" : ",\"dependsOn\":[" + String.join(",",
                java.util.Arrays.stream(dependsOn).map(d -> "\"" + d + "\"").toList()) + "]";
        return "{\"key\":\"" + key + "\",\"type\":\"" + type + "\",\"priority\":\"HIGH\"" + deps + "}";
    }

    /** Like {@link #node} but the TEST_NOOP job takes {@code millis} to run. */
    protected static String slowNode(String key, long millis, String... dependsOn) {
        String plain = node(key, "TEST_NOOP", dependsOn);
        return plain.substring(0, plain.length() - 1) + ",\"payload\":\"" + millis + "\"}";
    }

    protected static String graph(String... nodes) {
        return "{\"nodes\":[" + String.join(",", nodes) + "]}";
    }
}
