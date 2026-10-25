package com.playpulse.playpulse.job;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rejects graphs that can never finish: duplicate keys, unknown parents, or cycles. */
public final class JobGraphValidator {

    private JobGraphValidator() {}

    public static void validate(List<SubmitGraphRequest.Node> nodes) {
        Set<String> keys = new HashSet<>();
        for (var node : nodes) {
            if (!keys.add(node.key())) {
                throw bad("Duplicate node key: " + node.key());
            }
        }

        // Kahn's algorithm: repeatedly take nodes with no unfinished parents. If some nodes are never
        // taken, they wait on each other in a loop, which means the graph has a cycle.
        Map<String, Integer> unresolvedParents = new HashMap<>();
        Map<String, List<String>> childrenOf = new HashMap<>();
        for (var node : nodes) {
            Set<String> parents = distinct(node);
            for (String parent : parents) {
                if (!keys.contains(parent)) {
                    throw bad("Node '" + node.key() + "' depends on unknown key '" + parent + "'");
                }
                childrenOf.computeIfAbsent(parent, k -> new java.util.ArrayList<>()).add(node.key());
            }
            unresolvedParents.put(node.key(), parents.size());
        }

        Deque<String> ready = new ArrayDeque<>();
        unresolvedParents.forEach((key, count) -> {
            if (count == 0) {
                ready.add(key);
            }
        });

        int processed = 0;
        while (!ready.isEmpty()) {
            String key = ready.poll();
            processed++;
            for (String child : childrenOf.getOrDefault(key, List.of())) {
                if (unresolvedParents.merge(child, -1, Integer::sum) == 0) {
                    ready.add(child);
                }
            }
        }
        if (processed != nodes.size()) {
            throw bad("Dependency cycle detected: some jobs wait on each other");
        }
    }

    /** The set of distinct parent keys of a node (null and repeats ignored). */
    static Set<String> distinct(SubmitGraphRequest.Node node) {
        return node.dependsOn() == null ? Set.of() : new HashSet<>(node.dependsOn());
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
