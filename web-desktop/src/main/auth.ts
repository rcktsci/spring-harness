/**
 * Keycloak OAuth2 Authorization Code + PKCE — main process only (D-91).
 *
 * Flow (spec "SSO-логин и хранение токена"):
 *  1. `startLogin` opens a *visible* BrowserWindow at the authorization
 *     endpoint with PKCE challenge; the user sees the real Keycloak form.
 *  2. Keycloak redirects to a loopback URL; a local HTTP server on an
 *     ephemeral port captures `code` / `error`.
 *  3. `exchangeCode` trades the code (+ verifier) for tokens.
 *  4. Tokens go to `token-store` (safeStorage; never plain-text).
 *  5. `refreshIfNeeded` does a silent refresh (no window) on 401/expiry;
 *     a failed refresh clears the session and the renderer sees logged-out.
 *  6. `logout` clears storage and opens the Keycloak logout URL.
 */

import { BrowserWindow, shell } from 'electron';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { log } from './logger.js';
import {
  clearTokens,
  isTokenFresh,
  loadTokens,
  saveTokens,
  type TokenSet,
} from './token-store.js';
import { generatePkcePair, generateState, type PkcePair } from './pkce.js';
import type { ServerConfig } from '../shared/ipc-contract.js';

type LoginResult =
  | { ok: true; tokens: TokenSet }
  | { ok: false; reason: 'cancelled' | 'denied' | 'unavailable' | 'exchange-failed'; message?: string };

interface PendingLogin {
  pkce: PkcePair;
  state: string;
  window: BrowserWindow;
  server: Server;
  redirectPort: number;
}

let pending: PendingLogin | null = null;

function authUrl(cfg: ServerConfig, pkce: PkcePair, state: string, port: number): string {
  const issuer = cfg.keycloakIssuer.replace(/\/+$/, '');
  const redirectUri = `http://127.0.0.1:${port}/callback`;
  const params = new URLSearchParams({
    client_id: cfg.keycloakClientId,
    response_type: 'code',
    scope: cfg.keycloakScopes,
    redirect_uri: redirectUri,
    state,
    code_challenge: pkce.codeChallenge,
    code_challenge_method: 'S256',
  });
  return `${issuer}/protocol/openid-connect/auth?${params.toString()}`;
}

function tokenEndpoint(cfg: ServerConfig): string {
  const issuer = cfg.keycloakIssuer.replace(/\/+$/, '');
  return `${issuer}/protocol/openid-connect/token`;
}

function logoutUrl(cfg: ServerConfig): string {
  const issuer = cfg.keycloakIssuer.replace(/\/+$/, '');
  return `${issuer}/protocol/openid-connect/logout`;
}

function parseTokenResponse(body: unknown): TokenSet {
  const o = body as Record<string, unknown>;
  if (typeof o['access_token'] !== 'string') {
    throw new Error('token response missing access_token');
  }
  const expiresIn = typeof o['expires_in'] === 'number' ? o['expires_in'] : 0;
  return {
    accessToken: o['access_token'] as string,
    refreshToken: typeof o['refresh_token'] === 'string' ? o['refresh_token'] : undefined,
    expiresAt: Math.floor(Date.now() / 1000) + expiresIn,
  };
}

async function exchangeCode(cfg: ServerConfig, code: string, pkce: PkcePair, port: number): Promise<TokenSet> {
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: cfg.keycloakClientId,
    code,
    redirect_uri: `http://127.0.0.1:${port}/callback`,
    code_verifier: pkce.codeVerifier,
  });
  const res = await fetch(tokenEndpoint(cfg), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`token endpoint ${res.status}: ${sanitizeBody(text)}`);
  }
  return parseTokenResponse(await res.json());
}

export class RefreshGrantError extends Error {}

export class RefreshTransientError extends Error {}

const BODY_LOG_LIMIT_CHARS = 512;
const MASKED_VALUE = '[masked]';
const SECRET_KEY_PARTS = ['token', 'secret', 'password', 'credential'] as const;
const JWT_LIKE_PATTERN = /[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g;

function maskSecrets(value: unknown, key?: string): unknown {
  if (key !== undefined && SECRET_KEY_PARTS.some((part) => key.toLowerCase().includes(part))) {
    return MASKED_VALUE;
  }
  if (Array.isArray(value)) {
    return value.map((item) => maskSecrets(item));
  }
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([entryKey, entryValue]) => [entryKey, maskSecrets(entryValue, entryKey)]),
    );
  }
  return value;
}

function maskJwtLike(text: string): string {
  return text.replace(JWT_LIKE_PATTERN, '[jwt-like]');
}

function sanitizeBody(text: string): string {
  try {
    return JSON.stringify(maskSecrets(JSON.parse(text))).slice(0, BODY_LOG_LIMIT_CHARS);
  } catch {
    return maskJwtLike(text).slice(0, BODY_LOG_LIMIT_CHARS);
  }
}

