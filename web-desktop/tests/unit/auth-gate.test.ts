// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { entryRoute, installAuthGuard } from '../../src/renderer/src/auth-gate';

describe('entryRoute', () => {
  it('sends a signed-out visitor to /login', () => {
    expect(entryRoute('chat', false)).toBe('login');
    expect(entryRoute('settings', false)).toBe('login');
  });

  it('keeps /login when signed out', () => {
    expect(entryRoute('login', false)).toBeUndefined();
  });

  it('bounces a signed-in user from /login to /chat', () => {
    expect(entryRoute('login', true)).toBe('chat');
    expect(entryRoute(undefined, true)).toBe('chat');
  });

  it('leaves other routes alone when signed in', () => {
    expect(entryRoute('chat', true)).toBeUndefined();
    expect(entryRoute('artifacts', true)).toBeUndefined();
  });
});

describe('installAuthGuard', () => {
  let guard: (to: { name?: string }) => Promise<string | null>;

  beforeEach(() => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const router = {
      beforeEach: (fn: typeof guard) => {
        guard = fn;
      },
    };
    installAuthGuard(router as never, pinia);
  });

  it('asks main once and redirects a signed-in user away from /login', async () => {
    const loginState = vi.fn(async () => ({ loggedIn: true }));
    (window as unknown as { harness: unknown }).harness = { auth: { loginState } };
    expect(await guard({ name: 'login' })).toBe('chat');
    expect(await guard({ name: 'login' })).toBe('chat');
    expect(loginState).toHaveBeenCalledTimes(1);
  });

  it('parks a signed-out visitor on /login', async () => {
    (window as unknown as { harness: unknown }).harness = {
      auth: { loginState: async () => ({ loggedIn: false }) },
    };
    expect(await guard({ name: 'chat' })).toBe('login');
  });
});
