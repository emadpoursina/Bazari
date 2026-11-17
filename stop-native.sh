#!/usr/bin/env bash
#
# Take down the Go Money stack started by ./run-native.sh.
#
# Only controls THIS project's processes. The shared Postgres container
# (`shared_postgres`) is never touched — other projects depend on it.
#
# Usage:
#   ./stop-native.sh                 # backend + frontend + android bridge + navasan + grafana
#   ./stop-native.sh --backend-only
#   ./stop-native.sh --frontend-only
#   ./stop-native.sh --bridge-only
#   ./stop-native.sh --navasan-only
#   ./stop-native.sh --grafana-only
#
set -euo pipefail

cd "$(dirname "$0")"

RUN_DIR=".run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"
BRIDGE_PID_FILE="$RUN_DIR/bridge.pid"
NAVASAN_PID_FILE="$RUN_DIR/navasan.pid"

STOP_BACKEND=true
STOP_FRONTEND=true
STOP_BRIDGE=true
STOP_NAVASAN=true
STOP_GRAFANA=true

GRAFANA_PORT="${GRAFANA_PORT:-53841}"
GRAFANA_COMPOSE_FILE="${GRAFANA_COMPOSE_FILE:-./compose/docker-compose-grafana.yaml}"
GRAFANA_COMPOSE_PROJECT="${GRAFANA_COMPOSE_PROJECT:-go-money-grafana}"

# Same env file as run-native.sh, only so the reported navasan port is accurate.
NAVASAN_ENV_FILE="${NAVASAN_ENV_FILE:-./.env.navasan}"
if [[ -f "$NAVASAN_ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$NAVASAN_ENV_FILE"
  set +a
fi
NAVASAN_PORT="${NAVASAN_PORT:-${PORT:-8088}}"

usage() {
  sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
  case "$arg" in
    --backend-only) STOP_FRONTEND=false; STOP_BRIDGE=false; STOP_NAVASAN=false; STOP_GRAFANA=false ;;
    --frontend-only) STOP_BACKEND=false; STOP_BRIDGE=false; STOP_NAVASAN=false; STOP_GRAFANA=false ;;
    --bridge-only) STOP_BACKEND=false; STOP_FRONTEND=false; STOP_NAVASAN=false; STOP_GRAFANA=false ;;
    --navasan-only) STOP_BACKEND=false; STOP_FRONTEND=false; STOP_BRIDGE=false; STOP_GRAFANA=false ;;
    --grafana-only) STOP_BACKEND=false; STOP_FRONTEND=false; STOP_BRIDGE=false; STOP_NAVASAN=false ;;
    -h|--help) usage; exit 0 ;;
    *) echo "!! unknown option: $arg" >&2; usage >&2; exit 1 ;;
  esac
done

# Terminate a process and everything it spawned (npm -> ng serve -> workers).
kill_tree() {
  local pid=$1 child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    kill_tree "$child"
  done
  kill "$pid" 2>/dev/null || true
}

stop_one() {
  local pid_file=$1 label=$2

  if [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
    local pid
    pid=$(cat "$pid_file")
    kill_tree "$pid"

    for _ in $(seq 1 20); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.5
    done

    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null || true
    fi

    echo ">> stopped $label (pid $pid)"
  else
    echo ">> $label is not running"
  fi

  rm -f "$pid_file"
}

# Report anything still holding a port we just released (nothing is killed here).
report_port() {
  local port=$1
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "!! port $port is still in use:"
    lsof -nP -iTCP:"$port" -sTCP:LISTEN | tail -n +2 | sed 's/^/     /'
  fi
}

if $STOP_BACKEND; then
  stop_one "$BACKEND_PID_FILE" "backend"
  report_port "${GRPC_PORT:-52055}"
  report_port "${OPS_HTTP_PORT:-52056}"
fi

# Stopped right after the backend, which is its only consumer.
if $STOP_NAVASAN; then
  stop_one "$NAVASAN_PID_FILE" "navasan translator"
  report_port "$NAVASAN_PORT"
fi

if $STOP_FRONTEND; then
  stop_one "$FRONTEND_PID_FILE" "frontend"
  report_port "${FRONTEND_PORT:-4200}"
fi

if $STOP_BRIDGE; then
  stop_one "$BRIDGE_PID_FILE" "android bridge"
  report_port "${BRIDGE_PORT:-8788}"
fi

if $STOP_GRAFANA; then
  if command -v docker >/dev/null 2>&1 && [[ -f "$GRAFANA_COMPOSE_FILE" ]]; then
    docker compose -f "$GRAFANA_COMPOSE_FILE" --project-name "$GRAFANA_COMPOSE_PROJECT" stop >/dev/null 2>&1 || true
    echo ">> stopped grafana"
  else
    echo ">> grafana is not running"
  fi
  report_port "$GRAFANA_PORT"
fi
