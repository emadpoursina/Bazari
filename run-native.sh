#!/usr/bin/env bash
#
# Run the Go Money backend, Angular frontend, Android transaction bridge, and
# navasan currency-rate translator natively (no Docker).
#
# Postgres runs via Homebrew (postgresql@18) with the database restored from
# the previous Docker setup. See docs in README/CLAUDE.md for details.
#
# The Android bridge reads its tokens/paths from .env.android-bridge
# (see .env.android-bridge.example and specs/001-android-txn-capture/bridge-runbook.md).
# The navasan translator reads its API key from .env.navasan (see
# .env.navasan.example) and the backend gets EXCHANGE_RATES_URL from it, so
# neither has to be exported by hand on every run.
#
# Usage:
#   ./run-native.sh               # start backend, frontend, bridge, navasan, grafana
#   ./run-native.sh --build       # rebuild the binaries first, then start everything
#   ./run-native.sh --no-bridge   # skip the android bridge
#   ./run-native.sh --no-navasan  # skip the rate translator (falls back to the S3 feed)
#   ./run-native.sh --no-grafana  # skip Grafana (dashboard link in the UI stays empty)
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

# App config (EXCHANGE_RATES_URL is resolved after the navasan config loads)
export CURRENCY_CONFIG_BASE_CURRENCY="${CURRENCY_CONFIG_BASE_CURRENCY:-USD}"
export MCP_DOCS_DIR="${MCP_DOCS_DIR:-./mcp}"

# Grafana (UI "Navigate to grafana" reads GRAFANA_CONFIG_URL from the backend).
# Host bind is loopback-only; Caddy serves http://grafana.bazari.localhost.
GRAFANA_PORT="${GRAFANA_PORT:-53841}"
GRAFANA_PUBLIC_HOST="${GRAFANA_PUBLIC_HOST:-grafana.bazari.localhost}"
GRAFANA_COMPOSE_FILE="${GRAFANA_COMPOSE_FILE:-./compose/docker-compose-grafana.yaml}"
GRAFANA_COMPOSE_PROJECT="${GRAFANA_COMPOSE_PROJECT:-go-money-grafana}"

# Android transaction bridge (tokens/paths come from the env file)
BRIDGE_ENV_FILE="${BRIDGE_ENV_FILE:-./.env.android-bridge}"

RUN_DIR=".run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"
BRIDGE_PID_FILE="$RUN_DIR/bridge.pid"
NAVASAN_PID_FILE="$RUN_DIR/navasan.pid"
BUILD=false
START_BRIDGE=true
START_NAVASAN=true
START_GRAFANA=true

usage() {
  echo "Usage: $0 [--build] [--no-bridge] [--no-navasan] [--no-grafana]"
}

for arg in "$@"; do
  case "$arg" in
    --build) BUILD=true ;;
    --no-bridge) START_BRIDGE=false ;;
    --no-navasan) START_NAVASAN=false ;;
    --no-grafana) START_GRAFANA=false ;;
    -h|--help) usage; exit 0 ;;
    *) echo "!! unknown option: $arg" >&2; usage >&2; exit 1 ;;
  esac
done

