/**
 * Stub server for Web Desktop e2e — full §5 + §3.1 + §3.2 surface.
 *
 * Boots on an ephemeral HTTP port (and a sibling WS port — ws@8 requires
 * `server.on('upgrade')` on the same listener). Exposes:
 *  - REST §2: /api/v1/{agents,sessions,sessions/{id}/messages,
 *    sessions/{id}/compact,sessions/{id}/stop,sessions/{id}/tree,
 *    sessions/{id}/events,sessions/{id}/workspace/files,
 *    tasks/{id},tasks/{id}/events,tasks/{id}/history,
 *    tasks/{id}/comments}
 *  - SSE §3.1 / §3.2: text/event-stream
 *  - WS /api/v1/relay §5: hello/welcome, register/registered, tool.call,
 *    tool.result (echo), 4401/4403/4409 close codes, ping/pong, takeover
 *
 * Auth: every Authorization bearer must equal `STUB_TOKEN` (HARNESS_E2E_TOKEN
 * parity). The desktop is configured with the same token via env.
 *
 * Scenarios are scripted from the Playwright spec by writing into the
 * in-process store; the server then drives the desktop via SSE/WS frames.
 */
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { URL } from 'node:url';
import { WebSocketServer, type WebSocket } from 'ws';
import { readFileSync, rmSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const STUB_TOKEN = 'stub-test-token';

let workspaceRoot = '';

function ensureWorkspace(): void {
  workspaceRoot = join(tmpdir(), `harness-e2e-ws-${Date.now()}`);
  mkdirSync(workspaceRoot, { recursive: true });
  writeFileSync(join(workspaceRoot, 'hello.md'), '# hi from stub\n', 'utf8');
  writeFileSync(join(workspaceRoot, 'notes.md'), 'stub notes\n', 'utf8');
}

interface SessionRecord {
  id: string;
  owner: string;
  kind: 'FREE' | 'STATE';
  /** Set only on STATE sub-sessions; null on the FREE root and unused there. */
  parentSessionId: string | null;
  /** STATE-only: state code from `task.stateCode`; null on FREE. */
  stateCode: string | null;
  /** STATE-only: task id from `task.id`; null on FREE. */
  taskId: string | null;
  title: string | null;
  agentKey: string;
  agentRev: number;
  runtimeStatus: 'IDLE' | 'TURN_RUNNING' | 'PARKED_ASYNC' | 'PARKED_CLIENT';
  lastTurnOutcome?: 'COMPLETED' | 'FAILED' | 'CANCELLED';
  lastSeq: number;
  lastActivityAt: string;
  createdAt: string;
  /** SEQ-keyed journal of all messages emitted. */
  messages: Map<number, MessageRecord>;
}

interface MessageRecord {
  id: string;
  seq: number;
  kind: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL_CALL' | 'TOOL_RESULT' | 'COMPACT' | 'ASYNC_ACCEPTED';
  author?: string;
  payload: Record<string, unknown>;
  callId?: string;
  late?: boolean;
  createdAt: string;
}

const sessions = new Map<string, SessionRecord>();

function newSession(
  kind: 'FREE' | 'STATE',
  owner: string,
  agentKey: string,
  agentRev: number,
  parentSessionId: string | null = null,
  stateCode: string | null = null,
  taskId: string | null = null,
): SessionRecord {
  const id = crypto.randomUUID();
  const now = new Date().toISOString();
  const s: SessionRecord = {
    id,
    owner,
    kind,
    parentSessionId,
    stateCode,
    taskId,
    title: null,
    agentKey,
    agentRev,
    runtimeStatus: 'IDLE',
    lastSeq: 0,
    lastActivityAt: now,
    createdAt: now,
    messages: new Map(),
  };
  sessions.set(id, s);
  return s;
}

// Pre-create one FREE root session at boot for the test scenario.
// (Workspace creation happens in startStub(), NOT at module load: Playwright
// loads this file both for test collection and in the worker, and a
// module-level temp dir would leak — nothing closes the ghost copy.)
const rootSession = newSession('FREE', 'tester', 'tester', 1);
rootSession.title = 'e2e-root';
// Plus one STATE sub-session so /tree exercises stateCode/taskId in the
// response — verifies fidelity with api-contracts §2 TreeNode shape.
newSession('STATE', 'tester', 'tester', 1, rootSession.id, 'init', 't-1');

// Single source of truth for the /tree response shape (api-contracts §2).
function nodeFromSession(s: SessionRecord): Record<string, unknown> {
  const node: Record<string, unknown> = {
    id: s.id,
    parentSessionId: s.parentSessionId,
    kind: s.kind,
    agent: { key: s.agentKey, rev: s.agentRev },
    runtimeStatus: s.runtimeStatus,
    lastSeq: s.lastSeq,
    lastActivityAt: s.lastActivityAt,
  };
  // stateCode/taskId are STATE-only and must be present (as null/undefined for FREE).
  if (s.kind === 'STATE') {
    node['stateCode'] = s.stateCode;
    node['taskId'] = s.taskId;
  } else {
    node['stateCode'] = null;
    node['taskId'] = null;
  }
  return node;
}

function appendMessage(sessionId: string, kind: MessageRecord['kind'], payload: Record<string, unknown>, opts: { author?: string; callId?: string; late?: boolean } = {}): MessageRecord {
  const s = sessions.get(sessionId);
  if (!s) throw new Error(`session ${sessionId} not found`);
  s.lastSeq += 1;
  const m: MessageRecord = {
    id: ulid(),
    seq: s.lastSeq,
    kind,
    payload,
    createdAt: new Date().toISOString(),
    ...(opts.author ? { author: opts.author } : {}),
    ...(opts.callId ? { callId: opts.callId } : {}),
    ...(opts.late ? { late: true } : {}),
  };
  s.messages.set(m.seq, m);
  s.lastActivityAt = m.createdAt;
  return m;
}

function ulid(): string {
  // Cheap ULID-ish id (Crockford base32, 26 chars). Not strict ULID but stable.
  const ts = Date.now().toString(32).padStart(10, '0');
  const rand = Math.floor(Math.random() * 0xffffffff).toString(32).padStart(8, '0');
  const rand2 = Math.floor(Math.random() * 0xffffffff).toString(32).padStart(8, '0');
  return (ts + rand + rand2).toUpperCase().replace(/I|L|O|U/g, 'Z').slice(0, 26);
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(payload),
  });
  res.end(payload);
}

