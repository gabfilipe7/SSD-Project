#!/bin/bash

BASE_DIR="/home/gabriel/Documents/MSI/SSD-project/SSD-Project/Nodes"
JAR_PATH="/home/gabriel/Documents/MSI/SSD-project/SSD-Project/Public-Ledger/build/libs/Public-Ledger-1.0-SNAPSHOT-all.jar"

mkdir -p "$BASE_DIR"


start_node() {
  PORT=$1
  IS_BOOTSTRAP=$2
  NODE_DIR="$BASE_DIR/node_$PORT"
  mkdir -p "$NODE_DIR"

  if [ "$IS_BOOTSTRAP" = true ]; then
    gnome-terminal -- bash -c "cd $NODE_DIR && java -jar $JAR_PATH --port=$PORT --bootstrap true; exec bash"
  else
    gnome-terminal -- bash -c "cd $NODE_DIR && java -jar $JAR_PATH --port=$PORT; exec bash"
  fi
}


start_node 5000 true

start_node 5001 false
start_node 5002 false
start_node 5003 false
start_node 5004 false