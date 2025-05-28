#!/bin/bash

PORTS=(5001 5002 5003)

kill_node() {
  local PORT=$1
  PID=$(lsof -t -iTCP:"$PORT" -sTCP:LISTEN)

  if [ -n "$PID" ]; then
    echo "Killing node on port $PORT (PID $PID)…"
    kill "$PID" && echo "  → Sent TERM to $PID"
  else
    echo "No process found listening on port $PORT"
  fi
}

for p in "${PORTS[@]}"; do
  kill_node "$p"
done