function notFound(res: ServerResponse): void {
  sendJson(res, 404, { type: 'about:blank', title: 'not found', code: 'session-not-found' });
}

function unauthorized(res: ServerResponse): void {
  sendJson(res, 401, { type: 'about:blank', title: 'unauthenticated', code: 'unauthenticated' });
}

function checkAuth(req: IncomingMessage, res: ServerResponse): boolean {
  const auth = req.headers['authorization'];
  if (auth !== `Bearer ${STUB_TOKEN}`) {
    unauthorized(res);
    return false;
  }
  return true;
}

function validateRelativePath(p: string): boolean {
  if (!p || p.startsWith('/') || p.startsWith('\\')) return false;
  if (p.includes('..')) return false;
  return true;
}

// SSE plumbing.
const sessionSseClients = new Map<string, Set<ServerResponse>>();
const taskSseClients = new Map<string, Set<ServerResponse>>();

function openSse(res: ServerResponse, retryMs = 5_000): void {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  });
  // First line is a "retry:" hint so the SSE client backs off correctly on
  // disconnect (api-contracts §3.1: `retry: 5000`).
  res.write(`retry: ${retryMs}\n\n`);
  res.write(': hi\n\n');
}

function broadcastSessionEvent(sessionId: string, frame: { event?: string; id?: string; data: unknown }): void {
  const subs = sessionSseClients.get(sessionId);
  if (!subs) return;
  const wire = formatSseFrame(frame);
  for (const res of subs) {
    try {
      res.write(wire);
    } catch {
      // dead consumer; ignore
    }
  }
}

