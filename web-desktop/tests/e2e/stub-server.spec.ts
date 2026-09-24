/**
 * Pure stub-server test (no Electron). Verifies the scripted SSE flow that
 * the Playwright-electron spec drives against the stub.
 */
import { test, expect } from '@playwright/test';
import WS, { type WebSocket as WsSocket } from 'ws';
import { startStub, type StubHandle } from './stub-server';

const WebSocket = WS;

let stub: StubHandle | null = null;

test.beforeAll(async () => {
  stub = await startStub();
});

test.afterAll(async () => {
  await stub?.close();
});

test('stub drives scripted SSE flow end-to-end (WS client drives tool.result)', async () => {
  if (!stub) throw new Error('stub not started');
  const headers = { Authorization: 'Bearer stub-test-token' } as const;
  const base = stub.baseUrl;

  // Sessions list now contains the seeded FREE root plus one STATE sub-session
  // (stub exercises stateCode in the /tree response — covered by a dedicated test).
  const list = await fetch(`${base}/api/v1/sessions`, { headers });
  expect(list.status).toBe(200);
  const listBody = (await list.json()) as { items: Array<{ id: string }> };
  expect(listBody.items.length).toBe(2);
  const sessionId = listBody.items[0]!.id;

  // Open WS relay client (simulates the desktop).
  const wsUrl = base.replace(/^http/, 'ws') + '/api/v1/relay';
  const ws = new WebSocket(wsUrl, { headers });
  const wsFrames: Array<{ type?: string; [key: string]: unknown }> = [];
  ws.on('message', (raw: Buffer | string) => {
    wsFrames.push(JSON.parse(typeof raw === 'string' ? raw : raw.toString('utf8')));
  });
  await new Promise<void>((resolve, reject) => {
    ws.once('open', () => resolve());
    ws.once('error', (err: Error) => reject(err));
  });
  ws.send(JSON.stringify({ type: 'hello', identity: 'tester' }));
  await new Promise((r) => setTimeout(r, 100));
  ws.send(JSON.stringify({ type: 'register', sessionId, basePath: '/tmp', tools: ['bash'] }));
  await new Promise((r) => setTimeout(r, 100));
  expect(wsFrames.some((f) => f.type === 'welcome')).toBe(true);
  expect(wsFrames.some((f) => f.type === 'registered')).toBe(true);

  // Subscribe to SSE.
  const frames: Array<{ event: string; id?: string; data: unknown }> = [];
  const sseReady = new Promise<void>((resolve, reject) => {
    const ctl = new AbortController();
    void fetch(`${base}/api/v1/sessions/${sessionId}/events`, {
      headers,
      signal: ctl.signal,
    }).then(async (res) => {
      if (!res.body) {
        reject(new Error('no SSE body'));
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      const targetMarker = (data: unknown): boolean => {
        if (typeof data !== 'object' || data === null) return false;
        const payload = (data as { payload?: unknown }).payload;
        if (typeof payload !== 'object' || payload === null) return false;
        const t = (payload as { text?: unknown }).text;
        return typeof t === 'string' && t.includes('Готово');
      };
      // Drain until we see the final ASSISTANT frame.
      while (!frames.some((f) => targetMarker(f.data))) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx: number;
        while ((idx = buffer.indexOf('\n\n')) !== -1) {
          const block = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          const ev: { event: string; id?: string; data: unknown } = { event: 'message', data: '' };
          for (const line of block.split('\n')) {
            if (line.startsWith('event: ')) ev.event = line.slice(7);
            else if (line.startsWith('id: ')) ev.id = line.slice(4);
            else if (line.startsWith('data: ')) {
              const raw = line.slice(6);
              try {
                ev.data = JSON.parse(raw);
              } catch {
                ev.data = raw;
              }
            }
          }
          frames.push(ev);
          // Drive WS tool.result back to stub when we see TOOL_CALL.
          if (ev.event === 'message.created') {
            const d = ev.data as { kind?: string; callId?: string } | null;
            if (d?.kind === 'TOOL_CALL' && d.callId) {
              ws.send(JSON.stringify({
                type: 'tool.result',
                callId: d.callId,
                output: 'stub-result',
                exitCode: 0,
              }));
            }
          }
        }
      }
      ctl.abort();
      resolve();
    }).catch(reject);
  });

  await new Promise((r) => setTimeout(r, 200));
  // Drive the scripted flow.
  const post = await fetch(`${base}/api/v1/sessions/${sessionId}/messages`, {
    method: 'POST',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: 'ping' }),
  });
  expect(post.status).toBe(202);

  await sseReady;

  const kinds = frames
    .map((f) => {
      if (f.event === 'session.status') return 'session.status';
      if (f.event === 'message.created') {
        const d = f.data as { kind?: string } | null;
        return d?.kind ?? 'unknown';
      }
      return f.event;
    });
  expect(kinds).toContain('session.status');
  expect(kinds).toContain('USER');
  expect(kinds).toContain('TOOL_CALL');
  expect(kinds).toContain('TOOL_RESULT');
  expect(kinds).toContain('ASSISTANT');

  ws.close();
});

