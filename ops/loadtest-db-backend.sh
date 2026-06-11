#!/bin/bash
#
# DB + Backend load test orchestrator
#
# Runs hls-load.js against the 다형성테스트 meetings (4/5/6) only.
# 회의 3 (조합임원 역량강화 교육) 의 stream_key (m-EXAMPLESTREAMKEY) 는
# 절대 타겟하지 않으므로 운영 데이터는 안전.
#
# Usage:
#   sudo /opt/meeting/ops/loadtest-db-backend.sh
#
# Tunables (env vars):
#   VUS_PER_MEETING   회의당 가상 시청자 수  (default 100)
#   DURATION_SEC      테스트 지속 시간(초)   (default 300 = 5분)
#   MEETING_KEYS      space-separated stream_keys (회의 3 절대 포함 X)
#
# Examples:
#   VUS_PER_MEETING=200 DURATION_SEC=600 ./loadtest-db-backend.sh   # 200×3=600 VU, 10분
#   VUS_PER_MEETING=300 ./loadtest-db-backend.sh                    # 회의당 300명 풀 부하

set -u

# ─────────────────────────────────────────────────────────────
# Safety: 회의 3 stream_key 절대 타겟하지 않음
# ─────────────────────────────────────────────────────────────
FORBIDDEN_KEY="m-EXAMPLESTREAMKEY"  # 조합임원 역량강화 교육 (운영 데이터)

# Test meetings — 다형성 테스트 시리즈
DEFAULT_KEYS=(
  "m-EXAMPLESTREAMKEY"   # 회의 4 — 다형성 테스트
  "m-EXAMPLESTREAMKEY"   # 회의 5 — 다형성 테스트2
  "m-EXAMPLESTREAMKEY"   # 회의 6 — 다형성 테스트3
)

# Allow override via MEETING_KEYS env, but still reject the forbidden key
if [ -n "${MEETING_KEYS:-}" ]; then
  IFS=' ' read -ra KEYS <<< "$MEETING_KEYS"
else
  KEYS=("${DEFAULT_KEYS[@]}")
fi

for k in "${KEYS[@]}"; do
  if [ "$k" = "$FORBIDDEN_KEY" ]; then
    echo "❌ ABORT: 회의 3 stream_key($FORBIDDEN_KEY) 는 운영 데이터라 부하 테스트 금지." >&2
    exit 1
  fi
done

VUS_PER_MEETING="${VUS_PER_MEETING:-100}"
DURATION_SEC="${DURATION_SEC:-300}"
APP_BASE_URL="${APP_BASE_URL:-https://meeting.example.com}"
HLS_BASE="${HLS_BASE:-https://hls.example.com}"
LOADTEST_JS="${LOADTEST_JS:-/opt/meeting/loadtest/hls-load.js}"
ENV_FILE="${ENV_FILE:-/opt/meeting/.env}"

# ─────────────────────────────────────────────────────────────
# Pre-flight checks
# ─────────────────────────────────────────────────────────────
if [ ! -f "$LOADTEST_JS" ]; then
  echo "❌ load test script not found: $LOADTEST_JS" >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "❌ node 명령어 없음. Node.js 설치 필요." >&2
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "⚠️  .env 없음 → MySQL 모니터링 skip" >&2
  DB_AVAIL=0
else
  DB_AVAIL=1
  # shellcheck disable=SC1090
  . "$ENV_FILE"
fi