function formatSseFrame(frame: { event?: string; id?: string; data: unknown; retryMs?: number }): string {
  const data = typeof frame.data === 'string' ? frame.data : JSON.stringify(frame.data);
  const lines: string[] = [];
  if (frame.event) lines.push(`event: ${frame.event}`);
  if (frame.id) lines.push(`id: ${frame.id}`);
  lines.push(`data: ${data}`);
  lines.push('');
  lines.push('');
  return lines.join('\n');
}

function pushSnapshotThenKeepAlive(res: ServerResponse, sessionId: string): void {
  const s = sessions.get(sessionId);
  if (!s) return;
  res.write(formatSseFrame({
    event: 'session.status',
    data: { runtimeStatus: s.runtimeStatus, lastTurnOutcome: s.lastTurnOutcome },
  }));
  let set = sessionSseClients.get(sessionId);
  if (!set) {
    set = new Set();
    sessionSseClients.set(sessionId, set);
  }
  set.add(res);
  // Keep the connection alive with a `: ping` comment every 15 s. Test
  // smoke verifies the comment arrives after a couple of cycles, but the
  // production-grade behavior is to send the heartbeat on the configured
  // `harness.sse.ping-interval` (§3.1).
  const pingTimer = setInterval(() => {
    if (res.writableEnded) {
      clearInterval(pingTimer);
      return;
    }
    try {
      res.write(': ping\n\n');
    } catch {
      clearInterval(pingTimer);
    }
  }, 1_000); // 1 s in tests; real value is `harness.sse.ping-interval` (15 s)
  res.on('close', () => {
    set!.delete(res);
    if (set!.size === 0) sessionSseClients.delete(sessionId);
  });
}

// WS relay plumbing.
interface RelayConnection {
  socket: WebSocket;
  sessionId: string | null;
  basePath: string | null;
  tools: string[];
  identity: string;
}

const relayConns = new Map<string, RelayConnection>(); // key: sessionId|identity

function sendRelay(socket: WebSocket, frame: unknown): void {
  if (socket.readyState !== socket.OPEN) return;
  socket.send(JSON.stringify(frame));
}

function closeRelay(socket: WebSocket, code: number, reason: string): void {
  if (socket.readyState !== socket.OPEN) return;
  socket.close(code, reason);
}

const TOOL_CALL_SCRIPT_COMMAND = 'node -e "process.stdout.write(\'hello-from-client\')"';

function sessionToolScript(sessionId: string): void {
  // Simulate the orchestrator: push a TOOL_CALL for `bash` immediately after
  // the user message (which is appended via POST /messages — the spec triggers
  // us synchronously here for test determinism).
  const s = sessions.get(sessionId);
  if (!s) return;
  s.runtimeStatus = 'TURN_RUNNING';
  broadcastSessionEvent(sessionId, {
    event: 'session.status',
    data: { runtimeStatus: 'TURN_RUNNING' },
  });
  const callId = ulid();
  appendMessage(sessionId, 'TOOL_CALL', {
    callId,
    tool: 'bash',
    arguments: { command: TOOL_CALL_SCRIPT_COMMAND },
  });
  broadcastSessionEvent(sessionId, {
    id: String(s.lastSeq),
    event: 'message.created',
    data: {
      id: 'stub-call-' + callId,
      seq: s.lastSeq,
      kind: 'TOOL_CALL',
      payload: { callId, tool: 'bash', arguments: { command: TOOL_CALL_SCRIPT_COMMAND } },
      callId,
      createdAt: new Date().toISOString(),
    },
  });
  deliverToolCallWhenRegistered(sessionId, callId);
}

/**
 * The orchestrator can only call a client tool through a registered relay
 * connection. The desktop registers asynchronously (auto-connect + consent
 * dialog), so retry delivery for a while instead of dropping the call.
 */
