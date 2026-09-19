#!/usr/bin/env bash
set -euo pipefail

NAME="${NAME:-reservation-valkey}"
PORT="${PORT:-6379}"
IMAGE="${IMAGE:-valkey/valkey:8}"
CONTAINER_BIN="${CONTAINER_BIN:-container}"
if ! command -v "${CONTAINER_BIN}" >/dev/null 2>&1; then
  if [[ -x /opt/homebrew/bin/container ]]; then
    CONTAINER_BIN=/opt/homebrew/bin/container
  fi
fi

"${CONTAINER_BIN}" system status >/dev/null
"${CONTAINER_BIN}" stop "${NAME}" >/dev/null 2>&1 || true
exec "${CONTAINER_BIN}" run -d --rm --name "${NAME}" -p "127.0.0.1:${PORT}:6379" "${IMAGE}"
