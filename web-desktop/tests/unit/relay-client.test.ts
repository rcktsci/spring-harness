import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startRelayServer, smallConfig, type TestRelayServer } from './relay-harness';

// vi.mock is hoisted above all imports — these stubs let the main-process
// modules load under plain Node.
vi.mock('electron', () => ({
  app: {
    getPath: () => '/tmp/harness-relay-test',
    getVersion: () => '0.1.0-test',
  },
  safeStorage: {
    isEncryptionAvailable: () => true,
    encryptString: (s: string) => Buffer.from(s),
    decryptString: (b: Buffer) => b.toString('utf8'),
  },
  nativeTheme: { themeSource: 'system' },
  BrowserWindow: class {},
  ipcMain: { handle: vi.fn() },
}));

vi.mock('../../src/main/logger.js', () => ({
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
  initLogger: vi.fn(),
}));

const { RelayClient } = await import('../../src/main/relay-client');

const TOKEN = 'test-bearer';

function makeClient(
  server: TestRelayServer,
  overrides: Record<string, unknown> = {},
): InstanceType<typeof RelayClient> {
  const cfg = smallConfig({ serverBaseUrl: server.url.replace('ws', 'http'), ...overrides });
  return new RelayClient(cfg as never, async () => TOKEN);
}

/** Drives the full connect → hello → welcome → register → registered flow.
 * Consent is auto-approved because these suites exercise the wire protocol,
 * not the confirmation dialog (covered by the consent test below). */
async function happyPath(server: TestRelayServer, client: InstanceType<typeof RelayClient>) {
  const registered = client.register('sess-free', 'FREE', tmpBasePath());
  await server.waitFor((f) => (f as { type?: string }).type === 'hello');
  server.send({ type: 'welcome', protocol: 1 });
  await server.waitFor(() => client.getPendingConsent() !== null);
  await client.resolveRegistrationConsent('sess-free', true);
  await registered;
}

function tmpBasePath(): string {
  return mkdtempSync(join(tmpdir(), 'harness-relay-'));
}

