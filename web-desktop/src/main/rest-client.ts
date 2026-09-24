/**
 * Thin REST client for the frozen server contract — main process only
 * (D-91: renderer never holds a token or opens a socket to the server).
 */
import { log } from './logger.js';
import { requireAccessToken } from './auth.js';
import type {
  ArtifactDownloadBody,
  MessageListQuery,
  ServerConfig,
  SessionCreateBody,
  SessionListQuery,
  SessionSendBody,
  TaskCommentsListQuery,
  TaskCommentAddBody,
  TaskHistoryQuery,
  TaskListQuery,
} from '../shared/ipc-contract.js';
import type {
  AgentCatalog,
  CommentDto,
  CommentPage,
  MessagePage,
  SendMessageAccepted,
  SessionDto,
  SessionPage,
  SessionTreePage,
  TaskDto,
  TaskPage,
  TransitionPage,
  WorkspaceDownloadResult,
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

export async function getSessionTree(cfg: ServerConfig, id: string, depth?: number): Promise<SessionTreePage> {
  const q = new URLSearchParams();
  if (depth !== undefined) q.set('depth', String(depth));
  return requestJson<SessionTreePage>(cfg, `/sessions/${encodeURIComponent(id)}/tree`, { query: q });
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

export async function listTasks(cfg: ServerConfig, query: TaskListQuery = {}): Promise<TaskPage> {
  const q = new URLSearchParams();
  if (query.mine !== undefined) q.set('mine', String(query.mine));
  if (query.q) q.set('q', query.q);
  if (query.status) q.set('status', query.status);
  if (query.tags) q.set('tags', query.tags.join(','));
  if (query.cursor) q.set('cursor', query.cursor);
  if (query.limit !== undefined) q.set('limit', String(query.limit));
  return requestJson<TaskPage>(cfg, '/tasks', { query: q });
}

export async function getTask(cfg: ServerConfig, id: string): Promise<TaskDto> {
  return requestJson<TaskDto>(cfg, `/tasks/${encodeURIComponent(id)}`);
}

export async function listTaskHistory(
  cfg: ServerConfig,
  id: string,
  query: TaskHistoryQuery = {},
): Promise<TransitionPage> {
  const q = new URLSearchParams();
  if (query.since) q.set('since', query.since);
  if (query.limit !== undefined) q.set('limit', String(query.limit));
  return requestJson<TransitionPage>(cfg, `/tasks/${encodeURIComponent(id)}/history`, { query: q });
}

export async function listTaskComments(
  cfg: ServerConfig,
  id: string,
  query: TaskCommentsListQuery = {},
): Promise<CommentPage> {
  const q = new URLSearchParams();
  if (query.cursor) q.set('cursor', query.cursor);
  if (query.limit !== undefined) q.set('limit', String(query.limit));
  return requestJson<CommentPage>(cfg, `/tasks/${encodeURIComponent(id)}/comments`, { query: q });
}

export async function addTaskComment(cfg: ServerConfig, id: string, body: TaskCommentAddBody): Promise<CommentDto> {
  return requestJson<CommentDto>(cfg, `/tasks/${encodeURIComponent(id)}/comments`, { method: 'POST', body });
}

/**
 * Streams a workspace file from `/api/v1/sessions/{id}/workspace/files`.
 * Returns the Response so the caller can pipe its body to disk; surfaces
 * server failures (404/413/422) as a structured Error.
 */
export async function fetchWorkspaceFile(cfg: ServerConfig, body: ArtifactDownloadBody): Promise<Response> {
  const token = await requireAccessToken(cfg, cfg.tokenClockSkewSeconds);
  const q = new URLSearchParams({ path: body.path });
  const url = `${cfg.serverBaseUrl.replace(/\/+$/, '')}/api/v1/sessions/${encodeURIComponent(body.sessionId)}/workspace/files?${q.toString()}`;
  const res = await fetch(url, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/octet-stream',
    },
  });
  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    log.warn('workspace file fetch failed', {
      sessionId: body.sessionId,
      path: body.path,
      status: res.status,
      body: detail.slice(0, 500),
    });
    throw new WorkspaceFileError(res.status, detail);
  }
  return res;
}

/** Parsed failure surfaced by `fetchWorkspaceFile` so handlers can map → UI string. */
export class WorkspaceFileError extends Error {
  readonly status: number;
  readonly detail: string;
  constructor(status: number, detail: string) {
    super(`workspace file → ${status}${detail ? `: ${detail.slice(0, 200)}` : ''}`);
    this.name = 'WorkspaceFileError';
    this.status = status;
    this.detail = detail;
  }
}

export type { WorkspaceDownloadResult };
export type { SessionSendBody };
