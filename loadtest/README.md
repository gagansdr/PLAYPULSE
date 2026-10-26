# PlayPulse load test

An end-to-end load test: how many jobs does PlayPulse fully **complete** per minute, from a match event arriving to
the last notification being written?

## Results (measured on one laptop)

**Machine:** 8 CPU cores, 8 GB RAM, macOS. The app, Postgres 16 (Docker, default settings), Redis 7 (Docker) and the
load generator all share this one machine, so the numbers are conservative and machine-specific.

**Workload:** 2,000 fantasy teams in 5 leagues. Each match event fans out into ~1,470 jobs
(a score recalculation per holding team, a leaderboard rebuild per league, a notification per team).
20 events = **29,470 jobs**, 4 events submitted concurrently. A "job" is one step of that pipeline.
Every run finished with all jobs `COMPLETED` and **0 retries**.

| Stage | Change | Jobs completed / min | `POST /api/events` p50 | Jobs created / s |
|---|---|---|---|---|
| Baseline | code as it was, 8 workers | **28,600 - 29,100** | 4.1 - 4.9 s | ~1,300 - 1,500 |
| Step 1 | batch inserts, no `SELECT` before each `INSERT` | 34,000 | 0.77 s | ~7,000 |
| Step 2 | atomic claim / complete / release (fewer statements per job) | 48,300 | 0.66 s | ~8,500 |
| Step 3 | 16 workers, DB pool 24 | **60,800** | 0.85 s | ~6,300 |
| (Step 3, tried) | 32 workers, DB pool 40 | 57,800 | 1.66 s | ~3,800 |

**Final configuration, 3 repeat runs of 29,470 jobs:** 58,000 / 57,400 / 59,200 jobs per minute
(about **2x the baseline**), event latency p50 0.84 - 0.94 s (about **5x faster**).

**Sustained load, 88,300 jobs (60 events):** 45,900 and 53,400 jobs per minute in two runs. Throughput is lower
over a longer run than in a short burst, and varies from run to run. Quote the sustained figure, not the burst.

## What each step did, and why

1. **Batch inserts.** `Job` and `JobDependency` now implement Spring Data's `Persistable`. Previously each `save()`
   of an entity with a pre-assigned UUID ran a `SELECT` first (to decide insert vs update) and could not be batched.
   Together with `hibernate.jdbc.batch_size` and `reWriteBatchedInserts`, creating ~1,470 jobs went from thousands of
   round trips to a handful.
2. **Fewer, atomic statements per job.** Claiming a job is now one `UPDATE ... RETURNING`; completing it is one
   `UPDATE`; releasing dependents is one `UPDATE ... RETURNING` that lowers the counter and flips `WAITING` to
   `PENDING` together. This also removed a read-then-write race in the worker.
3. **Right-sizing concurrency.** More workers helped up to a point (16) and then hurt (32): extra connections just
   contend for Postgres.

## What limits it now (evidence, not a guess)

While a long run was slowing down, 14 Postgres connections were waiting on `LWLock/WALWrite`: they were queued
behind the write-ahead-log flush that every commit performs. CPU was not saturated (Java ~86%, Postgres ~200%,
Redis idle). So the ceiling on this hardware is **Postgres commit throughput**, i.e. the number of separate
transactions per job (currently about 4: claim, handler, complete, release).

Options not taken, because each adds risk or complexity: merge more of a job's work into fewer transactions;
relax `synchronous_commit` for worker transactions (trades a small window of lost state transitions, which the
at-least-once design could tolerate, for speed); tune Postgres group commit; run several app instances.

## How to rerun

```bash
docker compose up -d
docker compose exec postgres psql -U playpulse -d playpulse -c "create database playpulse_load"   # once
loadtest/start-app.sh 16                      # app on :8081 against the isolated playpulse_load DB and Redis db 1
python3 loadtest/run_loadtest.py --label my-run --events 20 --concurrency 4
```
The script refuses to run against the dev database `playpulse`. Raw results are saved in `loadtest/results/*.json`.
Job-completion log lines are set to WARN during load tests (`start-app.sh`) so logging I/O is not part of the measurement.

## Caveats

- One machine hosts everything, so app, database and load generator compete for the same 8 cores.
- Handlers do real database and Redis work but no network calls to external services (no real push provider or
  feed), so a real deployment would be limited by those too.
- Results vary run to run (see the two long runs). Treat figures as ranges.
- This measures throughput of a pre-seeded workload; it does not cover failure injection or multiple app instances.