test('workspace file GET (D-72)', async () => {
  if (!stub) throw new Error('stub not started');
  const headers = { Authorization: 'Bearer stub-test-token' } as const;
  const base = stub.baseUrl;

  const list = await fetch(`${base}/api/v1/sessions`, { headers });
  const { items } = (await list.json()) as { items: Array<{ id: string }> };
  const sessionId = items[0]!.id;

  const fileRes = await fetch(`${base}/api/v1/sessions/${sessionId}/workspace/files?path=hello.md`, { headers });
  expect(fileRes.status).toBe(200);
  const bytes = new Uint8Array(await fileRes.arrayBuffer());
  expect(bytes.byteLength).toBeGreaterThan(0);

  // D-72: rejection on `..`.
  const bad = await fetch(`${base}/api/v1/sessions/${sessionId}/workspace/files?path=../etc/passwd`, { headers });
  expect(bad.status).toBe(422);
});

test('WS relay §5: handshake, register, tool.result echo, 4409 superseded', async () => {
  if (!stub) throw new Error('stub not started');
  const headers = { Authorization: 'Bearer stub-test-token' } as const;

  // Get session id.
  const list = await fetch(`${stub.baseUrl}/api/v1/sessions`, { headers });
  const { items } = (await list.json()) as { items: Array<{ id: string }> };
  const sessionId = items[0]!.id;

  // Open WS.
  const wsUrl = stub.baseUrl.replace(/^http/, 'ws') + '/api/v1/relay';
  const ws = new WebSocket(wsUrl, { headers });
  const received: Array<{ type: string; [key: string]: unknown }> = [];
  ws.on('message', (raw: Buffer | string) => {
    received.push(JSON.parse(typeof raw === 'string' ? raw : raw.toString('utf8')));
  });
  await new Promise<void>((resolve, reject) => {
    ws.once('open', () => resolve());
    ws.once('error', (err: Error) => reject(err));
  });

  // hello → welcome.
  ws.send(JSON.stringify({ type: 'hello', identity: 'tester' }));
  await new Promise((r) => setTimeout(r, 200));
  expect(received.some((f) => f.type === 'welcome')).toBe(true);

  // register.
  ws.send(JSON.stringify({
    type: 'register',
    sessionId,
    basePath: '/tmp',
    tools: ['bash', 'read_file'],
  }));
  await new Promise((r) => setTimeout(r, 200));
  const reg = received.find((f) => f.type === 'registered');
  expect(reg).toBeTruthy();
  expect(reg!.sessionId).toBe(sessionId);

  // Second WS connection for the same session+identity → superseded.
  const ws2 = new WebSocket(wsUrl, { headers });
  const ws2Received: Array<{ type?: string }> = [];
  let ws1CloseCode: number | null = null;
  ws.on('close', (code: number) => {
    ws1CloseCode = code;
  });
  await new Promise<void>((resolve, reject) => {
    ws2.once('open', () => resolve());
    ws2.once('error', (err: Error) => reject(err));
  });
  ws2.on('message', (raw: Buffer | string) => {
    ws2Received.push(JSON.parse(typeof raw === 'string' ? raw : raw.toString('utf8')));
  });
  ws2.send(JSON.stringify({ type: 'hello', identity: 'tester' }));
  ws2.send(JSON.stringify({ type: 'register', sessionId, basePath: '/tmp', tools: [] }));
  await new Promise((r) => setTimeout(r, 300));
  expect(ws1CloseCode).toBe(4409);
  expect(ws2Received.some((f) => f.type === 'registered')).toBe(true);

  // 4401 — bad token.
  const ws3 = new WebSocket(wsUrl, { headers: { Authorization: 'Bearer wrong' } });
  await new Promise<void>((resolve, reject) => {
    ws3.once('unexpected-response', (_req: unknown, res: { statusCode: number }) => {
      expect(res.statusCode).toBe(401);
      resolve();
    });
    ws3.once('error', (err: Error) => {
      // ws@8 may surface as error after unexpected-response
      if (err.message.includes('401')) resolve();
      else reject(err);
    });
    ws3.once('open', () => reject(new Error('expected 401, got open')));
  });

  ws.close();
  ws2.close();
  ws3.close();
});

