package com.playpulse.playpulse.worker;

import com.playpulse.playpulse.queue.JobQueue;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Starts N virtual-thread workers that continuously pull the most urgent job and run it. */
@Component
public class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);
    private static final long IDLE_SLEEP_MS = 200;

    private final JobQueue jobQueue;
    private final JobExecutor jobExecutor;
    private final int workerCount;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public WorkerPool(JobQueue jobQueue, JobExecutor jobExecutor,
                      @Value("${playpulse.worker.count:4}") int workerCount) {
        this.jobQueue = jobQueue;
        this.jobExecutor = jobExecutor;
        this.workerCount = workerCount;
    }

    // Wait for ApplicationReadyEvent so workers don't start before Flyway/JPA/Redis are ready.
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        for (int i = 1; i <= workerCount; i++) {
            threads.add(Thread.ofVirtual().name("worker-" + i).start(this::runLoop));
        }
        log.info("Started {} workers", workerCount);
    }

    private void runLoop() {
        while (running) {
            try {
                var next = jobQueue.dequeue();
                if (next.isPresent()) {
                    UUID id = next.get();
                    jobExecutor.execute(id);
                } else {
                    Thread.sleep(IDLE_SLEEP_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Never let one bad iteration kill the worker thread.
                log.error("Worker loop error", e);
            }
        }
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        running = false;
        for (Thread t : threads) {
            t.join(5_000);   // let in-flight jobs finish
            t.interrupt();
        }
    }
}
