#!/usr/bin/env bash
# 로컬 실제 PostgreSQL 로 동시성 부하 검증을 한 번에 돌린다.
#   1) docker compose 의 postgres 에 전용 DB(daumjari_load)를 새로 만든다 (개발 DB daumjari 는 건드리지 않음)
#   2) 그 DB 로 백엔드를 테스트 전용 포트·한도로 띄운다 (코드 기본값은 그대로, 실행 인자로만 한도를 올림)
#   3) loadtest.mjs 로 단계별 부하 → 4) checks.sql 로 정합성 검증(0행이어야 정상) → 백엔드 종료
# 사용: loadtest/run.sh            (LEVELS=10,50 loadtest/run.sh 처럼 단계 지정 가능)
#       loadtest/run.sh --selftest (checks.sql 이 위반을 잡는지만 확인)
set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${LOADTEST_PORT:-18090}"
DB="daumjari_load"
PSQL_ADMIN=(docker compose exec -T postgres psql -U daumjari -v ON_ERROR_STOP=1)
export PSQL="docker compose exec -T postgres psql -U daumjari -d $DB"
export BASE_URL="http://localhost:$PORT"

docker compose up -d --wait postgres >/dev/null

if [[ "${1:-}" == "--selftest" ]]; then
  "${PSQL_ADMIN[@]}" -d daumjari_load -c 'select 1' >/dev/null 2>&1 || { echo "먼저 loadtest/run.sh 를 한 번 돌려 $DB 를 만드세요"; exit 1; }
  { sed '/^\\i /d;/^rollback;/d' loadtest/checks-selftest.sql; cat loadtest/checks.sql; echo 'rollback;'; } | $PSQL
  exit 0
fi

"${PSQL_ADMIN[@]}" -d postgres -c "drop database if exists $DB" -c "create database $DB" >/dev/null

mkdir -p loadtest/results
LOG="loadtest/results/backend-$(date +%Y%m%d-%H%M%S).log"
(cd backend && ./gradlew bootRun --console=plain -q --args="--server.port=$PORT \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/$DB \
  --daumjari.signup.max-per-ip=1000000 \
  --daumjari.login-throttle.max-per-ip=1000000 \
  --daumjari.login-throttle.max-per-account-ip=1000000 \
  --logging.level.root=WARN") >"$LOG" 2>&1 &
BACKEND_PID=$!
trap 'pkill -f "server.port=$PORT" >/dev/null 2>&1 || true; kill $BACKEND_PID >/dev/null 2>&1 || true' EXIT

for _ in $(seq 1 90); do
  curl -sf "$BASE_URL/actuator/health" >/dev/null && break
  sleep 2
done
curl -sf "$BASE_URL/actuator/health" >/dev/null || { echo "백엔드가 뜨지 않았어요: $LOG"; exit 1; }

status=0
node loadtest/loadtest.mjs || status=$?
echo "--- 정합성 검증 (모두 0행이어야 정상) ---"
$PSQL < loadtest/checks.sql | tee "loadtest/results/checks-$(date +%Y%m%d-%H%M%S).txt"
exit $status
