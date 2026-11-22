package com.playpulse.playpulse.queue;

import com.playpulse.playpulse.job.JobPriority;
import com.playpulse.playpulse.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Area 2: the Redis priority queue, tested directly against a real Redis. */
@TestPropertySource(properties = "playpulse.worker.count=0")
class PriorityQueueTest extends IntegrationTestBase {

    @Autowired
    JobQueue queue;

    private UUID enqueue(JobPriority priority) throws InterruptedException {
        UUID id = UUID.randomUUID();
        queue.enqueue(id, priority);
        Thread.sleep(3);   // the score includes epoch millis, so space arrivals apart to make FIFO observable
        return id;
    }

    private List<UUID> drain() {
        List<UUID> order = new ArrayList<>();
        Optional<UUID> next;
        while ((next = queue.dequeue()).isPresent()) {
            order.add(next.get());
        }
        return order;
    }

    @Test
    void servesHighBeforeMediumBeforeLowRegardlessOfArrivalOrder() throws Exception {
        UUID low1 = enqueue(JobPriority.LOW);
        UUID medium = enqueue(JobPriority.MEDIUM);
        UUID high1 = enqueue(JobPriority.HIGH);
        UUID low2 = enqueue(JobPriority.LOW);
        UUID high2 = enqueue(JobPriority.HIGH);

        assertThat(drain()).containsExactly(high1, high2, medium, low1, low2);
    }

    @Test
    void servesTheOldestJobFirstWithinOnePriority() throws Exception {
        UUID first = enqueue(JobPriority.MEDIUM);
        UUID second = enqueue(JobPriority.MEDIUM);
        UUID third = enqueue(JobPriority.MEDIUM);

        assertThat(drain()).containsExactly(first, second, third);
    }

    @Test
    void aLowJobThatArrivedLongAgoStillYieldsToAFreshHighJob() throws Exception {
        UUID oldLow = enqueue(JobPriority.LOW);
        Thread.sleep(1_100);   // a full second older; priority must still win over age
        UUID freshHigh = enqueue(JobPriority.HIGH);

        assertThat(drain()).containsExactly(freshHigh, oldLow);
    }

    @Test
    void dequeueOnAnEmptyQueueReturnsEmpty() {
        assertThat(queue.dequeue()).isEmpty();
    }

    @Test
    void dequeueRemovesTheJob() throws Exception {
        UUID id = enqueue(JobPriority.HIGH);

        assertThat(queue.dequeue()).contains(id);
        assertThat(queue.dequeue()).isEmpty();
        assertThat(queue.size()).isZero();
    }

    @Test
    void enqueueingTheSameIdTwiceKeepsASingleEntry() {
        UUID id = UUID.randomUUID();
        queue.enqueue(id, JobPriority.HIGH);
        queue.enqueue(id, JobPriority.HIGH);

        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.isQueued(id)).isTrue();
    }

    @Test
    void isQueuedIsFalseForAnIdThatWasNeverAddedOrAlreadyTaken() throws Exception {
        UUID id = enqueue(JobPriority.LOW);
        queue.dequeue();

        assertThat(queue.isQueued(id)).isFalse();
        assertThat(queue.isQueued(UUID.randomUUID())).isFalse();
    }

    @Test
    void eightThreadsPoppingAtOnceNeverReceiveTheSameJob() throws Exception {
        int total = 500;
        Set<UUID> expected = new HashSet<>();
        for (int i = 0; i < total; i++) {
            UUID id = UUID.randomUUID();
            expected.add(id);
            queue.enqueue(id, JobPriority.HIGH);
        }

        ConcurrentLinkedQueue<UUID> received = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            futures.add(pool.submit(() -> {
                Optional<UUID> next;
                while ((next = queue.dequeue()).isPresent()) {
                    received.add(next.get());
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(received).hasSize(total);                       // nothing lost
        assertThat(new HashSet<>(received)).isEqualTo(expected);   // nothing handed out twice
    }
}
