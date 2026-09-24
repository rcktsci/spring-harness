#!/usr/bin/env bash
# POSIX sibling of smoke-docker.ps1.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
web_dir="$repo_root/web-desktop"

: "${HARNESS_E2E_SERVER_BASE_URL:=http://localhost:8080}"
: "${HARNESS_E2E_KEYCLOAK_ISSUER:=http://localhost:8080/realms/harness}"
: "${HARNESS_E2E_KEYCLOAK_CLIENT_ID:=spring-harness-web-desktop}"
: "${HARNESS_E2E_KEYCLOAK_USER:=tester}"
: "${HARNESS_E2E_KEYCLOAK_PASSWORD:=tester}"

echo "[smoke] server=$HARNESS_E2E_SERVER_BASE_URL issuer=$HARNESS_E2E_KEYCLOAK_ISSUER"

if [ -z "${HARNESS_E2E_NO_BUILD:-}" ]; then
  echo "[smoke] building electron-vite bundle..."
  ( cd "$web_dir" && pnpm run build )
fi

export HARNESS_E2E_ENABLED=1
export HARNESS_E2E_SERVER_BASE_URL
export HARNESS_E2E_KEYCLOAK_ISSUER
export HARNESS_E2E_KEYCLOAK_CLIENT_ID
export HARNESS_E2E_KEYCLOAK_USER
export HARNESS_E2E_KEYCLOAK_PASSWORD
export HARNESS_E2E_AUTO_CONFIRM_COMMANDS=1
export ELECTRON_DISABLE_SECURITY_WARNINGS=1

cd "$web_dir"
pnpm exec playwright test --config=playwright.config.ts --project=electron-smoke
echo "[smoke] OK"
