package com.playpulse.playpulse.job;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitJobRequest(
        @NotBlank @Size(max = 100) String type,
        String payload,
        @NotNull JobPriority priority) {
}
