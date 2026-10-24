package org.eckelsoft.chunkblockmod;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TickWorkQueueTest {
    static class Job implements TickWorkQueue.Work {
        int remaining, visits, checkpoints, completions;
        boolean available = true;
        Job(int remaining) { this.remaining = remaining; }
        public boolean isAvailable() { return available; }
        public boolean step() { visits++; return --remaining <= 0; }
        public void checkpoint() { checkpoints++; }
        public void complete() { completions++; }
    }

    @Test void capsBacklogAndDeduplicatesWithoutReplacingProgress() {
        var queue = new TickWorkQueue<String>(2, () -> 0L);
        var first = new Job(1000);
        assertTrue(queue.offer("a", first));
        assertFalse(queue.offer("a", new Job(1)));
        assertTrue(queue.offer("b", new Job(1000)));
        assertFalse(queue.offer("c", new Job(1)));
        assertEquals(2, queue.size());
        queue.run(1, 100);
        assertEquals(1, first.visits);
    }

    @Test void sharesVisitBudgetAcrossJobsAndMakesFairProgress() {
        var queue = new TickWorkQueue<Integer>(10, () -> 0L);
        var jobs = new ArrayList<Job>();
        for (int i = 0; i < 10; i++) { jobs.add(new Job(10000)); queue.offer(i, jobs.get(i)); }
        assertEquals(1024, queue.run(1024, 100).visits());
        assertTrue(jobs.stream().allMatch(j -> j.visits > 0));
        assertEquals(1024, jobs.stream().mapToInt(j -> j.visits).sum());
    }

    @Test void stopsOnTimeEvenWithVisitBudgetRemaining() {
        var clock = new AtomicLong();
        var queue = new TickWorkQueue<Integer>(1, clock::get);
        var job = new Job(10000) {
            @Override public boolean step() { clock.addAndGet(500_000); return super.step(); }
        };
        queue.offer(1, job);
        var result = queue.run(2048, 2_000_000);
        assertEquals(4, result.visits());
        assertEquals(2_000_000, result.nanos());
        assertEquals(1, job.checkpoints);
        assertEquals(1, queue.size());
    }

    @Test void dropsUnloadedJobsInsteadOfRetainingOrReloadingThem() {
        var queue = new TickWorkQueue<String>(2, () -> 0L);
        var unloaded = new Job(100); unloaded.available = false;
        var loaded = new Job(1);
        queue.offer("unloaded", unloaded); queue.offer("loaded", loaded);
        var result = queue.run(100, 100);
        assertEquals(1, result.unavailable());
        assertEquals(0, unloaded.visits);
        assertEquals(1, result.completed());
        assertEquals(0, queue.size());
        assertTrue(queue.offer("unloaded", new Job(100)));
    }

    @Test void chargesAvailabilityChecksToTheTimeBudget() {
        var clock = new AtomicLong();
        var queue = new TickWorkQueue<Integer>(2, clock::get);
        var job = new Job(100) {
            @Override public boolean isAvailable() { clock.addAndGet(3_000_000); return true; }
        };
        queue.offer(1, job);
        var result = queue.run(2048, 2_000_000);
        assertEquals(0, result.visits());
        assertEquals(1, queue.size());
    }

    @Test void completesOnceAndReleasesCapacity() {
        var queue = new TickWorkQueue<Integer>(1, () -> 0L);
        var job = new Job(3);
        queue.offer(1, job);
        assertEquals(2, queue.run(2, 100).visits());
        assertEquals(1, job.checkpoints);
        assertEquals(1, queue.run(2, 100).completed());
        queue.run(2, 100);
        assertEquals(1, job.completions);
        assertEquals(0, queue.size());
        assertTrue(queue.offer(2, new Job(1)));
    }

    @Test void resumesTheNextJobOnTheFollowingTick() {
        var queue = new TickWorkQueue<Integer>(2, () -> 0L);
        var a = new Job(1000); var b = new Job(1000);
        queue.offer(1, a); queue.offer(2, b);
        queue.run(10, 100); queue.run(10, 100);
        assertEquals(10, a.visits); assertEquals(10, b.visits);
    }

    @Test void unloadAndShutdownReleaseReferences() {
        var queue = new TickWorkQueue<Integer>(2, () -> 0L);
        queue.offer(1, new Job(100)); queue.offer(2, new Job(100));
        queue.remove(1);
        assertFalse(queue.contains(1)); assertEquals(1, queue.size());
        queue.clear(); assertEquals(0, queue.size());
    }

    @Test void rejectsBudgetsThatCouldMakeNoProgress() {
        assertThrows(IllegalArgumentException.class, () -> new TickWorkQueue<Integer>(0));
        var queue = new TickWorkQueue<Integer>(1);
        assertThrows(IllegalArgumentException.class, () -> queue.run(0, 1));
        assertThrows(IllegalArgumentException.class, () -> queue.run(1, 0));
    }
}
