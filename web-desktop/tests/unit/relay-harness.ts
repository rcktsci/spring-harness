import { WebSocket, WebSocketServer } from 'ws';

/**
 * Shared harness for relay tests: an in-test WS server that speaks the
 * §5 protocol, plus stubs for electron/log so the client module can be
 * imported without a real Electron runtime.
 */

export interface TestRelayServer {
  url: string;
  clients: WebSocket[];
  /** Frames received from the client under test. */
  received: unknown[];
  send: (frame: unknown) => void;
  close: () => Promise<void>;
  /** Waits until the client has sent a frame matching the predicate. */
  waitFor: (pred: (f: unknown) => boolean) => Promise<unknown>;
  /** Waits until at least `n` frames match the predicate. */
  waitForCount: (pred: (f: unknown) => boolean, n: number) => Promise<unknown[]>;
}

export interface HarnessOptions {
  /** Auto-ack `register` with `registered` (default true). */
  autoAckRegister?: boolean;
}

export async function startRelayServer(
  opts: HarnessOptions = {},
): Promise<TestRelayServer> {
  const autoAck = opts.autoAckRegister !== false;
  const wss = new WebSocketServer({ port: 0 });
  const received: unknown[] = [];
  const clients: WebSocket[] = [];

  wss.on('connection', (ws) => {
    clients.push(ws);
    ws.on('message', (raw) => {
      const frame = JSON.parse(String(raw));
      received.push(frame);
      // Minimal §5 server: acknowledge registration so the client can
      // complete its register handshake. Tests that need an error
      // instead disable auto-ack and send their own frame.
      if (autoAck && frame.type === 'register') {
        ws.send(JSON.stringify({ type: 'registered', sessionId: frame.sessionId }));
      }
    });
  });

  await new Promise<void>((resolve) => wss.on('listening', resolve));
  const { port } = wss.address() as { port: number };

  const waitFor = async (pred: (f: unknown) => boolean): Promise<unknown> => {
    const matches = await waitForCount(pred, 1);
    return matches[0];
  };

  const waitForCount = async (
    pred: (f: unknown) => boolean,
    n: number,
  ): Promise<unknown[]> => {
    const count = (): unknown[] => received.filter(pred);
    if (count().length >= n) return count();
    for (let i = 0; i < 200; i++) {
      await new Promise((r) => setTimeout(r, 10));
      const found = count();
      if (found.length >= n) return found;
    }
    throw new Error(
      `${n} matching frame(s) not received within 2s; got ${JSON.stringify(received)}`,
    );
  };

  return {
    url: `ws://127.0.0.1:${port}`,
    clients,
    received,
    send: (frame: unknown) => {
      for (const c of clients) {
        if (c.readyState === WebSocket.OPEN) c.send(JSON.stringify(frame));
      }
    },
    close: () =>
      new Promise((resolve) => {
        for (const c of clients) c.close(1000, 'test done');
        wss.close(() => resolve());
      }),
    waitFor,
    waitForCount,
  };
}

export function smallConfig(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    serverBaseUrl: 'http://127.0.0.1:0',
    keycloakIssuer: 'http://127.0.0.1:0/realms/harness',
    keycloakClientId: 'test',
    showTray: false,
    theme: 'system',
    logLevel: 'info',
    logMaxSizeBytes: 100_000,
    windowWidth: 1200,
    windowHeight: 800,
    windowMinWidth: 800,
    windowMinHeight: 600,
    windowStateDebounceMs: 100,
    loginWindowWidth: 900,
    loginWindowHeight: 750,
    tokenClockSkewSeconds: 30,
    relayHandshakeTimeoutMs: 400,
    relayHeartbeatIntervalMs: 60_000,
    relayPingWatchdogMultiplier: 2,
    relayReconnectInitialMs: 50,
    relayReconnectMaxMs: 500,
    relayReconnectBackoffFactor: 2,
    relayToolCallTimeoutMs: 5_000,
    relayRegisterTimeoutMs: 1_000,
    toolOutputLimitBytes: 100,
    toolProgressChunkBytes: 32,
    toolKillGraceMs: 2_000,
    toolGlobMaxResults: 1_000,
    toolGrepMaxMatches: 1_000,
    confirmCommands: 'never',
    ...overrides,
  };
}
