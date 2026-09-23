import { beforeEach, describe, expect, it, vi } from 'vitest';

// Electron's safeStorage must be stubbed before the module under test
// is imported: the whole point of this suite is that token persistence
// never falls back to plain text when the keychain is missing.
const enc = new Map<string, string>();

const isEncryptionAvailable = vi.fn(() => true);

vi.mock('electron', () => ({
  app: {
    getPath: () => '/tmp/harness-test-userdata',
    getVersion: () => '0.1.0',
  },
  safeStorage: {
    isEncryptionAvailable,
    // Mirror the real Electron API: encryptString(str) → Buffer,
    // decryptString(Buffer) → string. The `enc:` prefix lets the test
    // prove the bytes on disk are never the plain-text token.
    encryptString: (s: string) => Buffer.from(`enc:${Buffer.from(s).toString('base64')}`),
    decryptString: (buf: Buffer) => {
      const s = buf.toString('utf8');
      if (!s.startsWith('enc:')) throw new Error('ciphertext was not encrypted');
      return Buffer.from(s.slice(4), 'base64').toString('utf8');
    },
  },
}));

vi.mock('node:fs', () => {
  const store = new Map<string, string>();
  return {
    readFileSync: (p: string) => {
      const v = store.get(String(p));
      if (v === undefined) {
        const err = new Error('not found') as NodeJS.ErrnoException;
        err.code = 'ENOENT';
        throw err;
      }
      return Buffer.from(v);
    },
    writeFileSync: (p: string, data: Buffer) => {
      store.set(String(p), data.toString('utf8'));
      enc.set(String(p), data.toString('utf8'));
    },
    unlinkSync: (p: string) => {
      store.delete(String(p));
    },
  };
});

vi.mock('../../src/main/logger.js', () => ({
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
  initLogger: vi.fn(),
}));

const {
  loadTokens,
  saveTokens,
  clearTokens,
  isTokenFresh,
  SafeStorageUnavailableError,
} = await import('../../src/main/token-store');

describe('token storage', () => {
  beforeEach(() => {
    isEncryptionAvailable.mockReturnValue(true);
    clearTokens();
  });

  it('returns null when nothing is stored', () => {
    expect(loadTokens()).toBeNull();
  });

  it('round-trips tokens through safeStorage', () => {
    saveTokens({
      accessToken: 'access-jwt',
      refreshToken: 'refresh-jwt',
      expiresAt: 9_999_999,
    });
    const stored = loadTokens();
    expect(stored).not.toBeNull();
    expect(stored?.accessToken).toBe('access-jwt');
    expect(stored?.refreshToken).toBe('refresh-jwt');
  });

  it('never writes plain text to disk', () => {
    saveTokens({ accessToken: 'secret-jwt', expiresAt: 1 });
    const raw = [...enc.values()][0];
    expect(raw).not.toContain('secret-jwt');
    expect(raw?.startsWith('enc:')).toBe(true);
  });

  it('clearing an already-empty store is a no-op', () => {
    expect(() => clearTokens()).not.toThrow();
  });

  it('treats a near-expiry token as stale given a skew', () => {
    const tokens = { accessToken: 'a', expiresAt: 1_000 };
    expect(isTokenFresh(tokens, 0, 999_000)).toBe(true);
    expect(isTokenFresh(tokens, 30, (1_000 - 29) * 1000)).toBe(false);
  });
});

describe('token storage without a keychain', () => {
  beforeEach(() => {
    isEncryptionAvailable.mockReturnValue(false);
    enc.clear();
  });

  it('refuses to save tokens rather than writing plain text', () => {
    expect(() => saveTokens({ accessToken: 'plain-forbidden', expiresAt: 1 })).toThrowError(
      /keychain/u,
    );
    expect([...enc.values()]).toHaveLength(0);
  });

  it('refuses to load tokens', () => {
    expect(() => loadTokens()).toThrowError(/keychain/u);
  });

  it('names the error so callers can render a readable message', () => {
    expect(() => saveTokens({ accessToken: 'x', expiresAt: 1 })).toThrow(
      SafeStorageUnavailableError,
    );
  });
});
