#!/usr/bin/env bash
#
# Run the Go Money backend, Angular frontend, and Android transaction bridge
# natively (no Docker).
#
# Postgres runs via Homebrew (postgresql@18) with the database restored from
# the previous Docker setup. See docs in README/CLAUDE.md for details.
#
# The Android bridge reads its tokens/paths from .env.android-bridge
# (see .env.android-bridge.example and specs/001-android-txn-capture/bridge-runbook.md).
#
# Usage:
#   ./run-native.sh             # start the backend, frontend, and android bridge
#   ./run-native.sh --build     # rebuild the binaries first, then start everything
#   ./run-native.sh --no-bridge # start only the backend and frontend
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

# Android transaction bridge (tokens/paths come from the env file)
BRIDGE_ENV_FILE="${BRIDGE_ENV_FILE:-./.env.android-bridge}"

RUN_DIR=".run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"
BRIDGE_PID_FILE="$RUN_DIR/bridge.pid"
BUILD=false
START_BRIDGE=true

usage() {
  echo "Usage: $0 [--build] [--no-bridge]"
}

for arg in "$@"; do
  case "$arg" in
    --build) BUILD=true ;;
    --no-bridge) START_BRIDGE=false ;;
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
BRIDGE_PID=""
STARTED_BACKEND=false
STARTED_FRONTEND=false
STARTED_BRIDGE=false
STARTUP_COMPLETE=false

cleanup_failed_start() {
  local exit_status=$?
  trap - EXIT

  if ! $STARTUP_COMPLETE; then
    if $STARTED_BRIDGE; then
      kill_tree "$BRIDGE_PID"
      rm -f "$BRIDGE_PID_FILE"
    fi
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

if $START_BRIDGE && ! is_running "$BRIDGE_PID_FILE"; then
  if [[ ! -f "$BRIDGE_ENV_FILE" ]]; then
    echo "!! bridge env file missing: $BRIDGE_ENV_FILE" >&2
    echo "   copy .env.android-bridge.example, fill in the values, and retry (or pass --no-bridge)" >&2
    exit 1
  fi

  if $BUILD; then
    echo ">> building bin/go-money-android-bridge"
    go build -o bin/go-money-android-bridge ./cmd/android-bridge
  fi

  if [[ ! -x bin/go-money-android-bridge ]]; then
    echo "!! bin/go-money-android-bridge not found. Run: ./run-native.sh --build" >&2
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

BRIDGE_PORT=""
if $START_BRIDGE; then
  # Load the bridge config now (nothing else is started after this point).
  set -a
  # shellcheck disable=SC1090
  source "$BRIDGE_ENV_FILE"
  set +a
  BRIDGE_PORT="${BRIDGE_LISTEN:-:8787}"
  BRIDGE_PORT="${BRIDGE_PORT##*:}"
fi

if ! $START_BRIDGE; then
  echo ">> skipping android bridge (--no-bridge)"
elif is_running "$BRIDGE_PID_FILE"; then
  BRIDGE_PID=$(cat "$BRIDGE_PID_FILE")
  echo ">> android bridge already running (pid $BRIDGE_PID)"
else
  rm -f "$BRIDGE_PID_FILE"
  echo ">> starting android-bridge (listen :$BRIDGE_PORT, gomoney ${GOMONEY_URL:-http://127.0.0.1:8080})"
  nohup ./bin/go-money-android-bridge > "$RUN_DIR/bridge.log" 2>&1 &
  BRIDGE_PID=$!
  STARTED_BRIDGE=true
  echo "$BRIDGE_PID" > "$BRIDGE_PID_FILE"

  if ! wait_tcp 127.0.0.1 "$BRIDGE_PORT" "android bridge" 30; then
    tail -n 20 "$RUN_DIR/bridge.log" >&2
    exit 1
  fi
  if ! kill -0 "$BRIDGE_PID" 2>/dev/null; then
    echo "!! android bridge exited during startup:" >&2
    tail -n 20 "$RUN_DIR/bridge.log" >&2
    exit 1
  fi
fi

STARTUP_COMPLETE=true

echo
echo "up:"
echo "  backend         grpc :$GRPC_PORT, ops :$OPS_HTTP_PORT (log $RUN_DIR/backend.log)"
echo "  frontend        http://localhost:$FRONTEND_PORT (log $RUN_DIR/frontend.log)"
if $START_BRIDGE; then
  echo "  android-bridge  http://127.0.0.1:$BRIDGE_PORT (log $RUN_DIR/bridge.log)"
else
  echo "  android-bridge  skipped"
fi
echo
echo "Set the API host in the browser console, then refresh the page:"
echo "  document.cookie = \"customApiHost=http://127.0.0.1:$GRPC_PORT; path=/; samesite=lax\""
echo "For access from another device, replace 127.0.0.1 with this machine's LAN IP."
echo "The Android app talks to the bridge directly (LAN IP + bridge token from $BRIDGE_ENV_FILE)."
echo
echo "stop with: ./stop-native.sh"
