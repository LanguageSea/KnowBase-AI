#!/bin/sh
set -eu

TLS_DIR="${TLS_DIR:-/etc/nginx/tls}"
mkdir -p "$TLS_DIR"

if [ ! -s "$TLS_DIR/tls.crt" ] || [ ! -s "$TLS_DIR/tls.key" ]; then
    echo "No TLS certificate found in $TLS_DIR; generating a self-signed demo certificate."
    openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
        -keyout "$TLS_DIR/tls.key" \
        -out "$TLS_DIR/tls.crt" \
        -subj "/CN=knowbase.local" >/dev/null 2>&1
    chmod 600 "$TLS_DIR/tls.key"
fi

nginx -t
exec /docker-entrypoint.sh nginx -g "daemon off;"