test('WS relay §5: 4403 for protocol mismatch and frame-before-hello', async () => {
  if (!stub) throw new Error('stub not started');
  const headers = { Authorization: 'Bearer stub-test-token' } as const;
  const wsUrl = stub.baseUrl.replace(/^http/, 'ws') + '/api/v1/relay';

  // Helper: wait for the socket to be open before sending.
  const opened = (s: WsSocket): Promise<void> =>
    new Promise<void>((resolve, reject) => {
      s.once('open', () => resolve());
      s.once('error', (err: Error) => reject(err));
    });

  // (a) Frame before hello — stub should close 4403.
  const before = new WebSocket(wsUrl, { headers });
  const beforeClosed = new Promise<number>((resolve, reject) => {
    before.once('close', (code: number) => resolve(code));
    before.once('error', (err: Error) => {
      if (err.message.includes('4403')) resolve(4403);
      else reject(err);
    });
  });
  await opened(before);
  before.send(JSON.stringify({ type: 'register', sessionId: 'noop', basePath: '/tmp', tools: [] }));
  const beforeCode = await beforeClosed;
  expect(beforeCode).toBe(4403);

  // (b) Protocol mismatch — stub should close 4403.
  const mismatch = new WebSocket(wsUrl, { headers });
  const mismatchClosed = new Promise<number>((resolve, reject) => {
    mismatch.once('close', (code: number) => resolve(code));
    mismatch.once('error', (err: Error) => {
      if (err.message.includes('4403')) resolve(4403);
      else reject(err);
    });
  });
  await opened(mismatch);
  mismatch.send(JSON.stringify({ type: 'hello', protocol: 999, identity: 'tester' }));
  const mismatchCode = await mismatchClosed;
  expect(mismatchCode).toBe(4403);
});

test('WS relay §5: JSON ping heartbeat', async () => {
  if (!stub) throw new Error('stub not started');
  const headers = { Authorization: 'Bearer stub-test-token' } as const;
  const wsUrl = stub.baseUrl.replace(/^http/, 'ws') + '/api/v1/relay';
  const ws = new WebSocket(wsUrl, { headers });
  const received: Array<{ type?: string }> = [];
  ws.on('message', (raw: Buffer | string) => {
    received.push(JSON.parse(typeof raw === 'string' ? raw : raw.toString('utf8')));
  });
  await new Promise<void>((resolve, reject) => {
    ws.once('open', () => resolve());
    ws.once('error', (err: Error) => reject(err));
  });
  // Send hello so the server starts the heartbeat interval.
  ws.send(JSON.stringify({ type: 'hello', identity: 'tester' }));
  // Wait for at least one server-initiated ping (interval is 5 s; we may
  // send pong too — both `ping` frames are required, never websocket-level
  // socket.ping()).
  await new Promise((r) => setTimeout(r, 5_500));
  const pings = received.filter((f) => f.type === 'ping');
  expect(pings.length).toBeGreaterThan(0);
  expect(pings[0]).toEqual({ type: 'ping' });
  ws.close();
});

test('SSE §3.1: retry: 5000 + Last-Event-ID header > ?since query', async () => {
  if (!stub) throw new Error('stub not started');
  const headers = { Authorization: 'Bearer stub-test-token' } as const;
  const base = stub.baseUrl;
  const list = await fetch(`${base}/api/v1/sessions`, { headers });
  const { items } = (await list.json()) as { items: Array<{ id: string }> };
  const sessionId = items[0]!.id;

  // (a) retry: hint in first frame.
  const first = await fetch(`${base}/api/v1/sessions/${sessionId}/events`, { headers });
  expect(first.status).toBe(200);
  const reader = first.body!.getReader();
  const decoder = new TextDecoder();
  const { value } = await reader.read();
  const firstBlock = decoder.decode(value);
  expect(firstBlock).toContain('retry: 5000');

  // (b) Last-Event-ID: send `Last-Event-ID: 42`, expect a `message.created`
  // frame with `id: 42` reflecting the header (api-contracts §3.1: header
  // has priority over `?since=`).
  await reader.cancel();
  const lastIdRes = await fetch(`${base}/api/v1/sessions/${sessionId}/events`, {
    headers: { ...headers, 'Last-Event-ID': '42' },
  });
  expect(lastIdRes.status).toBe(200);
  const reader2 = lastIdRes.body!.getReader();
  const { value: v2 } = await reader2.read();
  const decoded = decoder.decode(v2 as unknown as Uint8Array);
  expect(decoded).toContain('id: 42');
  await reader2.cancel();
});

test('/tree includes stateCode? for STATE sub-session', async () => {
  if (!stub) throw new Error('stub not started');
  const headers = { Authorization: 'Bearer stub-test-token' } as const;
  const base = stub.baseUrl;
  const list = await fetch(`${base}/api/v1/sessions`, { headers });
  const { items } = (await list.json()) as { items: Array<{ id: string }> };
  const rootId = items[0]!.id;

  const treeRes = await fetch(`${base}/api/v1/sessions/${rootId}/tree`, { headers });
  expect(treeRes.status).toBe(200);
  const body = (await treeRes.json()) as { items: Array<{ kind: string; stateCode?: string | null; taskId?: string | null; parentSessionId?: string | null; id: string }> };
  // Stub seeds FREE root + one STATE child.
  expect(body.items.length).toBeGreaterThanOrEqual(2);
  const free = body.items.find((n) => n.id === rootId);
  expect(free).toBeDefined();
  expect(free!.kind).toBe('FREE');
  expect(free!.stateCode).toBeNull();
  expect(free!.taskId).toBeNull();
  expect(free!.parentSessionId).toBeNull();
  const state = body.items.find((n) => n.kind === 'STATE' && n.parentSessionId === rootId);
  expect(state).toBeDefined();
  expect(state!.stateCode).toBe('init');
  expect(state!.taskId).toBeTruthy();
});
