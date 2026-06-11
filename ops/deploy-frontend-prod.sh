#!/usr/bin/env bash
set -euo pipefail

FRONTEND_DIR="/opt/meeting/frontend"
NGINX_CONF_SRC="/opt/meeting/ops/nginx/meeting.example.com.conf"
NGINX_CONF_DST="/etc/nginx/conf.d/meeting.example.com.conf"

SUDO=""
if [[ "$(id -u)" != "0" ]]; then
  SUDO="sudo"
fi

echo "[1/3] Build frontend..."
(cd "$FRONTEND_DIR" && npm run build)

echo "[2/3] Update nginx config..."
TS="$(date +%F-%H%M%S)"
if [[ -f "$NGINX_CONF_DST" ]]; then
  $SUDO cp -a "$NGINX_CONF_DST" "${NGINX_CONF_DST}.bak.${TS}"
fi
if [[ ! -f "$NGINX_CONF_SRC" ]]; then
  echo "ERROR: nginx config source not found: $NGINX_CONF_SRC" >&2
  exit 1
fi
$SUDO cp -a "$NGINX_CONF_SRC" "$NGINX_CONF_DST"

echo "[3/3] nginx -t && reload..."
$SUDO nginx -t
$SUDO systemctl reload nginx

echo "Done."
echo "Backup (if any): ${NGINX_CONF_DST}.bak.${TS}"

