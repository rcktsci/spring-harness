import { describe, expect, it } from 'vitest';
import { DEFAULT_CONFIG, IPC } from '../../src/shared/ipc-contract';

describe('config binding', () => {
  it('exposes every ServerConfig field with a non-undefined default', () => {
    for (const key of Object.keys(DEFAULT_CONFIG) as (keyof typeof DEFAULT_CONFIG)[]) {
      expect(DEFAULT_CONFIG[key], `default for ${key}`).not.toBeUndefined();
    }
  });

  it('keeps all numeric parameters out of code paths', () => {
    expect(DEFAULT_CONFIG.windowWidth).toBe(1200);
    expect(DEFAULT_CONFIG.windowHeight).toBe(800);
    expect(DEFAULT_CONFIG.windowMinWidth).toBe(800);
    expect(DEFAULT_CONFIG.windowMinHeight).toBe(600);
    expect(DEFAULT_CONFIG.windowStateDebounceMs).toBe(500);
    expect(DEFAULT_CONFIG.loginWindowWidth).toBe(900);
    expect(DEFAULT_CONFIG.loginWindowHeight).toBe(750);
    expect(DEFAULT_CONFIG.logMaxSizeBytes).toBe(5 * 1024 * 1024);
    expect(DEFAULT_CONFIG.tokenClockSkewSeconds).toBe(30);
  });

  it('freezes the security-sensitive defaults', () => {
    expect(DEFAULT_CONFIG.confirmCommands).toBe('always');
    expect(DEFAULT_CONFIG.showTray).toBe(true);
  });

  it('uses stable channel names across config/auth domains', () => {
    expect(IPC.CONFIG_GET).toBe('config:get');
    expect(IPC.CONFIG_SET).toBe('config:set');
    expect(IPC.AUTH_LOGIN_START).toBe('auth:login-start');
    expect(IPC.AUTH_LOGIN_LOGOUT).toBe('auth:login-logout');
    expect(IPC.AUTH_REFRESH).toBe('auth:refresh');
  });
});
