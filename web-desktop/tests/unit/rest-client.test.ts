import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../src/main/logger', () => ({
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() },
}));

vi.mock('../../src/main/auth', () => ({
  requireAccessToken: vi.fn(async () => 'test-token'),
}));

import { DEFAULT_CONFIG, type ServerConfig } from '../../src/shared/ipc-contract';
import { compactSession, listAgents, stopSession } from '../../src/main/rest-client';

describe('rest-client empty-body commands', () => {
  const cfg: ServerConfig = { ...DEFAULT_CONFIG, serverBaseUrl: 'http://server.test' };

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('resolves when compact answers 202 with no body', async () => {
    // Сервер: ResponseEntity.accepted().build() — тела нет вообще (SessionCommandsController).
    // Регрессия живого стенда 2026-09-24: res.json() → «Unexpected end of JSON input».
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(null, { status: 202 }));
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch);

    await expect(compactSession(cfg, 's1')).resolves.toBeUndefined();
    expect(fetchMock.mock.calls[0]![0]).toContain('/api/v1/sessions/s1/compact');
  });

  it('resolves when stop answers 202 with no body', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(null, { status: 202 }));
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch);

    await expect(stopSession(cfg, 's1')).resolves.toBeUndefined();
  });

  it('still parses a JSON body', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(
      new Response(JSON.stringify({ items: [{ agentKey: 'main-agent', name: 'A', rev: 1 }] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch);

    await expect(listAgents(cfg)).resolves.toEqual({
      items: [{ agentKey: 'main-agent', name: 'A', rev: 1 }],
    });
  });

  it('still fails loudly on a non-JSON body', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(
      new Response('<html>gateway</html>', { status: 200, headers: { 'Content-Type': 'text/html' } }),
    );
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch);

    await expect(listAgents(cfg)).rejects.toThrow(/not JSON/);
  });
});
