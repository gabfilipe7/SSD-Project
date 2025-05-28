#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BASE_DIR="$SCRIPT_DIR/../Nodes"
CERTS_DIR="$SCRIPT_DIR/certs"
JAR_PATH="$SCRIPT_DIR/../Public-Ledger/build/libs/Public-Ledger-1.0-SNAPSHOT-all.jar"

mkdir -p "$BASE_DIR"
mkdir -p "$CERTS_DIR"


CA_KEY="$CERTS_DIR/ca.key"
CA_CERT="$CERTS_DIR/ca.crt"

if [ ! -f "$CA_KEY" ]; then
  echo "Generating CA key and certificate (once)..."
  openssl genrsa -out "$CA_KEY" 2048
  openssl req -x509 -new -nodes -key "$CA_KEY" -sha256 -days 3650 -out "$CA_CERT" -subj "/CN=MyAuctionCA"
else
  echo "CA already exists. Skipping CA generation."
fi

generate_node_cert() {
  NODE_NAME=$1
  NODE_KEY="$BASE_DIR/$NODE_NAME/server.key"
  NODE_CSR="$BASE_DIR/$NODE_NAME/server.csr"
  NODE_CERT="$BASE_DIR/$NODE_NAME/server.crt"
  OPENSSL_CONFIG="$BASE_DIR/$NODE_NAME/openssl.cnf"

  mkdir -p "$BASE_DIR/$NODE_NAME"

  if [ ! -f "$NODE_KEY" ]; then
    echo "Generating certificate for $NODE_NAME..."

    cat > "$OPENSSL_CONFIG" <<EOF
[req]
default_bits = 2048
prompt = no
default_md = sha256
req_extensions = req_ext
distinguished_name = dn

[dn]
CN = $NODE_NAME

[req_ext]
subjectAltName = @alt_names

[alt_names]
IP.1 = 127.0.0.1
EOF

    openssl genrsa -out "$NODE_KEY" 2048
    openssl req -new -key "$NODE_KEY" -out "$NODE_CSR" -config "$OPENSSL_CONFIG"
    openssl x509 -req -in "$NODE_CSR" -CA "$CA_CERT" -CAkey "$CA_KEY" -CAcreateserial -out "$NODE_CERT" -days 365 -sha256 -extensions req_ext -extfile "$OPENSSL_CONFIG"

    rm "$OPENSSL_CONFIG"
  else
    echo "Certificate for $NODE_NAME already exists. Skipping."
  fi
}

start_node() {
  PORT=$1
  IS_BOOTSTRAP=$2
  NODE_NAME="node_$PORT"
  generate_node_cert "$NODE_NAME"

  NODE_DIR="$BASE_DIR/$NODE_NAME"

  if [ "$IS_BOOTSTRAP" = true ]; then
    gnome-terminal -- bash -c "cd \"$NODE_DIR\" && java -jar \"$JAR_PATH\" --port=$PORT --bootstrap true; exec bash"
  else
    gnome-terminal -- bash -c "cd \"$NODE_DIR\" && java -jar \"$JAR_PATH\" --port=$PORT; exec bash"
  fi
}

# ===== Arrancar os nodes =====
start_node 5000 true
start_node 5001 true
start_node 5002 false
start_node 5003 false
start_node 5004 false
