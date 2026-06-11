#!/usr/bin/env bash
set -euo pipefail

ROOT="/opt/meeting"
BACKEND_DIR="${ROOT}/backend"
SERVICE_SRC="${ROOT}/ops/systemd/meeting-backend.service"
SERVICE_DST="/etc/systemd/system/meeting-backend.service"

JAVA_HOME_DEFAULT="/usr/lib/jvm/java-21-openjdk-21.0.10.0.7-1.el9.x86_64"
JAVA_HOME="${JAVA_HOME:-${JAVA_HOME_DEFAULT}}"

SUDO=""
if [[ "$(id -u)" != "0" ]]; then
  SUDO="sudo"
fi

echo "[1/3] Build backend jar..."
(cd "$BACKEND_DIR" && \
  JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" \
  mvn -Dmaven.repo.local=/home/.m2/repository -DskipTests package)

echo "[2/3] Install systemd unit..."
TS="$(date +%F-%H%M%S)"
if [[ -f "$SERVICE_DST" ]]; then
  $SUDO cp -a "$SERVICE_DST" "${SERVICE_DST}.bak.${TS}"
fi
$SUDO cp -a "$SERVICE_SRC" "$SERVICE_DST"
$SUDO systemctl daemon-reload

echo "[3/3] Enable + restart backend service..."
$SUDO systemctl enable --now meeting-backend
$SUDO systemctl restart meeting-backend
$SUDO systemctl status meeting-backend --no-pager

echo "Done."