function deliverToolCallWhenRegistered(sessionId: string, callId: string, attempt = 0): void {
  const conn = [...relayConns.values()].find((c) => c.sessionId === sessionId);
  if (conn) {
    sendRelay(conn.socket, {
      type: 'tool.call',
      callId,
      sessionId,
      tool: 'bash',
      args: { command: TOOL_CALL_SCRIPT_COMMAND },
    });
    return;
  }
  if (attempt >= 50) return;
  setTimeout(() => deliverToolCallWhenRegistered(sessionId, callId, attempt + 1), 200);
}

const server = createServer((req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  const pathname = url.pathname;

  // Keycloak issuer stub — desktop's auth bypass skips this, but harmless.
  if (req.method === 'POST' && pathname === '/protocol/openid-connect/token') {
    sendJson(res, 200, {
      access_token: STUB_TOKEN,
      refresh_token: STUB_TOKEN,
      expires_in: 3600,
    });
    return;
  }

  // OpenAPI-prefixed endpoints.
  if (pathname.startsWith('/api/v1/')) {
    if (!checkAuth(req, res)) return;
    return void handleApi(req, res, pathname, url);
  }

  notFound(res);
});

function handleApi(req: IncomingMessage, res: ServerResponse, pathname: string, url: URL): void {
  // /api/v1/agents
  if (req.method === 'GET' && pathname === '/api/v1/agents') {
    sendJson(res, 200, {
      items: [{ key: 'tester', name: 'Tester', latestRev: 1, description: 'Stub agent for e2e' }],
    });
    return;
  }

  // /api/v1/sessions (list, create)
  if (req.method === 'GET' && pathname === '/api/v1/sessions') {
    sendJson(res, 200, { items: [...sessions.values()].map(sessionDto) });
    return;
  }
  if (req.method === 'POST' && pathname === '/api/v1/sessions') {
    void readBody(req).then((body) => {
      const parsed = JSON.parse(body || '{}') as { title?: string; agentKey: string; agentRev?: number };
      const s = newSession('FREE', 'tester', parsed.agentKey, parsed.agentRev ?? 1);
      s.title = parsed.title ?? null;
      sendJson(res, 201, sessionDto(s));
    });
    return;
  }

  // /api/v1/sessions/{id} (get, events, tree, messages, compact, stop, workspace/files)
  const sessionMatch = /^\/api\/v1\/sessions\/([A-Za-z0-9-]+)(\/.*)?$/.exec(pathname);
  if (sessionMatch) {
    const sessionId = sessionMatch[1]!;
    const tail = sessionMatch[2] ?? '';
    const s = sessions.get(sessionId);
    if (!s) {
      notFound(res);
      return;
    }
    if (req.method === 'GET' && tail === '') {
      sendJson(res, 200, sessionDto(s));
      return;
    }
    if (req.method === 'GET' && tail === '/tree') {
      const items = [
        nodeFromSession(s),
        // Add a STATE child for sessions that own one — exercises stateCode in tree response.
        ...[...sessions.values()]
          .filter((c) => c.parentSessionId === s.id)
          .map(nodeFromSession),
      ];
      sendJson(res, 200, { items });
      return;
    }
    if (req.method === 'GET' && tail === '/messages') {
      void readBody(req).then(() => {
        const items = [...s.messages.values()].sort((a, b) => a.seq - b.seq);
        const last = items[items.length - 1];
        sendJson(res, 200, {
          items,
          nextCursor: last ? last.seq : undefined,
        });
      });
      return;
    }
    if (req.method === 'POST' && tail === '/messages') {
      void readBody(req).then((body) => {
        const parsed = JSON.parse(body || '{}') as { text: string };
        const m = appendMessage(sessionId, 'USER', { text: parsed.text }, { author: 'tester' });
        sendJson(res, 202, { messageId: m.id, seq: m.seq });
        // Push USER event so the desktop feed updates.
        broadcastSessionEvent(sessionId, {
          id: String(m.seq),
          event: 'message.created',
          data: {
            id: m.id,
            seq: m.seq,
            kind: 'USER',
            author: 'tester',
            payload: { text: parsed.text },
            createdAt: m.createdAt,
          },
        });
        // Drive the scripted scenario.
        setImmediate(() => sessionToolScript(sessionId));
      });
      return;
    }
    if (req.method === 'POST' && (tail === '/compact' || tail === '/stop')) {
      sendJson(res, 202, undefined);
      return;
    }
    if (req.method === 'GET' && tail === '/events') {
      openSse(res);
      // Resume-from-cursor: `Last-Event-ID` header has priority over `?since=`
      // (api-contracts §3.1). Surface it in the snapshot so the test can assert
      // the header reaches the server.
      const lastEventIdRaw = req.headers['last-event-id'];
      const lastEventId = Array.isArray(lastEventIdRaw) ? lastEventIdRaw[0] : lastEventIdRaw;
      if (lastEventId) {
        res.write(formatSseFrame({
          event: 'message.created',
          id: lastEventId,
          data: { __lastEventId: lastEventId, sessionId },
        }));
      }
      pushSnapshotThenKeepAlive(res, sessionId);
      return;
    }
    if (req.method === 'GET' && tail === '/workspace/files') {
      const p = url.searchParams.get('path') ?? '';
      if (!validateRelativePath(p)) {
        sendJson(res, 422, { type: 'about:blank', title: 'invalid path', code: 'path-invalid' });
        return;
      }
      const full = join(workspaceRoot, p);
      try {
        const stat = statSync(full);
        if (!stat.isFile()) {
          sendJson(res, 404, { type: 'about:blank', title: 'file not found', code: 'file-not-found' });
          return;
        }
        const data = readFileSync(full);
        res.writeHead(200, {
          'Content-Type': 'application/octet-stream',
          'Content-Length': data.byteLength,
        });
        res.end(data);
      } catch (err) {
        if ((err as NodeJS.ErrnoException).code === 'ENOENT') {
          sendJson(res, 404, { type: 'about:blank', title: 'file not found', code: 'file-not-found' });
        } else {
          sendJson(res, 500, { type: 'about:blank', title: 'read error', code: 'file-read-failed' });
        }
      }
      return;
    }
  }

  // /api/v1/tasks/{id}
  const taskMatch = /^\/api\/v1\/tasks\/([A-Za-z0-9-]+)(\/.*)?$/.exec(pathname);
  if (taskMatch) {
    const taskId = taskMatch[1]!;
    const tail = taskMatch[2] ?? '';
    if (req.method === 'GET' && tail === '') {
      sendJson(res, 200, {
        id: taskId,
        title: 'Stub task',
        description: '',
        owner: 'tester',
        author: 'tester',
        workflow: { key: 'feature-delivery', rev: 1 },
        currentState: 'init',
        statusProjection: 'SUCCEEDED',
        suspended: false,
        tags: [],
        params: {},
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      });
      return;
    }
    if (req.method === 'GET' && tail === '/events') {
      openSse(res);
      res.write(formatSseFrame({
        event: 'task.status',
        data: {
          taskId,
          currentState: 'init',
          statusProjection: 'SUCCEEDED',
          suspended: false,
          taskEventSeq: 1,
        },
      }));
      let set = taskSseClients.get(taskId);
      if (!set) {
        set = new Set();
        taskSseClients.set(taskId, set);
      }
      set.add(res);
      res.on('close', () => {
        set!.delete(res);
        if (set!.size === 0) taskSseClients.delete(taskId);
      });
      return;
    }
  }

  notFound(res);
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    req.on('data', (c: Buffer) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', (err: Error) => reject(err));
  });
}

