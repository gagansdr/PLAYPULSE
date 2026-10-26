#!/bin/sh
# Starts PlayPulse on port 8081 against the isolated load-test database (Postgres db playpulse_load, Redis db 1).
# Usage: loadtest/start-app.sh [workers] [extra spring args...]     (logs: /tmp/playpulse-load-app.log)
cd "$(dirname "$0")/.." || exit 1
WORKERS=${1:-8}; [ $# -gt 0 ] && shift
exec ./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 \
--spring.datasource.url=jdbc:postgresql://localhost:5433/playpulse_load?reWriteBatchedInserts=true \
--spring.data.redis.database=1 --playpulse.worker.count=$WORKERS \
--logging.level.com.playpulse.playpulse.worker.JobExecutor=WARN $*"