# Navasan translator (custom currency rates): load the API key and any
# overrides from .env.navasan once, then derive the feed URL the backend polls.
# .env.navasan is gitignored — never commit the key.
NAVASAN_ENV_FILE="${NAVASAN_ENV_FILE:-./.env.navasan}"
if [[ -f "$NAVASAN_ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$NAVASAN_ENV_FILE"
  set +a
fi
NAVASAN_PORT="${NAVASAN_PORT:-${PORT:-8088}}"

if $START_NAVASAN; then
  export EXCHANGE_RATES_URL="${EXCHANGE_RATES_URL:-http://127.0.0.1:$NAVASAN_PORT/latest.json}"
else
  export EXCHANGE_RATES_URL="${EXCHANGE_RATES_URL:-http://go-money-exchange-rates.s3-website.eu-north-1.amazonaws.com/latest.json}"
fi

mkdir -p "$RUN_DIR"

is_running() {
  local pid_file=$1
  [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null
}

# Single connect attempt with a hard ~1s cap. Bash's /dev/tcp has no timeout and
# blocks ~75s per attempt against an unroutable address, which used to make
# wait_tcp hang for tens of minutes when the target never came up.
tcp_probe() {
  local host=$1 port=$2
  if command -v nc >/dev/null 2>&1; then
    nc -z -G 1 "$host" "$port" >/dev/null 2>&1
  else
    (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null
  fi
}

# wait_tcp HOST PORT LABEL [TRIES] [PID]
# Pass PID so we bail out as soon as the process we are waiting for dies,
# instead of burning every remaining attempt.
wait_tcp() {
  local host=$1 port=$2 label=$3 tries=${4:-60} pid=${5:-}
  local attempt
  for ((attempt = 0; attempt < tries; attempt++)); do
    if [[ -n "$pid" ]] && ! kill -0 "$pid" 2>/dev/null; then
      echo "!! $label exited during startup" >&2
      return 1
    fi
    if tcp_probe "$host" "$port"; then
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
NAVASAN_PID=""
STARTED_BACKEND=false
STARTED_FRONTEND=false
STARTED_BRIDGE=false
STARTED_NAVASAN=false
STARTUP_COMPLETE=false

cleanup_failed_start() {
  local exit_status=$?
  trap - EXIT

  if ! $STARTUP_COMPLETE; then
    if $STARTED_BRIDGE; then
      kill_tree "$BRIDGE_PID"
      rm -f "$BRIDGE_PID_FILE"
    fi
    if $STARTED_NAVASAN; then
      kill_tree "$NAVASAN_PID"
      rm -f "$NAVASAN_PID_FILE"
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

if $START_NAVASAN && ! is_running "$NAVASAN_PID_FILE"; then
  if $BUILD; then
    echo ">> building bin/go-money-navasan-translator"
    go build -o bin/go-money-navasan-translator ./cmd/navasan-translator
  fi

  if [[ ! -x bin/go-money-navasan-translator ]]; then
    echo "!! bin/go-money-navasan-translator not found. Run: ./run-native.sh --build" >&2
    exit 1
  fi

  if [[ -z "${NAVASAN_API_KEY:-}" || "$NAVASAN_API_KEY" == replace-with-* ]]; then
    echo "!! NAVASAN_API_KEY is not set (or still the placeholder)" >&2
    echo "   copy .env.navasan.example to .env.navasan, add your key, and retry (or pass --no-navasan)" >&2
    exit 1
  fi
fi

# The rate feed must be up before the backend: the backend syncs on startup.
if ! $START_NAVASAN; then
  echo ">> skipping navasan translator (--no-navasan, rates from $EXCHANGE_RATES_URL)"
elif is_running "$NAVASAN_PID_FILE"; then
  NAVASAN_PID=$(cat "$NAVASAN_PID_FILE")
  echo ">> navasan translator already running (pid $NAVASAN_PID)"
else
  rm -f "$NAVASAN_PID_FILE"
  echo ">> starting navasan-translator (http://127.0.0.1:$NAVASAN_PORT/latest.json -> $EXCHANGE_RATES_URL)"
  PORT="$NAVASAN_PORT" nohup ./bin/go-money-navasan-translator > "$RUN_DIR/navasan.log" 2>&1 &
  NAVASAN_PID=$!
  STARTED_NAVASAN=true
  echo "$NAVASAN_PID" > "$NAVASAN_PID_FILE"

  if ! wait_tcp 127.0.0.1 "$NAVASAN_PORT" "navasan translator" 30 "$NAVASAN_PID"; then
    tail -n 20 "$RUN_DIR/navasan.log" >&2
    exit 1
  fi
  if ! kill -0 "$NAVASAN_PID" 2>/dev/null; then
    echo "!! navasan translator exited during startup:" >&2
    tail -n 20 "$RUN_DIR/navasan.log" >&2
    exit 1
  fi

  # Best effort: a bad API key would otherwise fail silently until rates stop moving.
  if command -v curl >/dev/null 2>&1 && ! curl -fsS -m 15 "http://127.0.0.1:$NAVASAN_PORT/latest.json" >/dev/null 2>&1; then
    echo "!! navasan feed not serving yet (check $RUN_DIR/navasan.log) — continuing anyway" >&2
  fi
fi

if ! $START_GRAFANA; then
  echo ">> skipping local grafana (--no-grafana)"
elif ! command -v docker >/dev/null 2>&1; then
  echo "!! docker is required for Grafana (or pass --no-grafana)" >&2
  exit 1
else
  export GRAFANA_CONFIG_URL="${GRAFANA_CONFIG_URL:-http://${GRAFANA_PUBLIC_HOST}}"
  export GRAFANA_PUBLIC_HOST
  export GRAFANA_PORT
  echo ">> starting grafana (${GRAFANA_CONFIG_URL})"
  docker compose -f "$GRAFANA_COMPOSE_FILE" --project-name "$GRAFANA_COMPOSE_PROJECT" up -d
  if ! wait_tcp 127.0.0.1 "$GRAFANA_PORT" "grafana" 60; then
    docker compose -f "$GRAFANA_COMPOSE_FILE" --project-name "$GRAFANA_COMPOSE_PROJECT" logs --tail 30 >&2
    exit 1
  fi
  if is_running "$BACKEND_PID_FILE"; then
    echo "!! backend is already running and will not see GRAFANA_CONFIG_URL until you restart it:" >&2
    echo "   ./stop-native.sh --backend-only && ./run-native.sh" >&2
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

  if ! wait_tcp 127.0.0.1 "$GRPC_PORT" "backend" 60 "$BACKEND_PID"; then
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

  if ! wait_tcp 127.0.0.1 "$FRONTEND_PORT" "frontend" 160 "$FRONTEND_PID"; then
    tail -n 30 "$RUN_DIR/frontend.log" >&2
    exit 1
  fi
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    echo "!! frontend exited during startup:" >&2
    tail -n 30 "$RUN_DIR/frontend.log" >&2
    exit 1
  fi
fi

BRIDGE_HOST=""
BRIDGE_PORT=""
if $START_BRIDGE; then
  # Load the bridge config now (nothing else is started after this point).
  set -a
  # shellcheck disable=SC1090
  source "$BRIDGE_ENV_FILE"
  set +a
  # BRIDGE_LISTEN is a net.Listen address: "host:port", ":port" or "[::]:port".
  # Probe the host the bridge actually binds to (it may be a LAN/Tailscale IP,
  # not 127.0.0.1), but map wildcard hosts to loopback for the check.
  BRIDGE_LISTEN_ADDR="${BRIDGE_LISTEN:-:8787}"
  BRIDGE_PORT="${BRIDGE_LISTEN_ADDR##*:}"
  BRIDGE_HOST="${BRIDGE_LISTEN_ADDR%:*}"
  case "$BRIDGE_HOST" in
    ""|"0.0.0.0"|"*"|"::"|"[::]") BRIDGE_HOST="127.0.0.1" ;;
    \[*\]) BRIDGE_HOST="${BRIDGE_HOST#[}"; BRIDGE_HOST="${BRIDGE_HOST%]}" ;;
  esac
fi

if ! $START_BRIDGE; then
  echo ">> skipping android bridge (--no-bridge)"
elif is_running "$BRIDGE_PID_FILE"; then
  BRIDGE_PID=$(cat "$BRIDGE_PID_FILE")
  echo ">> android bridge already running (pid $BRIDGE_PID)"
else
  rm -f "$BRIDGE_PID_FILE"
  echo ">> starting android-bridge (listen ${BRIDGE_HOST}:$BRIDGE_PORT, gomoney ${GOMONEY_URL:-http://127.0.0.1:8080})"
  nohup ./bin/go-money-android-bridge > "$RUN_DIR/bridge.log" 2>&1 &
  BRIDGE_PID=$!
  STARTED_BRIDGE=true
  echo "$BRIDGE_PID" > "$BRIDGE_PID_FILE"

  if ! wait_tcp "$BRIDGE_HOST" "$BRIDGE_PORT" "android bridge" 30 "$BRIDGE_PID"; then
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
  echo "  android-bridge  http://$BRIDGE_HOST:$BRIDGE_PORT (log $RUN_DIR/bridge.log)"
else
  echo "  android-bridge  skipped"
fi
if $START_NAVASAN; then
  echo "  navasan rates   http://127.0.0.1:$NAVASAN_PORT/latest.json (log $RUN_DIR/navasan.log)"
else
  echo "  navasan rates   skipped ($EXCHANGE_RATES_URL)"
fi
if $START_GRAFANA; then
  echo "  grafana         $GRAFANA_CONFIG_URL  (admin / ${GRAFANA_ADMIN_PASSWORD:-admin})"
else
  echo "  grafana         skipped"
fi
echo
echo "Set the API host in the browser console, then refresh the page:"
echo "  document.cookie = \"customApiHost=http://127.0.0.1:$GRPC_PORT; path=/; samesite=lax\""
echo "For access from another device, replace 127.0.0.1 with this machine's LAN IP."
echo "The Android app talks to the bridge directly (LAN IP + bridge token from $BRIDGE_ENV_FILE)."
echo
echo "stop with: ./stop-native.sh"
