import { describe, expect, it, vi } from 'vitest';

// auth.ts дергает electron (BrowserWindow/shell) и node:http — для проверки
// чистой логики пригодности сессии достаточно заглушек.
vi.mock('electron', () => ({
  BrowserWindow: class {},
  shell: { openExternal: vi.fn() },
}));

import { isSessionUsable } from '../../src/main/auth';
import type { TokenSet } from '../../src/main/token-store';

const now = Math.floor(Date.now() / 1000);

function tokens(partial: Partial<TokenSet>): TokenSet {
  return { accessToken: 'a', expiresAt: now + 600, ...partial };
}

describe('isSessionUsable', () => {
  it('accepts a fresh access token', () => {
    expect(isSessionUsable(tokens({}), 30)).toBe(true);
  });

  it('accepts an expired token that can be silently refreshed', () => {
    expect(isSessionUsable(tokens({ expiresAt: now - 10, refreshToken: 'r' }), 30)).toBe(true);
  });

  it('rejects an expired token without a refresh token', () => {
    // Регрессия блокировки (живой стенд 2026-09-24): мёртвый токен без refresh
    // считался «залогиненным» — гейт пускал в чат, каждый вызов падал
    // «Not signed in», а на /login не пускал.
    expect(isSessionUsable(tokens({ expiresAt: now - 10 }), 30)).toBe(false);
  });

  it('rejects an expired token with an empty refresh token', () => {
    expect(isSessionUsable(tokens({ expiresAt: now - 10, refreshToken: '' }), 30)).toBe(false);
  });

  it('respects the clock skew', () => {
    expect(isSessionUsable(tokens({ expiresAt: now + 10 }), 30)).toBe(false);
  });
});