TOTAL_VUS=$(( VUS_PER_MEETING * ${#KEYS[@]} ))
RESULTS_DIR="/tmp/loadtest-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

# ─────────────────────────────────────────────────────────────
# Header
# ─────────────────────────────────────────────────────────────
cat <<EOF

═══════════════════════════════════════════════════════════════
  DB + Backend Load Test
───────────────────────────────────────────────────────────────
  VUs per meeting:  $VUS_PER_MEETING
  Meetings:         ${#KEYS[@]} (회의 3 제외, 다형성 테스트만)
  Total VUs:        $TOTAL_VUS
  Duration:         ${DURATION_SEC}초
  Results dir:      $RESULTS_DIR
  Forbidden key:    $FORBIDDEN_KEY  ← 절대 타겟 X
═══════════════════════════════════════════════════════════════

Meetings under test:
EOF
for k in "${KEYS[@]}"; do echo "  • $k"; done
echo ""

# ─────────────────────────────────────────────────────────────
# Baseline measurement (before load starts)
# ─────────────────────────────────────────────────────────────
echo "─── Baseline (테스트 전 상태) ───"
free -h | head -2
top -bn1 | head -3 | tail -1
if [ "$DB_AVAIL" = "1" ]; then
  mysql -u "$DB_USERNAME" -p"$DB_PASSWORD" -BN -e "
    SHOW STATUS LIKE 'Threads_connected';
    SHOW STATUS LIKE 'Slow_queries';
    SHOW STATUS LIKE 'Max_used_connections';
  " 2>/dev/null | sed 's/^/  /'
fi
echo ""

# ─────────────────────────────────────────────────────────────
# Spawn load generators
# ─────────────────────────────────────────────────────────────
PIDS=()
for key in "${KEYS[@]}"; do
  hls_url="${HLS_BASE}/live/${key}/playlist.m3u8"
  log_file="$RESULTS_DIR/load-${key}.log"
  HLS_URL="$hls_url" \
  APP_BASE_URL="$APP_BASE_URL" \
  API_STREAM_KEY="$key" \
  VUS="$VUS_PER_MEETING" \
  DURATION_SEC="$DURATION_SEC" \
  SIMULATE_UI_APIS=true \
  REALISTIC_MODE=true \
  node "$LOADTEST_JS" >"$log_file" 2>&1 &
  PIDS+=($!)
  echo "  ▶ load gen → $key  (PID $!, log: $log_file)"
done
echo ""
echo "─── Monitoring (10초 간격, ${DURATION_SEC}초 동안) ───"

# ─────────────────────────────────────────────────────────────
# Monitoring loop
# ─────────────────────────────────────────────────────────────
MONITOR_LOG="$RESULTS_DIR/monitor.log"
END=$(( $(date +%s) + DURATION_SEC ))

printf "%-9s | %-7s %-7s %-7s | %-6s | %-5s | %s\n" \
  "time" "mem_used" "mem_free" "swap" "cpu%" "conn" "mysql(slowq/aborted)" \
  | tee "$MONITOR_LOG"
printf "%-9s | %-7s %-7s %-7s | %-6s | %-5s | %s\n" \
  "---------" "-------" "-------" "-------" "------" "-----" "---------------------" \
  | tee -a "$MONITOR_LOG"

while [ $(date +%s) -lt $END ]; do
  ts=$(date '+%H:%M:%S')
  mem=$(free -m | awk '/^Mem:/ {printf "%dM %dM ", $3, $4}')
  swap=$(free -m | awk '/^Swap:/ {printf "%dM", $3}')
  cpu=$(top -bn1 | awk '/^%Cpu/ {printf "%.1f%%", 100 - $8}')

  if [ "$DB_AVAIL" = "1" ]; then
    db_line=$(mysql -u "$DB_USERNAME" -p"$DB_PASSWORD" -BN -e "
      SELECT
        (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected'),
        (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Slow_queries'),
        (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Aborted_connects')
    " 2>/dev/null)
    conn=$(echo "$db_line" | awk '{print $1}')
    slowq=$(echo "$db_line" | awk '{print $2}')
    aborted=$(echo "$db_line" | awk '{print $3}')
    db_summary="${slowq}/${aborted}"
  else
    conn="-"; db_summary="(no env)"
  fi

  line=$(printf "%-9s | %-7s | %-7s | %-6s | %-5s | %s" \
    "$ts" "$mem" "$swap" "$cpu" "$conn" "$db_summary")
  echo "$line" | tee -a "$MONITOR_LOG"
  sleep 10
done

# ─────────────────────────────────────────────────────────────
# Wait for load gens to finish + summary
# ─────────────────────────────────────────────────────────────
echo ""
echo "─── Load generators finishing ───"
for pid in "${PIDS[@]}"; do
  if kill -0 "$pid" 2>/dev/null; then
    wait "$pid" 2>/dev/null
  fi
done

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Results Summary"
echo "═══════════════════════════════════════════════════════════════"

for key in "${KEYS[@]}"; do
  echo ""
  echo "── $key ──"
  if [ -f "$RESULTS_DIR/load-${key}.log" ]; then
    grep -E "summary|p50|p95|p99|error|fail|RPS|VU" \
      "$RESULTS_DIR/load-${key}.log" | tail -25
  fi
done

echo ""
echo "─── Backend recent errors (last 50, during test window) ───"
journalctl -u meeting-backend --since "$(($(date +%s) - DURATION_SEC - 30)) seconds ago" 2>/dev/null \
  | grep -iE "ERROR|Exception|WARN" | tail -20
echo ""

echo "─── Final state ───"
free -h | head -2
if [ "$DB_AVAIL" = "1" ]; then
  echo ""
  mysql -u "$DB_USERNAME" -p"$DB_PASSWORD" -BN -e "
    SHOW STATUS LIKE 'Threads_connected';
    SHOW STATUS LIKE 'Slow_queries';
    SHOW STATUS LIKE 'Max_used_connections';
    SHOW STATUS LIKE 'Aborted_connects';
  " 2>/dev/null | sed 's/^/  /'
fi

echo ""
echo "Full logs preserved at: $RESULTS_DIR"
echo "  - monitor.log         (10초 간격 메모리/CPU/DB 시계열)"
echo "  - load-<meeting>.log  (각 load gen 출력)"
echo ""
echo "═══════════════════════════════════════════════════════════════"
