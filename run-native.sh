#!/usr/bin/env bash
#
# Run the Go Money backend and Angular frontend natively (no Docker).
#
# Postgres runs via Homebrew (postgresql@18) with the database restored from
# the previous Docker setup. See docs in README/CLAUDE.md for details.
#
# Usage:
#   ./run-native.sh          # start the backend and frontend
#   ./run-native.sh --build  # rebuild the backend first, then start both
#
set -euo pipefail

cd "$(dirname "$0")"

export PATH="/opt/homebrew/opt/postgresql@18/bin:$PATH"

# Database (native Postgres, not the Docker service hostname "db")
export DB_HOST="${DB_HOST:-127.0.0.1}"
export DB_PORT="${DB_PORT:-5432}"
export DB_USER="${DB_USER:-postgres}"
export DB_PASSWORD="${DB_PASSWORD:-postgres}"
export DB_DB="${DB_DB:-money}"

# Server ports
export GRPC_PORT="${GRPC_PORT:-52055}"
export OPS_HTTP_PORT="${OPS_HTTP_PORT:-52056}"
FRONTEND_PORT="${FRONTEND_PORT:-4200}"
FRONTEND_HOST="${FRONTEND_HOST:-0.0.0.0}"

# App config
export CURRENCY_CONFIG_BASE_CURRENCY="${CURRENCY_CONFIG_BASE_CURRENCY:-USD}"
export EXCHANGE_RATES_URL="${EXCHANGE_RATES_URL:-http://go-money-exchange-rates.s3-website.eu-north-1.amazonaws.com/latest.json}"
export MCP_DOCS_DIR="${MCP_DOCS_DIR:-./mcp}"

RUN_DIR=".run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"
BUILD=false

usage() {
  echo "Usage: $0 [--build]"
}

for arg in "$@"; do
  case "$arg" in
    --build) BUILD=true ;;
    -h|--help) usage; exit 0 ;;
    *) echo "!! unknown option: $arg" >&2; usage >&2; exit 1 ;;
  esac
done

mkdir -p "$RUN_DIR"

is_running() {
  local pid_file=$1
  [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null
}

wait_tcp() {
  local host=$1 port=$2 label=$3 tries=${4:-60}
  for ((attempt = 0; attempt < tries; attempt++)); do
    if (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null; then
      return 0
    fi
    sleep 0.5
  done
  echo "!! $label did not become ready on ${host}:${port}" >&2
  return 1
}

# npm starts ng serve as a child; stop both using the PID recorded by this script.
kill_tree() {
  local pid=$1 child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    kill_tree "$child"
  done
  kill "$pid" 2>/dev/null || true
}

BACKEND_PID=""
FRONTEND_PID=""
STARTED_BACKEND=false
STARTED_FRONTEND=false
STARTUP_COMPLETE=false

cleanup_failed_start() {
  local exit_status=$?
  trap - EXIT

  if ! $STARTUP_COMPLETE; then
    if $STARTED_FRONTEND; then
      kill_tree "$FRONTEND_PID"
      rm -f "$FRONTEND_PID_FILE"
    fi
    if $STARTED_BACKEND; then
      kill_tree "$BACKEND_PID"
      rm -f "$BACKEND_PID_FILE"
    fi
  fi

  exit "$exit_status"
}
trap cleanup_failed_start EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Check prerequisites before starting either service.
if ! is_running "$FRONTEND_PID_FILE"; then
  if [[ ! -d frontend/node_modules ]]; then
    echo "!! frontend/node_modules is missing. Run: (cd frontend && npm install)" >&2
    exit 1
  fi
  if ! command -v npm >/dev/null 2>&1; then
    echo "!! npm is not installed or not on PATH" >&2
    exit 1
  fi
fi

if ! is_running "$BACKEND_PID_FILE"; then
  if $BUILD; then
    echo ">> building bin/go-money-server"
    go build -o bin/go-money-server ./cmd/server
  fi

  if [[ ! -x bin/go-money-server ]]; then
    echo "!! bin/go-money-server not found. Run: ./run-native.sh --build" >&2
    exit 1
  fi
fi

if is_running "$BACKEND_PID_FILE"; then
  BACKEND_PID=$(cat "$BACKEND_PID_FILE")
  echo ">> backend already running (pid $BACKEND_PID)"
else
  rm -f "$BACKEND_PID_FILE"
  echo ">> starting go-money-server (grpc :$GRPC_PORT, ops :$OPS_HTTP_PORT, db $DB_HOST:$DB_PORT/$DB_DB)"
  nohup ./bin/go-money-server > "$RUN_DIR/backend.log" 2>&1 &
  BACKEND_PID=$!
  STARTED_BACKEND=true
  echo "$BACKEND_PID" > "$BACKEND_PID_FILE"

  if ! wait_tcp 127.0.0.1 "$GRPC_PORT" "backend"; then
    tail -n 20 "$RUN_DIR/backend.log" >&2
    exit 1
  fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "!! backend exited during startup:" >&2
    tail -n 20 "$RUN_DIR/backend.log" >&2
    exit 1
  fi
fi

if is_running "$FRONTEND_PID_FILE"; then
  FRONTEND_PID=$(cat "$FRONTEND_PID_FILE")
  echo ">> frontend already running (pid $FRONTEND_PID)"
else
  rm -f "$FRONTEND_PID_FILE"
  echo ">> starting frontend (ng serve on ${FRONTEND_HOST}:${FRONTEND_PORT})"
  pushd frontend >/dev/null
  nohup npm start -- --host "$FRONTEND_HOST" --port "$FRONTEND_PORT" > "../$RUN_DIR/frontend.log" 2>&1 &
  FRONTEND_PID=$!
  STARTED_FRONTEND=true
  echo "$FRONTEND_PID" > "../$FRONTEND_PID_FILE"
  popd >/dev/null

  if ! wait_tcp 127.0.0.1 "$FRONTEND_PORT" "frontend" 160; then
    tail -n 30 "$RUN_DIR/frontend.log" >&2
    exit 1
  fi
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    echo "!! frontend exited during startup:" >&2
    tail -n 30 "$RUN_DIR/frontend.log" >&2
    exit 1
  fi
fi

STARTUP_COMPLETE=true

echo
echo "up:"
echo "  backend   grpc :$GRPC_PORT, ops :$OPS_HTTP_PORT (log $RUN_DIR/backend.log)"
echo "  frontend  http://localhost:$FRONTEND_PORT (log $RUN_DIR/frontend.log)"
echo
echo "Set the API host in the browser console, then refresh the page:"
echo "  document.cookie = \"customApiHost=http://127.0.0.1:$GRPC_PORT; path=/; samesite=lax\""
echo "For access from another device, replace 127.0.0.1 with this machine's LAN IP."
echo
echo "stop with: ./stop-native.sh"
