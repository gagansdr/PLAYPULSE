package com.playpulse.playpulse.job;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** dependsOn (optional): ids of existing jobs that must COMPLETE before this one may run. */
public record SubmitJobRequest(
        @NotBlank @Size(max = 100) String type,
        String payload,
        @NotNull JobPriority priority,
        List<UUID> dependsOn) {
}
