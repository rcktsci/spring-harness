import { beforeEach, describe, expect, it, vi } from 'vitest';

type Handler = (...args: unknown[]) => void;

/**
 * Subscription callbacks are collected into plain arrays at registration
 * time: the composable wires its listeners exactly once (module-scoped
 * state), and Vitest may clear mock call history between tests — the
 * arrays below survive that.
 */
const statusCbs: Handler[] = [];
const consentCbs: Handler[] = [];
const toolCallCbs: Handler[] = [];

const harness = {
  relay: {
    connect: vi.fn(async () => null),
    register: vi.fn(async () => ({ ok: true })),
    setSession: vi.fn(async () => ({ ok: true })),
    disconnect: vi.fn(async () => undefined),
    status: vi.fn(async () => null),
    confirmRegistration: vi.fn(async () => ({ ok: true })),
    pendingConsent: vi.fn(async () => null),
    onStatus: vi.fn((cb: Handler) => {
      statusCbs.push(cb);
      return () => undefined;
    }),
    onRegistrationConsent: vi.fn((cb: Handler) => {
      consentCbs.push(cb);
      return () => undefined;
    }),
  },
  tool: {
    onCall: vi.fn((cb: Handler) => {
      toolCallCbs.push(cb);
      return () => undefined;
    }),
    onResult: vi.fn((_cb: Handler) => () => undefined),
    respondConfirm: vi.fn(async () => undefined),
    cancel: vi.fn(async () => undefined),
  },
};

vi.stubGlobal('window', { harness });

const { useRelay } = await import('../../src/renderer/src/composables/useRelay');

function emitStatus(payload: unknown): void {
  for (const cb of statusCbs) cb(payload);
}

function emitConsent(payload: unknown): void {
  for (const cb of consentCbs) cb(payload);
}

function emitToolCall(call: unknown, basePath: string): void {
  for (const cb of toolCallCbs) cb(call, basePath);
}

describe('useRelay.ensureConnected', () => {
  beforeEach(() => {
    harness.relay.connect.mockClear().mockImplementation(async () => null);
    harness.relay.register.mockClear().mockImplementation(async () => ({ ok: true }));
    emitStatus(null);
  });

  it('connects and registers for a FREE session', async () => {
    const relay = useRelay();
    await relay.ensureConnected('sess-a', 'FREE');
    expect(harness.relay.connect).toHaveBeenCalledTimes(1);
    expect(harness.relay.register).toHaveBeenCalledWith('sess-a', 'FREE');
  });

  it('never registers a STATE session', async () => {
    const relay = useRelay();
    await relay.ensureConnected('sess-state', 'STATE');
    expect(harness.relay.connect).not.toHaveBeenCalled();
    expect(harness.relay.register).not.toHaveBeenCalled();
  });

  it('skips a session that is already registered', async () => {
    const relay = useRelay();
    emitStatus({ connected: true, registered: true, phase: 'connected', sessionId: 'sess-a' });
    await relay.ensureConnected('sess-a', 'FREE');
    expect(harness.relay.connect).not.toHaveBeenCalled();
    expect(harness.relay.register).not.toHaveBeenCalled();
  });

  it('deduplicates concurrent triggers for the same session', async () => {
    let releaseConnect: () => void = () => undefined;
    harness.relay.connect.mockImplementation(
      () => new Promise<void>((resolve) => { releaseConnect = resolve; }),
    );
    const relay = useRelay();
    const first = relay.ensureConnected('sess-a', 'FREE');
    const second = relay.ensureConnected('sess-a', 'FREE');
    releaseConnect();
    await Promise.all([first, second]);
    expect(harness.relay.connect).toHaveBeenCalledTimes(1);
    expect(harness.relay.register).toHaveBeenCalledTimes(1);
  });

  it('allows a fresh trigger for a different session after a switch', async () => {
    const relay = useRelay();
    await relay.ensureConnected('sess-a', 'FREE');
    await relay.ensureConnected('sess-b', 'FREE');
    expect(harness.relay.register).toHaveBeenCalledWith('sess-b', 'FREE');
  });

  it('runs parallel in-flight triggers for different sessions independently', async () => {
    const resolvers: Array<() => void> = [];
    harness.relay.register.mockImplementation(async () => {
      await new Promise<void>((resolve) => { resolvers.push(resolve); });
      return { ok: true };
    });
    const relay = useRelay();
    const first = relay.ensureConnected('sess-a', 'FREE');
    const second = relay.ensureConnected('sess-b', 'FREE');
    await new Promise((r) => setTimeout(r, 0));
    expect(harness.relay.register).toHaveBeenCalledTimes(2);
    for (const resolve of resolvers) resolve();
    await Promise.all([first, second]);
  });

  it('swallows connect failures (status events surface them)', async () => {
    harness.relay.connect.mockRejectedValue(new Error('down'));
    const relay = useRelay();
    await expect(relay.ensureConnected('sess-a', 'FREE')).resolves.toBeUndefined();
  });
});

describe('useRelay prompts', () => {
  beforeEach(() => {
    harness.relay.confirmRegistration.mockClear().mockImplementation(async () => ({ ok: true }));
    harness.tool.respondConfirm.mockClear().mockImplementation(async () => undefined);
  });

  it('respondConsent forwards the answer and clears the prompt', async () => {
    const relay = useRelay();
    emitConsent({ sessionId: 'sess-a', basePath: '/tmp/ws', tools: ['bash'] });
    expect(relay.consent.value).not.toBeNull();
    await relay.respondConsent(false);
    expect(harness.relay.confirmRegistration).toHaveBeenCalledWith('sess-a', false);
    expect(relay.consent.value).toBeNull();
    await relay.respondConsent(true);
    expect(harness.relay.confirmRegistration).toHaveBeenCalledTimes(1);
  });

  it('respondToolConfirm forwards the answer and clears the prompt', async () => {
    const relay = useRelay();
    emitToolCall(
      { callId: 'call-1', sessionId: 'sess-a', tool: 'bash', args: { command: 'ls' }, basePath: '/tmp/ws' },
      '/tmp/ws',
    );
    expect(relay.toolConfirm.value?.call.callId).toBe('call-1');
    await relay.respondToolConfirm(true);
    expect(harness.tool.respondConfirm).toHaveBeenCalledWith('call-1', true);
    expect(relay.toolConfirm.value).toBeNull();
  });
});
