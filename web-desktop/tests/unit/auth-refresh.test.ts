import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('electron', () => ({
  BrowserWindow: class {},
  shell: { openExternal: vi.fn() },
}));

const log = { info: vi.fn(), warn: vi.fn(), error: vi.fn() };
vi.mock('../../src/main/logger.js', () => ({ log, initLogger: vi.fn() }));

const store = {
  tokens: null as unknown,
  save: vi.fn(),
  clear: vi.fn(),
};
vi.mock('../../src/main/token-store.js', () => ({
  loadTokens: () => store.tokens,
  saveTokens: (tokens: unknown) => store.save(tokens),
  clearTokens: () => {
    store.clear();
    store.tokens = null;
  },
}));

const now = Math.floor(Date.now() / 1000);
const cfg = {
  serverBaseUrl: 'http://127.0.0.1:1',
  keycloakIssuer: 'http://127.0.0.1:1/realms/harness',
  keycloakClientId: 'test-client',
  keycloakRequestTimeoutMs: 5_000,
} as never;

function okTokenResponse(body: Record<string, unknown>) {
  return { ok: true, status: 200, text: async () => JSON.stringify(body) };
}

function errorResponse(status: number, body: string) {
  return { ok: false, status, text: async () => body };
}

async function importAuth() {
  return await import('../../src/main/auth');
}

