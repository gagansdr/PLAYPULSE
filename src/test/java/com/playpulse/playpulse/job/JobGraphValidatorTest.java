package com.playpulse.playpulse.job;

import com.playpulse.playpulse.job.SubmitGraphRequest.Node;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobGraphValidatorTest {

    private static Node node(String key, String... dependsOn) {
        return new Node(key, "T", null, JobPriority.HIGH, List.of(dependsOn));
    }

    private static void assertRejected(List<Node> nodes, String messagePart) {
        assertThatThrownBy(() -> JobGraphValidator.validate(nodes))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining(messagePart);
    }

    @Test
    void acceptsAChain() {
        assertThatCode(() -> JobGraphValidator.validate(List.of(node("a"), node("b", "a"), node("c", "b")))).doesNotThrowAnyException();
    }

    @Test
    void acceptsADiamond() {
        assertThatCode(() -> JobGraphValidator.validate(
                List.of(node("top"), node("l", "top"), node("r", "top"), node("join", "l", "r")))).doesNotThrowAnyException();
    }

    @Test
    void acceptsSeveralIndependentRoots() {
        assertThatCode(() -> JobGraphValidator.validate(List.of(node("a"), node("b"), node("c")))).doesNotThrowAnyException();
    }

    @Test
    void ignoresRepeatedParentKeys() {
        assertThatCode(() -> JobGraphValidator.validate(List.of(node("a"), node("b", "a", "a")))).doesNotThrowAnyException();
    }

    @Test
    void rejectsATwoNodeCycle() {
        assertRejected(List.of(node("a", "b"), node("b", "a")), "cycle");
    }

    @Test
    void rejectsALongerCycleHiddenBehindAValidRoot() {
        assertRejected(List.of(node("root"), node("a", "root", "c"), node("b", "a"), node("c", "b")), "cycle");
    }

    @Test
    void rejectsAJobThatDependsOnItself() {
        assertRejected(List.of(node("a", "a")), "cycle");
    }

    @Test
    void rejectsAnUnknownParentKey() {
        assertRejected(List.of(node("a", "ghost")), "unknown key");
    }

    @Test
    void rejectsDuplicateNodeKeys() {
        assertRejected(List.of(node("a"), node("a")), "Duplicate");
    }

    @Test
    void treatsMissingDependsOnAsNoDependencies() {
        Node noDeps = new Node("a", "T", null, JobPriority.LOW, null);
        assertThatCode(() -> JobGraphValidator.validate(List.of(noDeps))).doesNotThrowAnyException();
    }
}
