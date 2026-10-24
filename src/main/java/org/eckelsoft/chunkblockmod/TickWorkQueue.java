package org.eckelsoft.chunkblockmod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Main-thread work with a shared time/visit budget, bounded backlog and round-robin slices. */
final class TickWorkQueue<K> {
    interface Work {
        boolean isAvailable();
        /** Perform at most one block visit; true means the job is complete. */
        boolean step();
        default void checkpoint() { }
        default void complete() { }
    }

    record Result(int visits, int completed, int unavailable, long nanos) { }

    private static final int SLICE_SIZE = 64;
    private final Map<K, Work> pending = new LinkedHashMap<>();
    private final int capacity;
    private final LongSupplier clock;

    TickWorkQueue(int capacity) {
        this(capacity, System::nanoTime);
    }

    TickWorkQueue(int capacity, LongSupplier clock) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.clock = clock;
    }

    boolean offer(K key, Work work) {
        if (pending.containsKey(key) || pending.size() >= capacity) return false;
        pending.put(key, work);
        return true;
    }

    boolean contains(K key) { return pending.containsKey(key); }
    int size() { return pending.size(); }
    void remove(K key) { pending.remove(key); }
    void clear() { pending.clear(); }

    Result run(int maxVisits, long maxNanos) {
        if (maxVisits < 1 || maxNanos < 1) throw new IllegalArgumentException("budgets must be positive");
        long start = clock.getAsLong();
        int visits = 0, completed = 0, unavailable = 0;
        while (!pending.isEmpty() && visits < maxVisits && clock.getAsLong() - start < maxNanos) {
            var iterator = pending.entrySet().iterator();
            var entry = iterator.next();
            K key = entry.getKey();
            Work work = entry.getValue();
            iterator.remove();
            // Unloaded jobs are discarded, not retried forever or force-loaded.
            // A chunk's checkpoint lets its next visit resume the transformation.
            if (!work.isAvailable()) {
                unavailable++;
                continue;
            }
            boolean done = false;
            for (int i = 0; i < SLICE_SIZE && visits < maxVisits
                    && clock.getAsLong() - start < maxNanos; i++) {
                done = work.step();
                visits++;
                if (done) break;
            }
            if (done) {
                work.complete();
                completed++;
            } else {
                work.checkpoint();
                pending.put(key, work);
            }
        }
        return new Result(visits, completed, unavailable, clock.getAsLong() - start);
    }
}