async function refreshTokens(cfg: ServerConfig, refreshToken: string): Promise<TokenSet> {
  const body = new URLSearchParams({
    grant_type: 'refresh_token',
    client_id: cfg.keycloakClientId,
    refresh_token: refreshToken,
  });
  let res: Response;
  try {
    res = await fetch(tokenEndpoint(cfg), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    });
  } catch (err) {
    throw new RefreshTransientError(`refresh endpoint unreachable: ${String(err)}`);
  }
  const text = await res.text();
  if (!res.ok) {
    if (res.status >= 400 && res.status < 500 && res.status !== 408 && res.status !== 429) {
      throw new RefreshGrantError(`refresh endpoint ${res.status}: ${sanitizeBody(text)}`);
    }
    throw new RefreshTransientError(`refresh endpoint ${res.status}: ${sanitizeBody(text)}`);
  }
  let parsedBody: unknown;
  try {
    parsedBody = JSON.parse(text) as unknown;
  } catch {
    throw new RefreshTransientError(`refresh endpoint ${res.status}: non-JSON body: ${sanitizeBody(text)}`);
  }
  let refreshed: TokenSet;
  try {
    refreshed = parseTokenResponse(parsedBody);
  } catch (err) {
    const detail = err instanceof Error ? err.message : String(err);
    throw new RefreshTransientError(`refresh endpoint ${res.status}: ${detail} — body: ${sanitizeBody(text)}`);
  }
  // Keycloak may omit a new refresh token — keep the previous one.
  if (!refreshed.refreshToken) {
    refreshed.refreshToken = refreshToken;
  }
  return refreshed;
}

/**
 * Runs the interactive login: visible window + loopback listener.
 * Resolves only after tokens are stored, or with a typed failure.
 */