function sessionDto(s: SessionRecord): Record<string, unknown> {
  return {
    id: s.id,
    kind: s.kind,
    title: s.title,
    owner: s.owner,
    agent: { key: s.agentKey, rev: s.agentRev },
    workspaceBinding: { type: 'SERVER_DIR' },
    runtimeStatus: s.runtimeStatus,
    lastTurnOutcome: s.lastTurnOutcome,
    lastSeq: s.lastSeq,
    lastActivityAt: s.lastActivityAt,
    createdAt: s.createdAt,
  };
}

// ====== WS relay /api/v1/relay ======
const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  if (url.pathname !== '/api/v1/relay') {
    socket.destroy();
    return;
  }
  const auth = req.headers['authorization'];
  if (auth !== `Bearer ${STUB_TOKEN}`) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (socket: WebSocket) => {
  let identity = 'anon';
  let conn: RelayConnection | null = null;
  /** True once `hello` has been accepted — frames before that get 4403. */
  let helloed = false;
  /** Last `protocolVersion` we advertised; hello must match exactly. */
  const SUPPORTED_PROTOCOL = 1;

  // Heartbeat: server-initiated JSON `{type:"ping"}`. The desktop's
  // ping-watchdog (§5.4 in api-contracts) reads this and replies with
  // `{type:"pong"}` — exercises the JSON-ping path end-to-end.
  const heartbeat = setInterval(() => {
    if (socket.readyState === socket.OPEN) {
      try {
        socket.send(JSON.stringify({ type: 'ping' }));
      } catch {
        // ignore
      }
    }
  }, 5_000);

  socket.on('message', (raw: Buffer | string) => {
    let frame: { type?: string; [key: string]: unknown };
    try {
      frame = JSON.parse(typeof raw === 'string' ? raw : raw.toString('utf8')) as typeof frame;
    } catch {
      // 4403 — protocol error / unparseable frame. The contract says any
      // frame that cannot be parsed as JSON is a protocol violation.
      closeRelay(socket, 4403, 'bad-frame');
      return;
    }

    if (frame.type !== 'hello' && !helloed) {
      // Frame before the handshake — protocol error, reject with 4403.
      closeRelay(socket, 4403, 'frame-before-hello');
      return;
    }

    if (frame.type === 'hello') {
      const protocolVersion = (frame['protocol'] as number | undefined) ?? SUPPORTED_PROTOCOL;
      if (protocolVersion !== SUPPORTED_PROTOCOL) {
        closeRelay(socket, 4403, 'protocol-mismatch');
        return;
      }
      const id = (frame['identity'] as string | undefined) ?? 'anon';
      identity = id;
      helloed = true;
      // §5.1: the frame field is `protocol` (not `protocolVersion`).
      sendRelay(socket, {
        type: 'welcome',
        protocol: SUPPORTED_PROTOCOL,
        heartbeatIntervalMs: 5_000,
        sessionEventStreamPath: '/api/v1/sessions/{id}/events',
        serverTime: new Date().toISOString(),
      });
      return;
    }

    if (frame.type === 'register') {
      const sessionId = frame['sessionId'] as string | undefined;
      if (!sessionId) {
        closeRelay(socket, 4403, 'wrong-session-kind');
        return;
      }
      const s = sessions.get(sessionId);
      if (!s) {
        closeRelay(socket, 4409, 'session-not-found');
        return;
      }
      const key = `${sessionId}|${identity}`;
      const existing = relayConns.get(key);
      if (existing && existing.socket !== socket) {
        // takeover — same identity, new socket
        try {
          closeRelay(existing.socket, 4409, 'superseded');
        } catch {
          // ignore
        }
        relayConns.delete(key);
      }
      // workspace-occupied sweep omitted for stub: same-identity takeover only
      const declaredTools = (frame['client'] as { tools?: unknown[] } | undefined)?.tools ?? [];
      const registered: RelayConnection = {
        socket,
        sessionId,
        basePath: (frame['basePath'] as string | undefined) ?? null,
        tools: declaredTools.map((t) => (t as { name?: string }).name ?? 'unknown'),
        identity,
      };
      conn = registered;
      relayConns.set(key, registered);
      sendRelay(socket, {
        type: 'registered',
        sessionId,
        toolCount: registered.tools.length,
      });
      return;
    }

    if (frame.type === 'tool.result') {
      // Forward as the synthetic final assistant message.
      const sessionId = conn?.sessionId;
      if (!sessionId) return;
      const callId = frame['callId'] as string | undefined;
      const output = (frame['output'] as string | undefined) ?? '';
      const exitCode = frame['exitCode'] as number | undefined;
      // Append TOOL_RESULT.
      const result = appendMessage(sessionId, 'TOOL_RESULT', {
        callId,
        tool: 'bash',
        status: 'OK',
        output,
        ...(typeof exitCode === 'number' ? { exitCode } : {}),
      });
      broadcastSessionEvent(sessionId, {
        id: String(result.seq),
        event: 'message.created',
        data: {
          id: result.id,
          seq: result.seq,
          kind: 'TOOL_RESULT',
          payload: {
            callId,
            tool: 'bash',
            status: 'OK',
            output,
            ...(typeof exitCode === 'number' ? { exitCode } : {}),
          },
          callId,
          createdAt: result.createdAt,
        },
      });
      // Final assistant message + park session.
      setImmediate(() => {
        const s = sessions.get(sessionId);
        if (!s) return;
        const final = appendMessage(sessionId, 'ASSISTANT', { text: 'Готово. `hello` напечатано в вашем workspace.' });
        broadcastSessionEvent(sessionId, {
          id: String(final.seq),
          event: 'message.created',
          data: {
            id: final.id,
            seq: final.seq,
            kind: 'ASSISTANT',
            payload: { text: 'Готово. `hello` напечатано в вашем workspace.' },
            createdAt: final.createdAt,
          },
        });
        s.runtimeStatus = 'IDLE';
        s.lastTurnOutcome = 'COMPLETED';
        broadcastSessionEvent(sessionId, {
          event: 'session.status',
          data: { runtimeStatus: 'IDLE', lastTurnOutcome: 'COMPLETED' },
        });
      });
      return;
    }

    if (frame.type === 'tool.cancel') {
      // No-op for stub — just acknowledge.
      return;
    }

    if (frame.type === 'pong') {
      return;
    }
  });

  socket.on('close', () => {
    clearInterval(heartbeat);
    if (conn) {
      const key = `${conn.sessionId}|${conn.identity}`;
      if (relayConns.get(key)?.socket === socket) {
        relayConns.delete(key);
      }
    }
  });

  socket.on('error', () => {
    // ignore
  });
});

