import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../src/main/logger', () => ({
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() },
}));

// Bypass the real token store / safeStorage — the function under test needs an access token.
vi.mock('../../src/main/auth', () => ({
  requireAccessToken: vi.fn(async () => 'test-token'),
}));

import { DEFAULT_CONFIG, type ServerConfig } from '../../src/shared/ipc-contract';
import { fetchWorkspaceFile, WorkspaceFileError } from '../../src/main/rest-client';

describe('fetchWorkspaceFile', () => {
  const cfg: ServerConfig = { ...DEFAULT_CONFIG, serverBaseUrl: 'http://server.test' };
  let fetchMock: ReturnType<typeof vi.fn>;

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns the response body on 200', async () => {
    fetchMock = vi.fn().mockResolvedValueOnce(
      new Response(new Uint8Array([1, 2, 3]), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch);
    const res = await fetchWorkspaceFile(cfg, {
      sessionId: 's1',
      path: 'reports/final.md',
    });
    expect(res.ok).toBe(true);
    expect(await res.arrayBuffer()).toEqual(new Uint8Array([1, 2, 3]).buffer);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/sessions/s1/workspace/files?path=reports%2Ffinal.md');
    expect((init.headers as Record<string, string>).Authorization).toMatch(/^Bearer /);
  });

  it('throws WorkspaceFileError on 404', async () => {
    fetchMock = vi.fn().mockResolvedValueOnce(
      new Response('{"code":"file-not-found"}', { status: 404 }),
    );
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch);
    await expect(
      fetchWorkspaceFile(cfg, { sessionId: 's1', path: 'reports/final.md' }),
    ).rejects.toBeInstanceOf(WorkspaceFileError);
  });

  it('throws WorkspaceFileError on 413 (payload-too-large)', async () => {
    fetchMock = vi.fn().mockResolvedValueOnce(new Response('{"code":"payload-too-large"}', { status: 413 }));
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch);
    await expect(
      fetchWorkspaceFile(cfg, { sessionId: 's1', path: 'big.bin' }),
    ).rejects.toMatchObject({ status: 413 });
  });

  it('throws WorkspaceFileError on 422 (path-invalid / extension-not-allowed)', async () => {
    fetchMock = vi.fn().mockResolvedValueOnce(new Response('{"code":"extension-not-allowed"}', { status: 422 }));
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch);
    let caught: unknown;
    try {
      await fetchWorkspaceFile(cfg, { sessionId: 's1', path: 'a.bin' });
    } catch (err) {
      caught = err;
    }
    expect(caught).toBeInstanceOf(WorkspaceFileError);
    expect((caught as WorkspaceFileError).status).toBe(422);
  });
});
