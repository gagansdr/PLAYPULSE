#!/usr/bin/env python3
"""
End-to-end load test for PlayPulse. No dependencies beyond Python 3 and Docker.

What it does
  1. Resets the load-test database and Redis DB 1 (never your dev data) and seeds fantasy teams.
  2. Fires match events at POST /api/events from several client threads. Each event fans out into
     ~2 * (teams holding the player) + leagues jobs.
  3. Waits until every job created is COMPLETED, sampling progress once a second.
  4. Prints and saves: ingest latency, jobs created/s, and the headline number: jobs COMPLETED per minute,
     measured from the first request to the last job finishing (end to end).

Prerequisite: the app is running with the load-test database, e.g.
  ./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 \
      --spring.datasource.url=jdbc:postgresql://localhost:5433/playpulse_load --spring.data.redis.database=1"
"""
import argparse
import http.client
import json
import os
import statistics
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

PROJECT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def compose(*args, stdin=None):
    result = subprocess.run(["docker", "compose", "exec", "-T", *args], cwd=PROJECT_DIR,
                            capture_output=True, text=True, input=stdin)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout


def psql(db, sql):
    return compose("postgres", "psql", "-U", "playpulse", "-d", db, "-tAc", sql).strip()


def http_call(host, port, method, path, body=None):
    conn = http.client.HTTPConnection(host, port, timeout=300)
    try:
        conn.request(method, path, body=body, headers={"Content-Type": "application/json"})
        response = conn.getresponse()
        return response.status, response.read().decode()
    finally:
        conn.close()