export interface StubHandle {
  baseUrl: string;
  workspaceRoot: string;
  sessionId: string;
  close(): Promise<void>;
}

export function startStub(): Promise<StubHandle> {
  ensureWorkspace();
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      if (!addr || typeof addr === 'string') {
        throw new Error('stub server failed to bind');
      }
      const baseUrl = `http://127.0.0.1:${addr.port}`;
      resolve({
        baseUrl,
        workspaceRoot,
        sessionId: rootSession.id,
        async close(): Promise<void> {
          for (const s of sessionSseClients.values()) for (const r of s) r.end();
          for (const s of taskSseClients.values()) for (const r of s) r.end();
          for (const conn of relayConns.values()) {
            try { closeRelay(conn.socket, 1000, 'server closing'); } catch { /* ignore */ }
          }
          wss.close();
          rmSync(workspaceRoot, { recursive: true, force: true, maxRetries: 5 });
          await new Promise<void>((r) => server.close(() => r()));
        },
      });
    });
  });
}

// Allow running this file directly to start the stub server for manual
// smoke runs: `node tests/e2e/stub-server.ts`.
const isDirectRun = process.argv[1] && process.argv[1].endsWith('stub-server.ts');
if (isDirectRun) {
  void startStub().then((h) => {
    console.log(JSON.stringify({
      baseUrl: h.baseUrl,
      workspaceRoot: h.workspaceRoot,
      sessionId: h.sessionId,
    }));
  });
}