export async function startLogin(cfg: ServerConfig): Promise<LoginResult> {
  if (pending) {
    log.warn('login already in progress');
    return { ok: false, reason: 'denied', message: 'A login window is already open.' };
  }

  let pkce: PkcePair;
  try {
    pkce = await generatePkcePair();
  } catch (err) {
    log.error('pkce generation failed', err);
    return { ok: false, reason: 'unavailable' };
  }
  const state = generateState();

  const server = createServer();
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as AddressInfo;
  const redirectPort = address.port;

  const win = new BrowserWindow({
    width: cfg.loginWindowWidth,
    height: cfg.loginWindowHeight,
    show: true,
    modal: true,
    title: 'Sign in — Keycloak',
    webPreferences: {
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  pending = { pkce, state, window: win, server, redirectPort };
  log.info(`login window opened; loopback listener on 127.0.0.1:${redirectPort}`);

  const result = await new Promise<LoginResult>((resolve) => {
    win.on('closed', () => {
      // Reset immediately so a fast retry is not falsely rejected as
      // "already in progress" — the promise below is already settled.
      cleanupPending();
      resolve({ ok: false, reason: 'cancelled' });
    });

    server.on('request', (req, res) => {
      const url = new URL(req.url ?? '/', `http://127.0.0.1:${redirectPort}`);
      if (url.pathname !== '/callback') {
        res.writeHead(404).end();
        return;
      }
      const code = url.searchParams.get('code');
      const errParam = url.searchParams.get('error');
      const returnedState = url.searchParams.get('state');

      const done = (status: number, body: string): void => {
        res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8' }).end(body);
      };

      if (errParam) {
        done(400, `<h1>Authorization denied</h1><p>${errParam}</p>`);
        resolve({ ok: false, reason: 'denied', message: errParam });
        return;
      }
      if (returnedState !== state || !code) {
        done(400, '<h1>Invalid callback</h1>');
        resolve({ ok: false, reason: 'denied', message: 'state mismatch or missing code' });
        return;
      }

      exchangeCode(cfg, code, pkce, redirectPort)
        .then((tokens) => {
          saveTokens(tokens);
          done(200, '<h1>Signed in</h1><p>You can close this window.</p>');
          resolve({ ok: true, tokens });
        })
        .catch((err: unknown) => {
          log.error('code exchange failed', err);
          done(502, '<h1>Sign-in failed</h1>');
          resolve({
            ok: false,
            reason: 'exchange-failed',
            message: err instanceof Error ? err.message : String(err),
          });
        });
    });

    void win.loadURL(authUrl(cfg, pkce, state, redirectPort));
  });

  cleanupPending();
  return result;
}

function cleanupPending(): void {
  if (!pending) return;
  const { server, window } = pending;
  pending = null;
  server.close();
  if (!window.isDestroyed()) {
    window.close();
  }
}

let refreshInFlight: Promise<string | null> | null = null;
let sessionEpoch = 0;

/**
 * Invalidates every in-flight silent refresh: called on any explicit session
 * change (logout, server/issuer switch) so a refresh finishing afterwards
 * cannot resurrect the cleared session.
 */
export function invalidateSession(): void {
  sessionEpoch += 1;
}

function terminateSession(): void {
  sessionEpoch += 1;
  clearTokens();
}

/**
 * Silent refresh — no window, no user interaction. Called on 401/expiry by
 * many consumers in parallel (session list, agent catalog, SSE, relay
 * auto-register, artifacts): single-flight — one network refresh per batch,
 * same result for every waiter, in-flight reset on settle (success or
 * failure) so the next batch can refresh again.
 *
 * Outcomes (D: silent-refresh-single-flight):
 *  - grant rejected by Keycloak (4xx on the token endpoint, except
 *    retry-later 408/429) → tokens cleared once, session ends;
 *  - transient failure (network, 5xx, 408/429, malformed 2xx) → tokens
 *    kept, returns null; the next request starts a fresh refresh.
 * A result arriving after invalidateSession() (logout, server switch) is
 * discarded — it must not resurrect the cleared session.
 */
export async function refreshIfNeeded(
  cfg: ServerConfig,
  skewSeconds: number,
): Promise<string | null> {
  let tokens: TokenSet | null;
  try {
    tokens = loadTokens();
  } catch (err) {
    if (err instanceof Error && err.name === 'SafeStorageUnavailableError') {
      log.error('safeStorage unavailable; refusing to continue with tokens', err);
      return null;
    }
    log.error('token store unreadable; clearing session', err);
    terminateSession();
    return null;
  }
  if (!tokens) {
    return null;
  }
  const now = Math.floor(Date.now() / 1000);
  if (now + skewSeconds < tokens.expiresAt) {
    return tokens.accessToken;
  }
  if (!tokens.refreshToken) {
    log.warn('access token expired and no refresh token stored');
    terminateSession();
    return null;
  }
  if (!refreshInFlight) {
    refreshInFlight = doRefresh(cfg, tokens.refreshToken).finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

async function doRefresh(cfg: ServerConfig, refreshToken: string): Promise<string | null> {
  const epoch = sessionEpoch;
  try {
    const refreshed = await refreshTokens(cfg, refreshToken);
    if (epoch !== sessionEpoch) {
      log.info('silent refresh result discarded — session changed mid-flight');
      return null;
    }
    saveTokens(refreshed);
    log.info('silent refresh succeeded');
    return refreshed.accessToken;
  } catch (err) {
    if (epoch !== sessionEpoch) {
      return null;
    }
    if (err instanceof RefreshGrantError) {
      log.error('silent refresh failed; clearing session', err);
      terminateSession();
    } else {
      log.warn('silent refresh postponed — transient failure, tokens kept', err);
    }
    return null;
  }
}

/**
 * Сессия пригодна, если токен ещё свежий ИЛИ его можно тихо обновить.
 * Проверка только наличия токена (прежнее поведение) оставляла приложение
 * «залогиненным» с мёртвым токеном: гейт пускал в чат, каждый вызов падал
 * «Not signed in», а на /login не пускал — пользователь оказывался заперт
 * (реальный случай 2026-09-24: SSO-логин без refresh_token).
 */
export function isSessionUsable(tokens: TokenSet, skewSeconds: number): boolean {
  if (isTokenFresh(tokens, skewSeconds)) {
    return true;
  }
  return typeof tokens.refreshToken === 'string' && tokens.refreshToken.length > 0;
}

/**
 * Returns the access token or throws when the session cannot be recovered.
 * Callers surface the message to the renderer.
 */
export async function requireAccessToken(cfg: ServerConfig, skewSeconds: number): Promise<string> {
  const token = await refreshIfNeeded(cfg, skewSeconds);
  if (!token) {
    throw new Error('Not signed in. Open Settings → Sign in and retry.');
  }
  return token;
}

/**
 * Logout: wipe local tokens, then send the browser to the Keycloak
 * end-session URL so the SSO cookie is invalidated too.
 */
export async function logout(cfg: ServerConfig): Promise<void> {
  // A login still in flight must not outlive the session it was for.
  cleanupPending();
  invalidateSession();
  clearTokens();
  const url = new URL(logoutUrl(cfg));
  url.searchParams.set('client_id', cfg.keycloakClientId);
  await shell.openExternal(url.toString());
  log.info('logout completed');
}

/**
 * Синхронное состояние для renderer (никогда сам токен).
 * `loggedIn` = сессия пригодна (свежая или обновляемая), а не «файл токенов
 * существует» — иначе просроченный токен запирает пользователя в UI (см.
 * {@link isSessionUsable}).
 */
export function readLoginState(skewSeconds: number): { loggedIn: boolean } {
  try {
    const tokens = loadTokens();
    return { loggedIn: tokens !== null && isSessionUsable(tokens, skewSeconds) };
  } catch {
    // safeStorage unavailable → not logged in, and login will explain why
    return { loggedIn: false };
  }
}