describe('silent refresh single-flight', () => {
  beforeEach(() => {
    vi.resetModules();
    log.info.mockClear();
    log.warn.mockClear();
    log.error.mockClear();
    store.save.mockClear();
    store.clear.mockClear();
    store.tokens = null;
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('runs exactly one network refresh for a batch of parallel consumers', async () => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    const fetchMock = vi.fn(async () => okTokenResponse({ access_token: 'fresh', refresh_token: 'r2', expires_in: 900 }));
    vi.stubGlobal('fetch', fetchMock);
    const { requireAccessToken } = await importAuth();

    const results = await Promise.all(Array.from({ length: 5 }, () => requireAccessToken(cfg, 30)));

    expect(results).toEqual(['fresh', 'fresh', 'fresh', 'fresh', 'fresh']);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(store.save).toHaveBeenCalledTimes(1);
    const saved = store.save.mock.calls[0]![0] as { accessToken: string; refreshToken: string; expiresAt: number };
    expect(saved).toMatchObject({ accessToken: 'fresh', refreshToken: 'r2' });
    expect(saved.expiresAt).toBeGreaterThan(now + 800);
    expect(store.clear).not.toHaveBeenCalled();
  });

  it('clears tokens exactly once when Keycloak rejects the grant', async () => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    const fetchMock = vi.fn(async () => errorResponse(400, '{"error":"invalid_grant","error_description":"Token is not active"}'));
    vi.stubGlobal('fetch', fetchMock);
    const { requireAccessToken } = await importAuth();

    const attempts = Array.from({ length: 5 }, () => requireAccessToken(cfg, 30));
    for (const attempt of attempts) {
      await expect(attempt).rejects.toThrow('Not signed in');
    }
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(store.clear).toHaveBeenCalledTimes(1);
    expect(store.save).not.toHaveBeenCalled();
    const logged = log.error.mock.calls[0]?.[1] as Error;
    expect(logged.message).toContain('invalid_grant');
    expect(logged.message).toContain('Token is not active');
  });

  it('keeps tokens when the token endpoint is unreachable', async () => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    const fetchMock = vi.fn(async () => {
      throw new Error('ECONNREFUSED');
    });
    vi.stubGlobal('fetch', fetchMock);
    const { refreshIfNeeded } = await importAuth();

    const results = await Promise.all(Array.from({ length: 5 }, () => refreshIfNeeded(cfg, 30)));

    expect(results).toEqual([null, null, null, null, null]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(store.clear).not.toHaveBeenCalled();
    expect(store.save).not.toHaveBeenCalled();
    const logged = log.warn.mock.calls[0]?.[1] as Error;
    expect(logged.message).toContain('ECONNREFUSED');
  });

  it('keeps tokens on a 5xx from the token endpoint', async () => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    vi.stubGlobal('fetch', vi.fn(async () => errorResponse(503, 'service unavailable')));
    const { refreshIfNeeded } = await importAuth();

    const results = await Promise.all(Array.from({ length: 3 }, () => refreshIfNeeded(cfg, 30)));

    expect(results).toEqual([null, null, null]);
    expect(store.clear).not.toHaveBeenCalled();
    expect(store.save).not.toHaveBeenCalled();
  });

  it('starts a fresh refresh for the next batch after a transient failure', async () => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    const fetchMock = vi.fn<() => Promise<unknown>>(async () => {
      throw new Error('ECONNREFUSED');
    });
    vi.stubGlobal('fetch', fetchMock);
    const { refreshIfNeeded } = await importAuth();

    await Promise.all(Array.from({ length: 3 }, () => refreshIfNeeded(cfg, 30)));
    fetchMock.mockImplementation(async () => okTokenResponse({ access_token: 'recovered', expires_in: 900 }));

    const second = await refreshIfNeeded(cfg, 30);

    expect(second).toBe('recovered');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('does not hit the token endpoint when the access token is fresh', async () => {
    store.tokens = { accessToken: 'tok', refreshToken: 'r1', expiresAt: now + 600 };
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const { requireAccessToken } = await importAuth();

    const results = await Promise.all(Array.from({ length: 5 }, () => requireAccessToken(cfg, 30)));

    expect(results).toEqual(['tok', 'tok', 'tok', 'tok', 'tok']);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(store.save).not.toHaveBeenCalled();
  });

  it.each([408, 429])('treats %i from the token endpoint as transient', async (status) => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    vi.stubGlobal('fetch', vi.fn(async () => errorResponse(status, 'slow down')));
    const { refreshIfNeeded } = await importAuth();

    const results = await Promise.all(Array.from({ length: 3 }, () => refreshIfNeeded(cfg, 30)));

    expect(results).toEqual([null, null, null]);
    expect(store.clear).not.toHaveBeenCalled();
    expect(store.save).not.toHaveBeenCalled();
  });

  it('includes the response body when a 2xx reply has no access_token', async () => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    vi.stubGlobal('fetch', vi.fn(async () => okTokenResponse({ foo: 'bar' })));
    const { refreshIfNeeded } = await importAuth();

    const result = await refreshIfNeeded(cfg, 30);

    expect(result).toBeNull();
    expect(store.clear).not.toHaveBeenCalled();
    expect(store.save).not.toHaveBeenCalled();
    const logged = log.warn.mock.calls[0]?.[1] as Error;
    expect(logged.message).toContain('access_token');
    expect(logged.message).toContain('{"foo":"bar"}');
  });

  it('aborts a hanging token endpoint request by the configured timeout', async () => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    const kcCfg = { ...(cfg as Record<string, unknown>), keycloakRequestTimeoutMs: 50 } as never;
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: unknown, init: { signal?: AbortSignal }) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => {
            const timeoutError = new Error('The operation was aborted due to timeout');
            timeoutError.name = 'TimeoutError';
            reject(timeoutError);
          });
        })),
    );
    const { refreshIfNeeded } = await importAuth();

    const result = await refreshIfNeeded(kcCfg, 30);

    expect(result).toBeNull();
    expect(store.clear).not.toHaveBeenCalled();
    expect(store.save).not.toHaveBeenCalled();
    const logged = log.warn.mock.calls[0]?.[1] as Error;
    expect(logged.message).toContain('timed out after 50 ms');
    expect(logged.message).not.toContain('unreachable');
  });

  it('masks secret fields of the response body in error logs', async () => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    const body = '{"refresh_token":"SECRET-RT","id_token":"SECRET-IDT","scope":"openid"}';
    vi.stubGlobal('fetch', vi.fn(async () => okTokenResponse(JSON.parse(body))));
    const { refreshIfNeeded } = await importAuth();

    await refreshIfNeeded(cfg, 30);

    const logged = log.warn.mock.calls[0]?.[1] as Error;
    expect(logged.message).toContain('"refresh_token":"[masked]"');
    expect(logged.message).toContain('"id_token":"[masked]"');
    expect(logged.message).toContain('"scope":"openid"');
    expect(logged.message).not.toContain('SECRET-RT');
    expect(logged.message).not.toContain('SECRET-IDT');
  });

  it('masks the whole subtree under a secret container key', async () => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    const body = JSON.stringify({
      credentials: { user: 'u', plain: 'p' },
      tokens: [{ access_token: 'A', note: 'n' }],
      scope: 'openid',
    });
    vi.stubGlobal('fetch', vi.fn(async () => okTokenResponse(JSON.parse(body))));
    const { refreshIfNeeded } = await importAuth();

    await refreshIfNeeded(cfg, 30);

    const logged = log.warn.mock.calls[0]?.[1] as Error;
    expect(logged.message).toContain('"credentials":"[masked]"');
    expect(logged.message).toContain('"tokens":"[masked]"');
    expect(logged.message).toContain('"scope":"openid"');
    expect(logged.message).not.toContain('"user"');
    expect(logged.message).not.toContain('"plain"');
    expect(logged.message).not.toContain('"note"');
    expect(logged.message).not.toContain('"access_token"');
  });

  it('does not log jwt-like strings from a non-JSON body', async () => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    const body = 'AccessToken: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.s1gn4tur3-part-9';
    vi.stubGlobal('fetch', vi.fn(async () => errorResponse(400, body)));
    const { requireAccessToken } = await importAuth();

    await expect(requireAccessToken(cfg, 30)).rejects.toThrow('Not signed in');

    const logged = log.error.mock.calls[0]?.[1] as Error;
    expect(logged.message).toContain('[jwt-like]');
    expect(logged.message).toContain('AccessToken:');
    expect(logged.message).not.toContain('eyJhbGciOiJIUzI1NiJ9');
    expect(logged.message).not.toContain('s1gn4tur3-part-9');
  });

  it('truncates oversized response bodies in error logs', async () => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    const oversized = 'x'.repeat(1000);
    vi.stubGlobal('fetch', vi.fn(async () => errorResponse(400, oversized)));
    const { requireAccessToken } = await importAuth();

    await expect(requireAccessToken(cfg, 30)).rejects.toThrow('Not signed in');

    const logged = log.error.mock.calls[0]?.[1] as Error;
    expect(logged.message).toContain('refresh endpoint 400');
    expect(logged.message.length).toBeLessThan(oversized.length);
    expect(logged.message).not.toContain('x'.repeat(900));
  });

  it('discards an in-flight refresh result when logout happens mid-flight', async () => {
    store.tokens = { accessToken: 'stale', refreshToken: 'r1', expiresAt: now - 10 };
    let release: () => void = () => undefined;
    const fetchMock = vi.fn(async () => {
      await new Promise<void>((resolve) => { release = resolve; });
      return okTokenResponse({ access_token: 'zombie', refresh_token: 'r2', expires_in: 900 });
    });
    vi.stubGlobal('fetch', fetchMock);
    const { requireAccessToken, logout, readLoginState } = await importAuth();

    const batch = Array.from({ length: 3 }, () => requireAccessToken(cfg, 30));
    await new Promise((r) => setTimeout(r, 0));
    await logout(cfg);
    release();
    for (const attempt of batch) {
      await expect(attempt).rejects.toThrow('Not signed in');
    }

    expect(store.save).not.toHaveBeenCalled();
    expect(store.tokens).toBeNull();
    expect(readLoginState(30)).toEqual({ loggedIn: false });
  });
});
