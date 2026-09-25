#!/usr/bin/env bash
#
# Run the Go Money backend natively (no Docker).
#
# Postgres runs via Homebrew (postgresql@18) with the database restored from
# the previous Docker setup. See docs in README/CLAUDE.md for details.
#
# Usage:
#   ./run-native.sh          # run the prebuilt binary
#   ./run-native.sh --build  # rebuild from source first, then run
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

# App config
export CURRENCY_CONFIG_BASE_CURRENCY="${CURRENCY_CONFIG_BASE_CURRENCY:-USD}"
export EXCHANGE_RATES_URL="${EXCHANGE_RATES_URL:-http://go-money-exchange-rates.s3-website.eu-north-1.amazonaws.com/latest.json}"
export MCP_DOCS_DIR="${MCP_DOCS_DIR:-./mcp}"

if [[ "${1:-}" == "--build" ]]; then
  echo ">> building bin/go-money-server"
  go build -o bin/go-money-server ./cmd/server
fi

if [[ ! -x bin/go-money-server ]]; then
  echo "!! bin/go-money-server not found. Run: ./run-native.sh --build" >&2
  exit 1
fi

echo ">> starting go-money-server (grpc :$GRPC_PORT, ops :$OPS_HTTP_PORT, db $DB_HOST:$DB_PORT/$DB_DB)"
exec ./bin/go-money-server
