#!/usr/bin/env bash
#
# Bring up the Go Money stack natively (no Docker):
#   - Go backend        (this project)
#   - Angular dev server (this project)
#
# The database is NOT managed here. It lives in the shared Postgres container
# `shared_postgres` (docker-infrastructure), which other projects use too.
#
# Usage:
#   ./run-native.sh                  # backend + frontend
#   ./run-native.sh --build          # rebuild the Go binary first, then start
#   ./run-native.sh --backend-only   # skip the Angular dev server
#   ./run-native.sh --frontend-only  # skip the Go backend
#
set -euo pipefail

cd "$(dirname "$0")"

export PATH="/opt/homebrew/opt/postgresql@18/bin:$PATH"

# ---- database (shared Postgres; this project has its own role + database) ----
# Dedicated role `gomoney` owns database `money`, so these credentials cannot
# read any other project's tables on the shared server.
# NOTE: local-only password; the shared Postgres is bound to 127.0.0.1.
export DB_HOST="${DB_HOST:-127.0.0.1}"
export DB_PORT="${DB_PORT:-5432}"
export DB_USER="${DB_USER:-gomoney}"
export DB_PASSWORD="${DB_PASSWORD:-yV2ta2pDeqzpUjt1Qg0wdPT2}"
export DB_DB="${DB_DB:-money}"

# ---- ports / hostnames ----
export GRPC_PORT="${GRPC_PORT:-52055}"
export OPS_HTTP_PORT="${OPS_HTTP_PORT:-52056}"
FRONTEND_PORT="${FRONTEND_PORT:-4200}"
FRONTEND_HOST="${FRONTEND_HOST:-0.0.0.0}"
PUBLIC_HOSTNAME="${PUBLIC_HOSTNAME:-bazari.localhost}"

# ---- app config ----
export CURRENCY_CONFIG_BASE_CURRENCY="${CURRENCY_CONFIG_BASE_CURRENCY:-USD}"
export EXCHANGE_RATES_URL="${EXCHANGE_RATES_URL:-http://go-money-exchange-rates.s3-website.eu-north-1.amazonaws.com/latest.json}"
export MCP_DOCS_DIR="${MCP_DOCS_DIR:-./mcp}"

RUN_DIR=".run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"

BUILD=false
START_BACKEND=true
START_FRONTEND=true

usage() {
  sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
  case "$arg" in
    --build) BUILD=true ;;
    --backend-only) START_FRONTEND=false ;;
    --frontend-only) START_BACKEND=false ;;
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
  for _ in $(seq 1 "$tries"); do
    if (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null; then
      return 0
    fi
    sleep 0.5
  done
  echo "!! $label did not become ready on ${host}:${port}" >&2
  return 1
}

# The MCP server reads its docs from $MCP_DOCS_DIR. Mirror the Docker image,
# which ships only the essential subset, instead of committing a duplicate tree.
ensure_mcp_docs() {
  local dir="${MCP_DOCS_DIR:-./mcp}"

  [[ "$dir" != "./mcp" ]] && return 0
  [[ -d "$dir" ]] && return 0

  local sources=(
    "docs/mcp/GOLDEN-RULES.md:$dir/GOLDEN-RULES.md"
    "docs/schema/QUICK-REF.md:$dir/schema/QUICK-REF.md"
    "docs/analytics/QUICK-REF.md:$dir/analytics/QUICK-REF.md"
  )

  echo ">> preparing MCP docs in $dir"
  local pair src dest
  for pair in "${sources[@]}"; do
    src="${pair%%:*}"
    dest="${pair#*:}"
    mkdir -p "$(dirname "$dest")"
    cp "$src" "$dest"
  done
}

# ------------------------------ backend ------------------------------
if $START_BACKEND; then
  if is_running "$BACKEND_PID_FILE"; then
    echo ">> backend already running (pid $(cat "$BACKEND_PID_FILE"))"
  else
    if $BUILD; then
      echo ">> building bin/go-money-server"
      go build -o bin/go-money-server ./cmd/server
    fi

    if [[ ! -x bin/go-money-server ]]; then
      echo "!! bin/go-money-server not found. Run: ./run-native.sh --build" >&2
      exit 1
    fi

    # Read-only preflight: never starts or stops the shared DB.
    if ! (exec 3<>"/dev/tcp/${DB_HOST}/${DB_PORT}") 2>/dev/null; then
      echo "!! cannot reach Postgres at ${DB_HOST}:${DB_PORT}" >&2
      echo "   '${DB_DB}' is hosted by the shared service 'shared_postgres', which is" >&2
      echo "   managed separately from this project (docker-infrastructure)." >&2
      exit 1
    fi

    echo ">> starting backend (grpc :$GRPC_PORT, ops :$OPS_HTTP_PORT)"
    ensure_mcp_docs
    nohup ./bin/go-money-server > "$RUN_DIR/backend.log" 2>&1 &
    echo $! > "$BACKEND_PID_FILE"

    if ! wait_tcp 127.0.0.1 "$GRPC_PORT" "backend"; then
      tail -n 20 "$RUN_DIR/backend.log" >&2
      exit 1
    fi
  fi
fi

# ------------------------------ frontend ------------------------------
if $START_FRONTEND; then
  if is_running "$FRONTEND_PID_FILE"; then
    echo ">> frontend already running (pid $(cat "$FRONTEND_PID_FILE"))"
  else
    if [[ ! -d frontend/node_modules ]]; then
      echo "!! frontend/node_modules is missing. Run: (cd frontend && npm install)" >&2
      exit 1
    fi

    echo ">> starting frontend (ng serve on ${FRONTEND_HOST}:${FRONTEND_PORT})"
    pushd frontend >/dev/null
    nohup npm start -- --host "$FRONTEND_HOST" --port "$FRONTEND_PORT" > "../$RUN_DIR/frontend.log" 2>&1 &
    echo $! > "../$FRONTEND_PID_FILE"
    popd >/dev/null

    if ! wait_tcp 127.0.0.1 "$FRONTEND_PORT" "frontend" 160; then
      tail -n 30 "$RUN_DIR/frontend.log" >&2
      exit 1
    fi
  fi
fi

echo
echo "up:"
if $START_BACKEND; then
  echo "  backend   http://127.0.0.1:$GRPC_PORT   (ops :$OPS_HTTP_PORT, log $RUN_DIR/backend.log)"
fi
if $START_FRONTEND; then
  echo "  frontend  http://${PUBLIC_HOSTNAME}:${FRONTEND_PORT}   (log $RUN_DIR/frontend.log)"
  echo
  echo "  the frontend reaches the API via the 'customApiHost' cookie. If the app"
  echo "  cannot reach the backend, run this in the browser console on the app page:"
  echo "    document.cookie = \"customApiHost=http://127.0.0.1:$GRPC_PORT; path=/; samesite=lax\""
fi
echo
echo "stop with: ./stop-native.sh"
