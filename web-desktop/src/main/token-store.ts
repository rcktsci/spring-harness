/**
 * Token storage — OS keychain only (D-91: main owns secrets).
 *
 * plain-text storage of tokens is explicitly forbidden (spec:
 * "safeStorage недоступен — отказ с понятным сообщением; plain-text
 * хранение токенов запрещён"). If `safeStorage.isEncryptionAvailable()`
 * is false, every read/write throws `SafeStorageUnavailableError` and
 * the login flow surfaces a human-readable error.
 */

import { app, safeStorage } from 'electron';
import { readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { log } from './logger.js';

const STORE_FILE = 'tokens.enc';

export class SafeStorageUnavailableError extends Error {
  constructor() {
    super(
      'OS keychain is unavailable on this machine. Web Desktop cannot store ' +
        'SSO credentials without it — see the logs folder for details.',
    );
    this.name = 'SafeStorageUnavailableError';
  }
}

export interface TokenSet {
  accessToken: string;
  refreshToken?: string;
  /** Epoch seconds. */
  expiresAt: number;
}

function assertSafeStorage(): void {
  if (!safeStorage.isEncryptionAvailable()) {
    throw new SafeStorageUnavailableError();
  }
}

function storePath(): string {
  return join(app.getPath('userData'), STORE_FILE);
}

export function loadTokens(): TokenSet | null {
  // e2e bypass (Playwright stub-server): HARNESS_E2E_TOKEN injects a pre-issued
  // bearer — saves the stub Keycloak issuer from having to render HTML.
  if (process.env['HARNESS_E2E_ENABLED'] === '1' && process.env['HARNESS_E2E_TOKEN']) {
    return {
      accessToken: process.env['HARNESS_E2E_TOKEN'],
      expiresAt: Math.floor(Date.now() / 1000) + 3600,
    };
  }
  assertSafeStorage();
  let raw: Buffer;
  try {
    raw = readFileSync(storePath());
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') {
      return null;
    }
    throw err;
  }
  const json = safeStorage.decryptString(raw);
  const parsed = JSON.parse(json) as TokenSet;
  return parsed;
}

export function saveTokens(tokens: TokenSet): void {
  assertSafeStorage();
  const encrypted = safeStorage.encryptString(JSON.stringify(tokens));
  writeFileSync(storePath(), encrypted);
  log.info('tokens persisted to keychain');
}

export function clearTokens(): void {
  try {
    unlinkSync(storePath());
    log.info('tokens cleared');
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code !== 'ENOENT') {
      throw err;
    }
  }
}

/**
 * True when the access token is still valid with the given clock skew.
 * `skewSeconds` comes from DEFAULT_CONFIG, never hardcoded at the call site.
 */
export function isTokenFresh(tokens: TokenSet, skewSeconds: number, now = Date.now()): boolean {
  return Math.floor(now / 1000) + skewSeconds < tokens.expiresAt;
}
