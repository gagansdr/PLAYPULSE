package com.playpulse.playpulse.job;

import com.playpulse.playpulse.support.IntegrationTestBase;
import com.playpulse.playpulse.support.RecordingHandler;
import com.playpulse.playpulse.support.RecordingHandler.Run;
import com.playpulse.playpulse.support.TestApi.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Area 5, part 2: dependent jobs actually run in order, join correctly, and cancel when a parent dies. */
@TestPropertySource(properties = {
        "playpulse.worker.count=4",
        "playpulse.retry.max-attempts=3",
        "playpulse.retry.base-delay-ms=50",
        "playpulse.retry.max-delay-ms=200"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DependencyFlowTest extends IntegrationTestBase {

    private static final String T = RecordingHandler.TYPE;

    @SuppressWarnings("unchecked")
    private static UUID id(Response response, String key) {
        return idOf((Map<String, Object>) response.map().get(key));
    }

    private static Run run(UUID id) {
        assertThat(RecordingHandler.runsOf(id)).as("runs of %s", id).hasSize(1);
        return RecordingHandler.runsOf(id).get(0);
    }

    private static void assertStartedAfterFinished(UUID child, UUID parent) {
        assertThat(run(child).startNanos()).as("child starts after parent finishes").isGreaterThanOrEqualTo(run(parent).endNanos());
    }

    @Test
    void aChainRunsStrictlyInOrder() {
        Response response = api.post("/api/jobs/submit-graph", graph(node("a", T), node("b", T, "a"), node("c", T, "b")));
        awaitAllJobsFinished();

        UUID a = id(response, "a"), b = id(response, "b"), c = id(response, "c");
        assertStartedAfterFinished(b, a);
        assertStartedAfterFinished(c, b);
        for (UUID job : List.of(a, b, c)) {
            assertThat(statusOf(job)).isEqualTo("COMPLETED");
        }
    }

    @Test
    void aJoinWaitsForBothParentsOfADiamond() {
        Response response = api.post("/api/jobs/submit-graph",
                // "right" is slow on purpose: a join that is released as soon as "left" finishes would start long before it ends.
                graph(node("top", T), node("left", T, "top"), slowNode("right", 800, "top"), node("join", T, "left", "right")));
        awaitAllJobsFinished();

        UUID top = id(response, "top"), left = id(response, "left"), right = id(response, "right"), join = id(response, "join");
        assertStartedAfterFinished(left, top);
        assertStartedAfterFinished(right, top);
        assertStartedAfterFinished(join, left);
        assertStartedAfterFinished(join, right);
        assertThat(attemptsOf(join)).isEqualTo(1);
    }

    @Test
    void aDeadParentCancelsEveryDescendantAndTheyNeverRun() {
        Response response = api.post("/api/jobs/submit-graph", graph(
                node("a", "NO_SUCH_TYPE"), node("b", T, "a"), node("c", T, "b"), node("d", T, "b")));

        UUID a = id(response, "a"), b = id(response, "b"), c = id(response, "c"), d = id(response, "d");
        awaitStatus(a, "DEAD");
        for (UUID descendant : List.of(b, c, d)) {
            awaitStatus(descendant, "CANCELLED");
            assertThat(RecordingHandler.runsOf(descendant)).as("cancelled job must never execute").isEmpty();
            assertThat(attemptsOf(descendant)).isZero();
        }
    }

    @Test
    void aFailedParentThatRecoversStillReleasesItsChild() {
        // DEMO_FLAKY fails twice then succeeds, so its child must wait through the retries and then run.
        Response response = api.post("/api/jobs/submit-graph", graph(node("flaky", "DEMO_FLAKY"), node("child", T, "flaky")));
        awaitAllJobsFinished();

        UUID flaky = id(response, "flaky"), child = id(response, "child");
        assertThat(attemptsOf(flaky)).isEqualTo(3);
        assertThat(statusOf(child)).isEqualTo("COMPLETED");
        assertThat(run(child)).isNotNull();
    }

    @Test
    void tenParentsFinishingTogetherReleaseTheJoinExactlyOnce() {
        // Repeated: if the counter decrement were not atomic, some join would be lost or released twice.
        Map<UUID, List<UUID>> parentsOfJoin = new java.util.LinkedHashMap<>();
        List<UUID> joins = new ArrayList<>();
        List<UUID> parents = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            List<String> nodes = new ArrayList<>();
            String[] parentKeys = new String[10];
            for (int p = 0; p < 10; p++) {
                parentKeys[p] = "p" + p;
                // The last parent is slow, so nine parents are long done while the join must still be waiting.
                nodes.add(p == 9 ? slowNode(parentKeys[p], 500) : node(parentKeys[p], T));
            }
            nodes.add(node("join", T, parentKeys));
            Response response = api.post("/api/jobs/submit-graph", graph(nodes.toArray(new String[0])));
            UUID join = id(response, "join");
            joins.add(join);
            List<UUID> roundParents = new ArrayList<>();
            for (String key : parentKeys) {
                roundParents.add(id(response, key));
            }
            parentsOfJoin.put(join, roundParents);
            parents.addAll(roundParents);
        }

        awaitAllJobsFinished();

        for (UUID join : joins) {
            assertThat(statusOf(join)).isEqualTo("COMPLETED");
            assertThat(attemptsOf(join)).isEqualTo(1);
            assertThat(RecordingHandler.runsOf(join)).as("executions of join %s", join).hasSize(1);
            // Released exactly once AND only after the last of its ten parents had finished.
            parentsOfJoin.get(join).forEach(parent -> assertStartedAfterFinished(join, parent));
        }
        parents.forEach(p -> assertThat(statusOf(p)).isEqualTo("COMPLETED"));
        assertThat(count("select count(*) from jobs where status <> 'COMPLETED'")).isZero();
    }
}
