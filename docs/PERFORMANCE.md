# Performance repair: 0.3.0

The repair changes how work is scheduled, not just the number of blocks edited at once. It builds on repository revision `5a0035f` and preserves Eckelsoft's original project credit.

## Problems found in 0.2.0

- The shared 2,048-visit cap did not bound elapsed time. A block write can trigger expensive server work, so a count alone cannot protect tick time.
- Each chunk scanned every Y position, including all empty sky, and repeatedly evaluated foliage tags/string rules inside that loop.
- The pending deque was unbounded. Unloaded jobs stayed in it and were retried every tick, while the global visited set grew for the lifetime of the process.
- `/rc stop` stopped new entries but the scheduled queue continued editing blocks.
- Completed/partial chunks were not persisted, so a restart forgot which chunks had already been transformed.
- Every five seconds the plugin scanned large entity volumes around every player, even while paused, and removed items that did not necessarily belong to this plugin.
- Liquid replacement targets could produce cascading fluid work. Several other reactive materials remained eligible.
- Most controls were public, and the challenge applied to every world on a multiworld server.

## What changed

One main-thread round-robin queue shares a configurable visit/time budget across all chunks. Its default limits are 1,024 visits, a soft 2 ms processing budget, and 64 queued chunks. Each job gets a slice of at most 64 visits before another job gets a turn. Availability checks and checkpoints are included in measured elapsed processing time.

When Paper's average tick time reaches 40 ms, editing pauses until that average recovers. Set `performance.pause-above-mspt: 0` to disable backoff when measuring the worker in isolation. `/rc status` reports when work is waiting for server capacity.

The worker uses each column's world-surface heightmap to skip empty sky and precomputes material classification. It still scans underground and aboveground structures/trees. All world reads/writes stay on the server thread; there are no asynchronous Bukkit block edits and no NMS/version-specific mutation code.

Unloaded work is released without force-loading or generating chunks. Per-chunk persistent data records its chosen material and progress; a subsequent entry resumes it. Completed chunks are not requeued, including after a clean server restart. A full queue defers that entry without permanently marking it visited; enter it again after the queue has room. `/rc stop` pauses queued work; removing a world from the allowlist stops its pending work.

The broad dropped-item sweep has been removed. Containers/inventories, bedrock, and portal blocks are protected. Liquids and reactive replacements such as melting ice, sponges, farmland, and cactus are filtered out. Temporary surprise mobs no longer persist to disk when their expiry task is cancelled by a restart.

Worlds must be explicitly enabled with `/rc world add <loaded-world>`. Existing configurations without an allowlist also start with no enabled worlds. All controls require `paperchunkrandomizer.admin`; `/rc status` is public. Teleports are checked at the actual destination on the next tick, avoiding synchronous destination generation in the event listener.

## Build and verification

Install a Java 21 JDK for the compile toolchain, and run:

```sh
./gradlew build probeJar
```

The wrapper pins Gradle 9.1.0 and verifies its distribution SHA-256. The release jar is under `build/libs/Paper-Chunk-Randomizer-0.3.0-build.<timestamp>.jar`. The separate `RandomizerVerification.jar` is a development harness, never part of the release jar and never intended for a normal server.

The JUnit suite checks queue capacity/deduplication, fairness, shared visit and elapsed-time limits, unloaded-job release, completion/cleanup, configuration clamps, explicit world opt-in, and server-load backoff.

Run integration assertions with a Paper jar and the Java runtime required by that Paper version:

```sh
python3 scripts/verify-paper.py --paper-jar /path/to/paper.jar
```

The script creates a new flat world under `run/verification-<timestamp>`, listens only on `127.0.0.1:25575`, requires a whitelist, and disables RCON/query/management. It copies no production configuration, player data, or worlds. Port 25575 must be free. Each phase shuts down the test server normally; the second starts it again using the saved test world.

The actual-Paper assertions check operator controls, world opt-in, delayed teleport entry, queue limits/deduplication, pause/resume, unload/re-entry, completion markers, retry after overload, protected inventory contents/bedrock/portal/fluids, foliage removal, full tree conversion, and progress/completion across a real restart. The fixed flat-world seed is 230, with 101 solid terrain layers. Effects and surprise mobs are disabled, queue capacity is four, and backoff is disabled for these worker measurements.

See [verification-results.json](verification-results.json) for the final measured timings and tested jar checksum.

The final candidate passed **13 JUnit tests** and both real-Paper phases, including a delayed-teleport regression. The production server was not changed.

| Paper 26.3 verification | Calls/ticks | Worker mean | Worker p95 | Worker max |
|---|---:|---:|---:|---:|
| Phase 1 | 221 | 1.618 ms | 2.683 ms | 8.753 ms |
| Phase 2 | 71 | 2.076 ms | 2.263 ms | 12.598 ms |

Phase 1 transformed five chunks and deliberately checkpointed a sixth. Phase 2 restarted the server, verified the first five completion markers, and finished the sixth chunk. These are synthetic worker timings with no connected players, not a comparison against the original plugin.


## Limits and rollout

The time budget is **soft**. A Bukkit block write, availability check, checkpoint, JVM pause, or OS preemption already in progress cannot be interrupted. Tick cost from lighting, packets, world generation, entity AI, and other plugins is not bounded by this worker's budget. The benchmark records worker-call times in a synthetic world without connected players; it is not a before/after server-wide TPS benchmark or a multiplayer load test.

The plugin trades conversion speed for server responsiveness. Backoff and queue saturation intentionally delay terrain changes. A larger budget increases throughput but consumes more of the server's 50 ms tick; tune only after observing `/rc status` and a profiler on a staging server.

Use a **fresh dedicated challenge world**. Version 0.2.0 did not persist its visited set, so existing transformed chunks cannot be identified retroactively. Each desired dimension/world must be explicitly enabled. `/rc reset` resets the timer only; it does not restore terrain or clear chunk completion markers. Keep a full backup before testing on existing worlds.

This code has not been installed or activated on the production server. Before rollout, test movement/teleportation with real players, Chunk Walker boots, effects, surprise mobs, and any region-protection/claim plugins in staging. The randomizer does not implement third-party claim checks, and terrain replacement is permanent.

## API references

- [Paper scheduling and main-thread work](https://docs.papermc.io/paper/dev/scheduler/)
- [Paper chunk API and persistent data](https://jd.papermc.io/paper/1.21.11/org/bukkit/Chunk.html)