describe('relay client: handshake and frames', () => {
  let server: TestRelayServer;
  let client: InstanceType<typeof RelayClient>;

  beforeEach(async () => {
    server = await startRelayServer();
  });

  afterEach(async () => {
    client?.disconnect();
    await server.close();
  });

  it('connects, sends hello, and completes on welcome', async () => {
    client = makeClient(server);
    const registerPromise = client.register('sess-free', 'FREE', tmpBasePath());

    const hello = (await server.waitFor((f) => (f as { type?: string }).type === 'hello')) as {
      protocol: number;
    };
    expect(hello.protocol).toBe(1);

    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    const outcome = await registerPromise;
    expect(outcome.ok).toBe(true);
  });

  it('rejects a protocol mismatch and does not retry', async () => {
    client = makeClient(server);
    const statuses: string[] = [];
    client.on('status', (s) => statuses.push(s.phase));

    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    // 4403 = protocol-mismatch → fatal, no reconnect
    for (const c of server.clients) c.close(4403, 'protocol-mismatch');
    await client.resolveRegistrationConsent('sess-free', true);
    await expect(p).resolves.toMatchObject({ ok: false });
    expect(statuses).toContain('fatal');
  });

  it('stops reconnecting on 4409 superseded and notifies', async () => {
    client = makeClient(server);
    const statuses: string[] = [];
    client.on('status', (s) => {
      statuses.push(`${s.phase}:${s.code ?? ''}`);
    });

    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await expect(p).resolves.toMatchObject({ ok: true });

    for (const c of server.clients) c.close(4409, 'superseded');
    await new Promise((r) => setTimeout(r, 300));

    expect(statuses).toContain('disconnected:superseded');
    // no reconnect attempt after the fatal-ish 4409
    expect(server.received.filter((f) => (f as { type?: string }).type === 'hello').length).toBe(1);
  });

  it('replies pong to a server ping', async () => {
    client = makeClient(server);
    await happyPath(server, client);

    server.send({ type: 'ping' });
    const pong = await server.waitFor((f) => (f as { type?: string }).type === 'pong');
    expect(pong).toEqual({ type: 'pong' });
  });

  it('reconnects with backoff after a network drop and re-registers', async () => {
    client = makeClient(server, { relayReconnectInitialMs: 30, relayReconnectMaxMs: 90 });
    await happyPath(server, client);

    // simulate an abrupt loss: destroy the socket without a close frame
    for (const c of server.clients) {
      c.removeAllListeners();
      c.terminate();
    }
    server.clients.length = 0;

    // the client should reconnect: a second hello + register arrive
    await server.waitForCount((f) => (f as { type?: string }).type === 'hello', 2);
    const registers = server.received.filter((f) => (f as { type?: string }).type === 'register');
    expect(registers.length).toBeGreaterThanOrEqual(2);
  });

  it('declares the standard tool set on register', async () => {
    client = makeClient(server);
    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);

    const reg = (await server.waitFor(
      (f) => (f as { type?: string }).type === 'register',
    )) as { client: { tools: { name: string }[] } };
    expect(reg.client.tools.map((t) => t.name)).toEqual([
      'bash',
      'read_file',
      'write_file',
      'edit_file',
      'glob',
      'grep',
    ]);
    await p;
  });

  it('surfaces workspace-occupied without attempting takeover', async () => {
    // no auto-ack: the test sends the error frame itself
    await server.close();
    server = await startRelayServer({ autoAckRegister: false });
    client = makeClient(server);
    const statuses: string[] = [];
    client.on('status', (s) => statuses.push(`${s.code}:${s.reason}`));

    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await server.waitFor((f) => (f as { type?: string }).type === 'register');
    server.send({ type: 'error', code: 'workspace-occupied', message: 'no' });

    const outcome = await p;
    expect(outcome.ok).toBe(false);
    expect(outcome.code).toBe('workspace-occupied');
    expect(statuses.some((s) => s.includes('another user'))).toBe(true);
    // no further register attempts were made
    expect(server.received.filter((f) => (f as { type?: string }).type === 'register').length).toBe(1);
  });

  it('refuses to register on a STATE session', async () => {
    client = makeClient(server);
    const outcome = await client.register('sess-state', 'STATE', tmpBasePath());
    expect(outcome.ok).toBe(false);
    expect(outcome.code).toBe('wrong-session-kind');
  });

  it('does not connect when the token is unavailable', async () => {
    const cfg = smallConfig({ serverBaseUrl: server.url.replace('ws', 'http') });
    const noToken = new RelayClient(cfg as never, async () => null);
    const statuses: string[] = [];
    noToken.on('status', (s) => statuses.push(s.reason));
    await noToken.connect();
    expect(statuses).toContain('Not signed in.');
  });

  it('times out the handshake when no welcome arrives, then reconnects', async () => {
    client = makeClient(server, { relayHandshakeTimeoutMs: 200, relayReconnectInitialMs: 50 });
    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    // never send welcome — handshake timer must fire
    await expect(p).resolves.toMatchObject({ ok: false, message: expect.stringContaining('handshake') });
    // reconnect path: a second hello arrives
    await server.waitForCount((f) => (f as { type?: string }).type === 'hello', 2);
  });

  it('terminates an in-flight registration when disconnect arrives during consent', async () => {
    client = makeClient(server);
    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);

    client.disconnect();
    await expect(p).resolves.toMatchObject({
      ok: false,
      code: 'disconnected',
      message: 'relay disconnected',
    });
    expect(client.getPendingConsent()).toBeNull();

    const p2 = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitForCount((f) => (f as { type?: string }).type === 'hello', 2);
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await expect(p2).resolves.toMatchObject({ ok: true });
  });

  it('terminates an in-flight register frame on disconnect instead of timing out', async () => {
    await server.close();
    server = await startRelayServer({ autoAckRegister: false });
    client = makeClient(server, { relayRegisterTimeoutMs: 5_000 });
    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await server.waitFor((f) => (f as { type?: string }).type === 'register');

    client.disconnect();
    await expect(p).resolves.toMatchObject({
      ok: false,
      code: 'disconnected',
      message: 'relay disconnected',
    });
  });

  it('rejects a tool call for a session without registration instead of using a foreign basePath', async () => {
    client = makeClient(server);
    const dirA = tmpBasePath();
    const p1 = client.register('sess-free', 'FREE', dirA);
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await expect(p1).resolves.toMatchObject({ ok: true });

    const cwdCommand = 'node -e "process.stdout.write(\'EXECUTED-IN:\' + process.cwd())"';
    server.send({
      type: 'tool.call',
      callId: 'call-x',
      sessionId: 'sess-never',
      tool: 'bash',
      args: { command: cwdCommand },
    });
    const refused = (await server.waitFor(
      (f) => (f as { type?: string; callId?: string }).type === 'tool.result' && (f as { callId?: string }).callId === 'call-x',
    )) as { output: string; exitCode: number };
    expect(refused.exitCode).toBe(-1);
    expect(refused.output).toContain('no registration for session sess-never');
    expect(refused.output).not.toContain('EXECUTED-IN');
    expect(refused.output).not.toContain(dirA);
  });

  it('clears the session-to-basePath mapping on terminal closes', async () => {
    client = makeClient(server);
    const p1 = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await expect(p1).resolves.toMatchObject({ ok: true });
    expect(client.trackedSessionCount).toBe(1);

    for (const c of server.clients) c.close(4409, 'superseded');
    await new Promise((r) => setTimeout(r, 300));
    expect(client.trackedSessionCount).toBe(0);

    const p2 = client.register('sess-again', 'FREE', tmpBasePath());
    await server.waitForCount((f) => (f as { type?: string }).type === 'hello', 2);
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent()?.sessionId === 'sess-again');
    await client.resolveRegistrationConsent('sess-again', true);
    await expect(p2).resolves.toMatchObject({ ok: true });
    expect(client.trackedSessionCount).toBe(1);
  });

  it('clears the session-to-basePath mapping on a fatal protocol close', async () => {
    client = makeClient(server);
    const p1 = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await expect(p1).resolves.toMatchObject({ ok: true });
    expect(client.trackedSessionCount).toBe(1);

    for (const c of server.clients) c.close(4403, 'protocol-mismatch');
    await new Promise((r) => setTimeout(r, 300));
    expect(client.trackedSessionCount).toBe(0);
  });

  it('executes calls of successively registered sessions in their own workspaces', async () => {
    client = makeClient(server);
    const dirA = tmpBasePath();
    const dirB = tmpBasePath();

    const pA = client.register('sess-a', 'FREE', dirA);
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent()?.sessionId === 'sess-a');
    await client.resolveRegistrationConsent('sess-a', true);
    await expect(pA).resolves.toMatchObject({ ok: true });

    const pB = client.register('sess-b', 'FREE', dirB);
    await server.waitFor(() => client.getPendingConsent()?.sessionId === 'sess-b');
    await client.resolveRegistrationConsent('sess-b', true);
    await expect(pB).resolves.toMatchObject({ ok: true });

    const cwdCommand = 'node -e "process.stdout.write(process.cwd())"';
    server.send({ type: 'tool.call', callId: 'a-1', sessionId: 'sess-a', tool: 'bash', args: { command: cwdCommand } });
    const resultA = (await server.waitFor(
      (f) => (f as { type?: string; callId?: string }).type === 'tool.result' && (f as { callId?: string }).callId === 'a-1',
    )) as { output: string };
    expect(resultA.output).toContain(dirA);

    server.send({ type: 'tool.call', callId: 'b-1', sessionId: 'sess-b', tool: 'bash', args: { command: cwdCommand } });
    const resultB = (await server.waitFor(
      (f) => (f as { type?: string; callId?: string }).type === 'tool.result' && (f as { callId?: string }).callId === 'b-1',
    )) as { output: string };
    expect(resultB.output).toContain(dirB);
  });

  it('uses the freshly registered basePath after a reconnect', async () => {
    client = makeClient(server);
    const dirA = tmpBasePath();
    const dirB = tmpBasePath();

    const p1 = client.register('sess-free', 'FREE', dirA);
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await expect(p1).resolves.toMatchObject({ ok: true });

    for (const c of server.clients) {
      c.removeAllListeners();
      c.terminate();
    }
    server.clients.length = 0;
    await server.waitForCount((f) => (f as { type?: string }).type === 'hello', 2);
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitForCount((f) => (f as { type?: string }).type === 'register', 2);

    const p2 = client.register('sess-free', 'FREE', dirB);
    await expect(p2).resolves.toMatchObject({ ok: true });

    const cwdCommand = 'node -e "process.stdout.write(process.cwd())"';
    server.send({ type: 'tool.call', callId: 'cwd-1', sessionId: 'sess-free', tool: 'bash', args: { command: cwdCommand } });
    const result = (await server.waitFor(
      (f) => (f as { type?: string; callId?: string }).type === 'tool.result' && (f as { callId?: string }).callId === 'cwd-1',
    )) as { output: string };
    expect(result.output).toContain(dirB);
    expect(result.output).not.toContain(dirA);
  });

  it('does not consume the register timeout while consent is pending', async () => {
    client = makeClient(server, { relayRegisterTimeoutMs: 300 });
    const statuses: Array<{ code?: string }> = [];
    client.on('status', (s) => statuses.push({ code: s.code }));

    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);

    await new Promise((r) => setTimeout(r, 700));
    expect(statuses.some((s) => s.code === 'register-timeout')).toBe(false);

    client.resolveRegistrationConsent('sess-free', true);
    await expect(p).resolves.toMatchObject({ ok: true });
  });

  it('surfaces a register-timeout status and allows a retry without restart', async () => {
    await server.close();
    server = await startRelayServer({ autoAckRegister: false });
    client = makeClient(server, { relayRegisterTimeoutMs: 200 });
    const statuses: Array<{ code?: string }> = [];
    client.on('status', (s) => statuses.push({ code: s.code }));

    const p1 = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await expect(p1).resolves.toMatchObject({ ok: false, message: 'registration timeout' });
    expect(statuses.some((s) => s.code === 'register-timeout')).toBe(true);

    const p2 = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await server.waitFor((f) => (f as { type?: string }).type === 'register');
    server.send({ type: 'registered', sessionId: 'sess-free' });
    await expect(p2).resolves.toMatchObject({ ok: true });
  });

  it('asks for first-registration consent and honours decline', async () => {
    client = makeClient(server);
    const consents: Array<{ sessionId: string; tools: string[] }> = [];
    client.on('registrationConsent', (sessionId, _basePath, tools) => {
      consents.push({ sessionId, tools });
    });

    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => consents.length === 1);
    expect(consents[0]?.sessionId).toBe('sess-free');
    expect(consents[0]?.tools).toContain('bash');

    client.resolveRegistrationConsent('sess-free', false);
    await expect(p).resolves.toMatchObject({ ok: false, message: expect.stringContaining('declined') });
    expect(server.received.filter((f) => (f as { type?: string }).type === 'register')).toHaveLength(0);
  });

  it('emits a consent-declined status so the UI can show the reason', async () => {
    client = makeClient(server);
    const statuses: Array<{ registered: boolean; phase: string; code?: string; reason?: string }> = [];
    client.on('status', (s) => statuses.push({ ...s }));

    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    client.resolveRegistrationConsent('sess-free', false);
    await expect(p).resolves.toMatchObject({ ok: false });

    const declined = statuses.find((s) => s.code === 'consent-declined');
    expect(declined).toBeDefined();
    expect(declined).toMatchObject({ registered: false, phase: 'connected' });
    expect(declined?.reason).toContain('declined');
  });

  it('refuses a parallel registration of a different session without disturbing the first', async () => {
    client = makeClient(server);
    const consents: string[] = [];
    client.on('registrationConsent', (sessionId) => consents.push(sessionId));

    const first = client.register('sess-a', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => consents.includes('sess-a'));

    const second = await client.register('sess-b', 'FREE', tmpBasePath());
    expect(second).toMatchObject({ ok: false, code: 'register-in-progress' });
    expect(consents).toEqual(['sess-a']);

    client.resolveRegistrationConsent('sess-a', true);
    await expect(first).resolves.toMatchObject({ ok: true });
    const registered = server.received.filter((f) => (f as { sessionId?: string }).sessionId === 'sess-a');
    expect(registered.length).toBeGreaterThan(0);
  });

  it('rejects a duplicate same-session register while consent is pending', async () => {
    client = makeClient(server);
    const consents: string[] = [];
    client.on('registrationConsent', (sessionId) => consents.push(sessionId));

    const first = client.register('sess-a', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => consents.length === 1);

    const duplicate = await client.register('sess-a', 'FREE', tmpBasePath());
    expect(duplicate).toMatchObject({ ok: false, code: 'consent-pending' });
    expect(consents).toEqual(['sess-a']);

    client.resolveRegistrationConsent('sess-a', true);
    await expect(first).resolves.toMatchObject({ ok: true });
  });

  it('keeps the consent request retrievable until the user answers', async () => {
    client = makeClient(server);
    const consents: Array<{ sessionId: string; basePath: string }> = [];
    client.on('registrationConsent', (sessionId, basePath) => {
      consents.push({ sessionId, basePath });
    });

    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => consents.length === 1);

    // Renderer re-fetch path: the request must still be pending here.
    expect(client.getPendingConsent()).toMatchObject({ sessionId: 'sess-free' });

    client.resolveRegistrationConsent('sess-free', true);
    await p;
    expect(client.getPendingConsent()).toBeNull();
  });

  it('ignores an answer with no open consent and asks again on the next registration', async () => {
    client = makeClient(server);
    const consents: string[] = [];
    client.on('registrationConsent', (sessionId) => consents.push(sessionId));

    client.resolveRegistrationConsent('sess-free', true);
    client.resolveRegistrationConsent('sess-free', false);
    expect(client.getPendingConsent()).toBeNull();

    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => consents.length === 1);
    expect(client.getPendingConsent()).toMatchObject({ sessionId: 'sess-free' });
    expect(server.received.filter((f) => (f as { type?: string }).type === 'register')).toHaveLength(0);

    client.resolveRegistrationConsent('sess-free', true);
    await expect(p).resolves.toMatchObject({ ok: true });
  });

  it('delivers exactly one answer per consent prompt', async () => {
    client = makeClient(server);
    const consents: string[] = [];
    client.on('registrationConsent', (sessionId) => consents.push(sessionId));

    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => consents.length === 1);

    client.resolveRegistrationConsent('sess-free', true);
    client.resolveRegistrationConsent('sess-free', false);
    await expect(p).resolves.toMatchObject({ ok: true });
    expect(consents).toEqual(['sess-free']);
  });

  it('resolves pending command confirmations with a rejection on disconnect', async () => {
    client = makeClient(server, { confirmCommands: 'always' });
    await happyPath(server, client);

    const confirms: string[] = [];
    const rejections: Array<{ callId: string; output: string }> = [];
    client.on('toolCall', (call) => confirms.push(call.callId));
    client.on('toolResult', (callId, output) => rejections.push({ callId, output }));

    server.send({
      type: 'tool.call',
      callId: 'call-dc',
      sessionId: 'sess-free',
      tool: 'bash',
      args: { command: 'echo never-runs' },
    });
    await server.waitFor(() => confirms.includes('call-dc'));

    client.disconnect();
    await new Promise((r) => setTimeout(r, 50));
    expect(rejections).toEqual([{ callId: 'call-dc', output: 'command rejected by user' }]);
  });

  it('requires confirmation before bash when confirmCommands=always', async () => {
    client = makeClient(server, { confirmCommands: 'always' });
    await happyPath(server, client);

    const confirms: string[] = [];
    client.on('toolCall', (call) => confirms.push(call.callId));

    server.send({
      type: 'tool.call',
      callId: 'call-1',
      sessionId: 'sess-free',
      tool: 'bash',
      args: { command: 'echo should-not-run-yet' },
    });
    await server.waitFor(() => confirms.includes('call-1'));
    // deny → rejected result, no bash output
    client.resolveConfirmation('call-1', false);
    const denied = (await server.waitFor(
      (f) => (f as { type?: string; callId?: string }).type === 'tool.result'
        && (f as { callId?: string }).callId === 'call-1',
    )) as { output: string; exitCode: number };
    expect(denied.output).toContain('rejected');
    expect(denied.exitCode).toBe(-1);
  });

  it('runs bash after confirmation is approved', async () => {
    client = makeClient(server, { confirmCommands: 'always' });
    await happyPath(server, client);

    const confirms: string[] = [];
    client.on('toolCall', (call) => confirms.push(call.callId));
    server.send({
      type: 'tool.call',
      callId: 'call-2',
      sessionId: 'sess-free',
      tool: 'bash',
      args: { command: 'echo confirmed-run' },
    });
    await server.waitFor(() => confirms.includes('call-2'));
    client.resolveConfirmation('call-2', true);
    const result = (await server.waitFor(
      (f) => (f as { type?: string; callId?: string }).type === 'tool.result'
        && (f as { callId?: string }).callId === 'call-2',
    )) as { output: string; exitCode: number };
    expect(result.exitCode).toBe(0);
    expect(result.output).toContain('confirmed-run');
  });

  it('suppresses tool.result when cancel arrives during spawn', async () => {
    client = makeClient(server);
    await happyPath(server, client);

    server.send({
      type: 'tool.call',
      callId: 'slow-1',
      sessionId: 'sess-free',
      tool: 'bash',
      args: { command: 'node -e "setTimeout(()=>{},8000)"' },
    });
    // let the process spawn, then cancel
    await new Promise((r) => setTimeout(r, 200));
    server.send({ type: 'tool.cancel', callId: 'slow-1' });

    // cancel for unknown id must be ignored (no crash / no result for it either)
    server.send({ type: 'tool.cancel', callId: 'never-existed' });

    // wait past SIGKILL grace; no tool.result for slow-1 may arrive
    await new Promise((r) => setTimeout(r, 500));
    const results = server.received.filter(
      (f) => (f as { type?: string; callId?: string }).type === 'tool.result'
        && (f as { callId?: string }).callId === 'slow-1',
    );
    expect(results).toHaveLength(0);
  });

  it('emits tool.progress for long bash output before the final result', async () => {
    client = makeClient(server, { toolOutputLimitBytes: 50, toolProgressChunkBytes: 16 });
    await happyPath(server, client);

    server.send({
      type: 'tool.call',
      callId: 'long-1',
      sessionId: 'sess-free',
      tool: 'bash',
      args: { command: 'node -e "process.stdout.write(\\"y\\".repeat(400))"' },
    });
    await server.waitFor(
      (f) => (f as { type?: string; callId?: string }).type === 'tool.result'
        && (f as { callId?: string }).callId === 'long-1',
    );
    const progress = server.received.filter((f) => (f as { type?: string }).type === 'tool.progress');
    expect(progress.length).toBeGreaterThan(0);
  });

  it('resets the backoff ladder after a successful welcome (M1)', async () => {
    client = makeClient(server, {
      relayHandshakeTimeoutMs: 150,
      relayReconnectInitialMs: 50,
      relayReconnectMaxMs: 4_000,
    });

    // First attempt: no welcome → handshake timeout → attempt becomes 1.
    const p1 = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitForCount((f) => (f as { type?: string }).type === 'hello', 1);
    await expect(p1).resolves.toMatchObject({ ok: false });
    expect(client.backoffAttempt).toBeGreaterThanOrEqual(1);

    // Second attempt gets a welcome → counter must reset to 0.
    await server.waitForCount((f) => (f as { type?: string }).type === 'hello', 2);
    server.send({ type: 'welcome', protocol: 1 });
    await new Promise((r) => setTimeout(r, 50));
    expect(client.backoffAttempt).toBe(0);
  });

  it('forces reconnect via ping-watchdog when the server goes silent', async () => {
    client = makeClient(server, {
      relayHeartbeatIntervalMs: 50,
      relayPingWatchdogMultiplier: 2,
      relayReconnectInitialMs: 50,
      relayReconnectMaxMs: 200,
    });
    await happyPath(server, client);

    // Server never pings → watchdog (50×2=100ms) must close and reconnect.
    await server.waitForCount((f) => (f as { type?: string }).type === 'hello', 2);
  });

  it('silent-refreshes on close 4401 and reconnects with a fresh token', async () => {
    let tokenCalls = 0;
    const cfg = smallConfig({
      serverBaseUrl: server.url.replace('ws', 'http'),
      relayReconnectInitialMs: 50,
    });
    client = new RelayClient(cfg as never, async () => {
      tokenCalls += 1;
      return TOKEN;
    });
    await happyPath(server, client);
    const before = tokenCalls;

    for (const c of server.clients) c.close(4401, 'unauthenticated');
    await server.waitForCount((f) => (f as { type?: string }).type === 'hello', 2);
    // handle401 + connect both request a token
    expect(tokenCalls).toBeGreaterThan(before);
  });

  it.each(['session-not-found', 'wrong-session-kind', 'duplicate-tool-name'] as const)(
    'surfaces register error frame code %s',
    async (code) => {
      await server.close();
      server = await startRelayServer({ autoAckRegister: false });
      client = makeClient(server);
      const statuses: string[] = [];
      client.on('status', (s) => statuses.push(`${s.code}:${s.reason}`));

      const p = client.register('sess-free', 'FREE', tmpBasePath());
      await server.waitFor((f) => (f as { type?: string }).type === 'hello');
      server.send({ type: 'welcome', protocol: 1 });
      await server.waitFor(() => client.getPendingConsent() !== null);
      await client.resolveRegistrationConsent('sess-free', true);
      await server.waitFor((f) => (f as { type?: string }).type === 'register');
      server.send({ type: 'error', code, message: `raw ${code}` });

      const outcome = await p;
      expect(outcome.ok).toBe(false);
      expect(outcome.code).toBe(code);
      expect(statuses.some((s) => s.startsWith(`${code}:`))).toBe(true);
      expect(client.currentStatus.registered).toBe(false);
    },
  );

  it('keeps the register error reason across a following close 4409 (M2)', async () => {
    await server.close();
    server = await startRelayServer({ autoAckRegister: false });
    client = makeClient(server);
    const statuses: string[] = [];
    client.on('status', (s) => statuses.push(`${s.code}:${s.reason}`));

    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await server.waitFor((f) => (f as { type?: string }).type === 'register');
    server.send({ type: 'error', code: 'workspace-occupied', message: 'no' });
    await expect(p).resolves.toMatchObject({ ok: false, code: 'workspace-occupied' });

    // §5.2: refusal = error frame + close 4409 — must not become "superseded"
    for (const c of server.clients) c.close(4409, 'workspace-occupied');
    await new Promise((r) => setTimeout(r, 200));

    expect(statuses.some((s) => s.startsWith('workspace-occupied:') && s.includes('another user'))).toBe(true);
    expect(statuses.some((s) => s.startsWith('superseded:'))).toBe(false);
    expect(statuses.filter((s) => s.startsWith('disconnected:superseded'))).toHaveLength(0);
  });

  it('removes the frame listener when registration times out (M4)', async () => {
    await server.close();
    server = await startRelayServer({ autoAckRegister: false });
    client = makeClient(server, { relayRegisterTimeoutMs: 100 });

    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await server.waitFor((f) => (f as { type?: string }).type === 'register');
    // no registered/error reply → timeout path
    await expect(p).resolves.toMatchObject({ ok: false, message: expect.stringContaining('timeout') });
    expect(client.listenerCount('frame')).toBe(0);
    expect(client.currentStatus.registered).toBe(false);
  });

  it('sends tool.result for an invalid grep pattern instead of hanging (M3)', async () => {
    client = makeClient(server);
    await happyPath(server, client);

    server.send({
      type: 'tool.call',
      callId: 'bad-grep',
      sessionId: 'sess-free',
      tool: 'grep',
      args: { pattern: '(' },
    });
    const result = (await server.waitFor(
      (f) => (f as { type?: string; callId?: string }).type === 'tool.result'
        && (f as { callId?: string }).callId === 'bad-grep',
    )) as { output: string; exitCode: number };
    expect(result.exitCode).not.toBe(0);
    expect(result.output).toContain('invalid pattern');
  });

  it('does not report registered=true after a register refusal (D3)', async () => {
    await server.close();
    server = await startRelayServer({ autoAckRegister: false });
    client = makeClient(server);

    const p = client.register('sess-free', 'FREE', tmpBasePath());
    await server.waitFor((f) => (f as { type?: string }).type === 'hello');
    server.send({ type: 'welcome', protocol: 1 });
    await server.waitFor(() => client.getPendingConsent() !== null);
    await client.resolveRegistrationConsent('sess-free', true);
    await server.waitFor((f) => (f as { type?: string }).type === 'register');
    expect(client.currentStatus.registered).toBe(false);

    server.send({ type: 'error', code: 'session-not-found', message: 'gone' });
    await p;
    expect(client.currentStatus.registered).toBe(false);
  });
});
