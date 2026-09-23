/**
 * Thin REST client for the frozen server contract — main process only
 * (D-91: renderer never holds a token or opens a socket to the server).
 */
import { log } from './logger.js';
import { requireAccessToken } from './auth.js';
import type { ServerConfig, SessionListQuery, SessionCreateBody, MessageListQuery } from '../shared/ipc-contract.js';
import type {
  AgentCatalog,
  MessagePage,
  SendMessageAccepted,
  SessionDto,
  SessionPage,
} from '../shared/api-types.js';

async function authHeaders(cfg: ServerConfig): Promise<Record<string, string>> {
  const token = await requireAccessToken(cfg, cfg.tokenClockSkewSeconds);
  return {
    Authorization: `Bearer ${token}`,
    Accept: 'application/json',
  };
}

function apiUrl(cfg: ServerConfig, path: string, query?: URLSearchParams): string {
  const base = cfg.serverBaseUrl.replace(/\/+$/, '');
  const qs = query && [...query.keys()].length > 0 ? `?${query.toString()}` : '';
  return `${base}/api/v1${path}${qs}`;
}

async function requestJson<T>(
  cfg: ServerConfig,
  path: string,
  init: { method?: string; query?: URLSearchParams; body?: unknown } = {},
): Promise<T> {
  const headers = await authHeaders(cfg);
  if (init.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const res = await fetch(apiUrl(cfg, path, init.query), {
    method: init.method ?? 'GET',
    headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    log.warn('api request failed', { path, status: res.status, body: text.slice(0, 500) });
    throw new Error(`API ${init.method ?? 'GET'} ${path} → ${res.status}${text ? `: ${text.slice(0, 200)}` : ''}`);
  }
  return (await res.json()) as T;
}

export async function listSessions(cfg: ServerConfig, query: SessionListQuery = {}): Promise<SessionPage> {
  const q = new URLSearchParams();
  if (query.mine !== undefined) q.set('mine', String(query.mine));
  if (query.q) q.set('q', query.q);
  if (query.cursor) q.set('cursor', query.cursor);
  if (query.limit !== undefined) q.set('limit', String(query.limit));
  return requestJson<SessionPage>(cfg, '/sessions', { query: q });
}

export async function getSession(cfg: ServerConfig, id: string): Promise<SessionDto> {
  return requestJson<SessionDto>(cfg, `/sessions/${encodeURIComponent(id)}`);
}

export async function createSession(cfg: ServerConfig, body: SessionCreateBody): Promise<SessionDto> {
  return requestJson<SessionDto>(cfg, '/sessions', { method: 'POST', body });
}

export async function listAgents(cfg: ServerConfig): Promise<AgentCatalog> {
  return requestJson<AgentCatalog>(cfg, '/agents');
}

export async function listMessages(
  cfg: ServerConfig,
  sessionId: string,
  query: MessageListQuery = {},
): Promise<MessagePage> {
  const q = new URLSearchParams();
  if (query.since !== undefined) q.set('since', String(query.since));
  if (query.limit !== undefined) q.set('limit', String(query.limit));
  return requestJson<MessagePage>(cfg, `/sessions/${encodeURIComponent(sessionId)}/messages`, { query: q });
}

export async function sendMessage(cfg: ServerConfig, sessionId: string, text: string): Promise<SendMessageAccepted> {
  return requestJson<SendMessageAccepted>(cfg, `/sessions/${encodeURIComponent(sessionId)}/messages`, {
    method: 'POST',
    body: { text },
  });
}

export async function compactSession(cfg: ServerConfig, sessionId: string): Promise<void> {
  await requestJson<unknown>(cfg, `/sessions/${encodeURIComponent(sessionId)}/compact`, { method: 'POST' });
}

export async function stopSession(cfg: ServerConfig, sessionId: string): Promise<void> {
  await requestJson<unknown>(cfg, `/sessions/${encodeURIComponent(sessionId)}/stop`, { method: 'POST' });
}
