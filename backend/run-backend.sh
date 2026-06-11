#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ROOT_DIR}/../.env"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d "/usr/lib/jvm/java-21-openjdk-21.0.10.0.7-1.el9.x86_64" ]]; then
    export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-21.0.10.0.7-1.el9.x86_64"
  fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

MAVEN_USER_HOME="${MAVEN_USER_HOME:-/home/.m2}" \
  mvn -DskipTests spring-boot:run -Dspring-boot.run.profiles=local
