#!/usr/bin/env pwsh
# Automated Playwright-electron smoke against docker-compose (live Keycloak).
#
# Prereqs:
#   - Docker + docker-compose on PATH
#   - The server repo's `dev/Keycloak/docker-compose.yml` (or analogous) up
#     with a test realm/user seeded (e.g. realm "harness", user "tester").
#   - `pnpm build` (electron-vite) ran once.
#
# What it does:
#   1. Reads server URL + Keycloak issuer from env or defaults.
#   2. Runs `electron-smoke` project (full happy-path scenario).
#
# Usage:
#   pwsh web-desktop/scripts/smoke-docker.ps1
#   $env:HARNESS_E2E_SERVER_BASE_URL="http://localhost:8080"
#   $env:HARNESS_E2E_KEYCLOAK_ISSUER="http://localhost:8080/realms/harness"
#   $env:HARNESS_E2E_KEYCLOAK_CLIENT_ID="spring-harness-web-desktop"
#   pwsh web-desktop/scripts/smoke-docker.ps1

[CmdletBinding()]
param(
  [string]$ServerBaseUrl = $env:HARNESS_E2E_SERVER_BASE_URL ?? 'http://localhost:8080',
  [string]$KeycloakIssuer = $env:HARNESS_E2E_KEYCLOAK_ISSUER ?? 'http://localhost:8080/realms/harness',
  [string]$KeycloakClientId = $env:HARNESS_E2E_KEYCLOAK_CLIENT_ID ?? 'spring-harness-web-desktop',
  [string]$KeycloakUser = $env:HARNESS_E2E_KEYCLOAK_USER ?? 'tester',
  [string]$KeycloakPassword = $env:HARNESS_E2E_KEYCLOAK_PASSWORD ?? 'tester',
  [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..' '..')
$webDir = Join-Path $repoRoot 'web-desktop'

Write-Host "[smoke] server=$ServerBaseUrl issuer=$KeycloakIssuer client=$KeycloakClientId"

if (-not $NoBuild) {
  Write-Host '[smoke] building electron-vite bundle...'
  Push-Location $webDir
  try { pnpm run build } finally { Pop-Location }
}

Write-Host '[smoke] running Playwright-electron (full scenario)...'
Push-Location $webDir
try {
  $env:HARNESS_E2E_ENABLED = '1'
  $env:HARNESS_E2E_SERVER_BASE_URL = $ServerBaseUrl
  $env:HARNESS_E2E_KEYCLOAK_ISSUER = $KeycloakIssuer
  $env:HARNESS_E2E_KEYCLOAK_CLIENT_ID = $KeycloakClientId
  $env:HARNESS_E2E_KEYCLOAK_USER = $KeycloakUser
  $env:HARNESS_E2E_KEYCLOAK_PASSWORD = $KeycloakPassword
  $env:HARNESS_E2E_AUTO_CONFIRM_COMMANDS = '1'
  $env:ELECTRON_DISABLE_SECURITY_WARNINGS = '1'
  pnpm exec playwright test --config=playwright.config.ts --project=electron-smoke
  $code = $LASTEXITCODE
  if ($code -ne 0) {
    Write-Error "[smoke] failed with exit code $code"
    exit $code
  }
} finally {
  Pop-Location
}

Write-Host '[smoke] OK'
