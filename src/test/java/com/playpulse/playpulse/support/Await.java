package com.playpulse.playpulse.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Polls until a condition holds. Background workers make results appear asynchronously, so tests wait, not sleep. */
public final class Await {

    private Await() {}

    public static void until(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + description, e);
            }
        }
        throw new AssertionError("Timed out after " + timeout.toSeconds() + "s waiting for " + description);
    }
}