def percentile(values, p):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(p / 100 * (len(ordered) - 1))))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True, help="name for this run, used for the results file")
    ap.add_argument("--port", type=int, default=8081)
    ap.add_argument("--db", default="playpulse_load")
    ap.add_argument("--redis-db", type=int, default=1)
    ap.add_argument("--teams", type=int, default=2000)
    ap.add_argument("--leagues", type=int, default=5)
    ap.add_argument("--players", type=int, default=30)
    ap.add_argument("--players-per-team", type=int, default=11)
    ap.add_argument("--events", type=int, default=4)
    ap.add_argument("--concurrency", type=int, default=4, help="parallel event requests")
    ap.add_argument("--timeout", type=int, default=900, help="give up if not finished after this many seconds")
    args = ap.parse_args()

    if args.db == "playpulse":
        sys.exit("refusing to run against the dev database 'playpulse'")

    print(f"[reset] truncating {args.db}, flushing Redis db {args.redis_db}")
    psql(args.db, "truncate notifications, team_event_scores, team_scores, team_players, fantasy_teams, "
                  "job_dependencies, jobs, match_events cascade")
    compose("redis", "redis-cli", "-n", str(args.redis_db), "flushdb")

    status, body = http_call("localhost", args.port, "POST",
                             f"/api/dev/seed?teams={args.teams}&leagues={args.leagues}"
                             f"&players={args.players}&playersPerTeam={args.players_per_team}")
    if status != 200:
        sys.exit(f"seed failed: {status} {body}")
    print(f"[seed] {body}")

    run_id = str(int(time.time()))
    types = ["GOAL", "ASSIST", "SAVE", "YELLOW_CARD", "RED_CARD"]
    results = []      # (latency_s, status, jobsCreated)
    lock = threading.Lock()

    def fire(i):
        payload = json.dumps({"eventId": f"load-{run_id}-{i}", "matchId": 1, "type": types[i % len(types)],
                              "playerId": (i % args.players) + 1, "minute": i % 90})
        t = time.perf_counter()
        status, body = http_call("localhost", args.port, "POST", "/api/events", payload)
        latency = time.perf_counter() - t
        created = json.loads(body)["jobsCreated"] if status in (200, 202) else 0
        with lock:
            results.append((latency, status, created))

    samples = []      # (seconds since start, {status: count})
    stop = threading.Event()
    t0 = time.perf_counter()

    def poll():
        while not stop.is_set():
            try:
                rows = psql(args.db, "select status, count(*) from jobs group by status")
                counts = {r.split("|")[0]: int(r.split("|")[1]) for r in rows.splitlines() if r}
                samples.append((time.perf_counter() - t0, counts))
            except Exception as e:           # a failed poll must not kill the test
                print(f"[poll] {e}")
            stop.wait(1.0)

    poller = threading.Thread(target=poll, daemon=True)
    poller.start()

    print(f"[run] {args.events} events, {args.concurrency} at a time")
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        list(pool.map(fire, range(args.events)))
    ingest_seconds = time.perf_counter() - t0

    total_jobs = sum(c for _, s, c in results if s == 202)
    ok = sum(1 for _, s, _ in results if s == 202)
    print(f"[ingest] {ok}/{args.events} accepted in {ingest_seconds:.1f}s, {total_jobs} jobs created")

    done_at = None
    last_progress, last_completed = time.perf_counter(), -1
    while time.perf_counter() - t0 < args.timeout:
        time.sleep(1.0)
        if not samples:
            continue
        counts = samples[-1][1]
        completed = counts.get("COMPLETED", 0)
        if completed != last_completed:
            last_completed, last_progress = completed, time.perf_counter()
        if completed >= total_jobs and sum(counts.values()) == total_jobs:
            done_at = samples[-1][0]
            break
        if time.perf_counter() - last_progress > 90:
            print("[abort] no progress for 90s")
            break
    stop.set()
    poller.join(timeout=3)

    final = samples[-1][1] if samples else {}
    retried = int(psql(args.db, "select count(*) from jobs where attempts <> 1"))
    notifications = int(psql(args.db, "select count(*) from notifications"))
    e2e = done_at if done_at else float("nan")
    latencies = [l for l, s, _ in results if s == 202]

    summary = {
        "label": args.label, "events": args.events, "concurrency": args.concurrency, "teams": args.teams,
        "total_jobs": total_jobs, "finished": done_at is not None,
        "ingest_seconds": round(ingest_seconds, 2),
        "jobs_created_per_second": round(total_jobs / ingest_seconds, 1) if ingest_seconds else None,
        "event_latency_s": {"p50": round(percentile(latencies, 50), 2), "p95": round(percentile(latencies, 95), 2),
                            "max": round(max(latencies), 2), "mean": round(statistics.mean(latencies), 2)} if latencies else {},
        "end_to_end_seconds": round(e2e, 1),
        "jobs_completed_per_minute": round(total_jobs / e2e * 60) if done_at else None,
        "final_status_counts": final, "jobs_with_retries": retried, "notifications": notifications,
        "progress": [(round(t), c.get("COMPLETED", 0)) for t, c in samples[::max(1, len(samples) // 30)]],
    }
    os.makedirs(os.path.join(PROJECT_DIR, "loadtest", "results"), exist_ok=True)
    with open(os.path.join(PROJECT_DIR, "loadtest", "results", f"{args.label}.json"), "w") as f:
        json.dump(summary, f, indent=2)

    print("\n================ RESULT ================")
    print(f"run:                    {args.label}")
    print(f"jobs created/completed: {total_jobs} / {final.get('COMPLETED', 0)}   (finished: {done_at is not None})")
    print(f"ingest:                 {ingest_seconds:.1f}s  ->  {summary['jobs_created_per_second']} jobs created/s")
    if latencies:
        print(f"POST /api/events:       p50 {summary['event_latency_s']['p50']}s  p95 {summary['event_latency_s']['p95']}s  max {summary['event_latency_s']['max']}s")
    print(f"end to end:             {e2e:.1f}s  ->  {summary['jobs_completed_per_minute']} jobs COMPLETED per minute")
    print(f"final statuses:         {final}")
    print(f"jobs that needed retry: {retried}")


if __name__ == "__main__":
    main()
