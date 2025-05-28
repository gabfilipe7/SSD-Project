#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"


BASE_DIR="$SCRIPT_DIR/../Nodes"
JAR_PATH="$SCRIPT_DIR/../Public-Ledger/build/libs/Public-Ledger-1.0-SNAPSHOT-all.jar"


mkdir -p "$BASE_DIR"

start_node() {
  PORT=$1
  IS_BOOTSTRAP=$2
  NODE_DIR="$BASE_DIR/node_$PORT"
  mkdir -p "$NODE_DIR"

  if [ "$IS_BOOTSTRAP" = true ]; then
    gnome-terminal -- bash -c "cd \"$NODE_DIR\" && java -jar \"$JAR_PATH\" --port=$PORT --bootstrap true; exec bash"
  else
    gnome-terminal -- bash -c "cd \"$NODE_DIR\" && java -jar \"$JAR_PATH\" --port=$PORT; exec bash"
  fi
}

start_node 5000 true
start_node 5001 true
start_node 5002 false
start_node 5003 false
start_node 5004 false
