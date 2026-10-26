package com.playpulse.playpulse.worker.handlers;

import org.springframework.boot.json.JsonParserFactory;

import java.util.Map;
import java.util.UUID;

/** Typed access to a job's JSON payload. A missing field fails the job loudly rather than guessing. */
final class JobPayload {

    private final Map<String, Object> values;

    private JobPayload(Map<String, Object> values) {
        this.values = values;
    }

    static JobPayload parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Job has no payload");
        }
        return new JobPayload(JsonParserFactory.getJsonParser().parseMap(json));
    }

    long requireLong(String key) {
        if (!(values.get(key) instanceof Number n)) {
            throw new IllegalArgumentException("Payload is missing numeric field '" + key + "'");
        }
        return n.longValue();
    }

    String requireString(String key) {
        if (!(values.get(key) instanceof String s)) {
            throw new IllegalArgumentException("Payload is missing text field '" + key + "'");
        }
        return s;
    }

    UUID requireUuid(String key) {
        return UUID.fromString(requireString(key));
    }
}
