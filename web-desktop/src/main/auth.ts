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
    throw new Error(`token endpoint ${res.status}: ${text}`);
  }
  return parseTokenResponse(await res.json());
}

async function refreshTokens(cfg: ServerConfig, refreshToken: string): Promise<TokenSet> {
  const body = new URLSearchParams({
    grant_type: 'refresh_token',
    client_id: cfg.keycloakClientId,
    refresh_token: refreshToken,
  });
  const res = await fetch(tokenEndpoint(cfg), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });
  if (!res.ok) {
    throw new Error(`refresh endpoint ${res.status}`);
  }
  const refreshed = parseTokenResponse(await res.json());
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

/**
 * Silent refresh — no window, no user interaction. Called on 401/expiry.
 * Returns the fresh access token, or `null` when the session must end
 * (refresh failed / no refresh token / safeStorage unavailable).
 */
export async function refreshIfNeeded(
  cfg: ServerConfig,
  skewSeconds: number,
): Promise<string | null> {
  try {
    const tokens = loadTokens();
    if (!tokens) {
      return null;
    }
    const now = Math.floor(Date.now() / 1000);
    if (now + skewSeconds < tokens.expiresAt) {
      return tokens.accessToken;
    }
    if (!tokens.refreshToken) {
      log.warn('access token expired and no refresh token stored');
      clearTokens();
      return null;
    }
    const refreshed = await refreshTokens(cfg, tokens.refreshToken);
    saveTokens(refreshed);
    log.info('silent refresh succeeded');
    return refreshed.accessToken;
  } catch (err) {
    if (err instanceof Error && err.name === 'SafeStorageUnavailableError') {
      log.error('safeStorage unavailable; refusing to continue with tokens', err);
      return null;
    }
    log.error('silent refresh failed; clearing session', err);
    clearTokens();
    return null;
  }
}

/**
 * Returns a usable access token or throws a human-readable error.
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
  clearTokens();
  const url = new URL(logoutUrl(cfg));
  url.searchParams.set('client_id', cfg.keycloakClientId);
  await shell.openExternal(url.toString());
  log.info('logout completed');
}

/**
 * Synchronous state for the renderer (never the token itself).
 */
export function readLoginState(): { loggedIn: boolean } {
  try {
    return { loggedIn: loadTokens() !== null };
  } catch {
    // safeStorage unavailable → not logged in, and login will explain why
    return { loggedIn: false };
  }
}
