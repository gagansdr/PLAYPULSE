package com.playpulse.playpulse.job;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A set of jobs submitted together with dependencies between them. Nodes are named by a request-local
 * {@code key} because their ids do not exist yet; {@code dependsOn} lists the keys of the parent nodes.
 */
public record SubmitGraphRequest(@NotEmpty @Valid List<Node> nodes) {

    public record Node(
            @NotBlank String key,
            @NotBlank @Size(max = 100) String type,
            String payload,
            @NotNull JobPriority priority,
            List<String> dependsOn) {
    }
}
