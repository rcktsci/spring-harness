import { describe, expect, it } from 'vitest';
import { DEFAULT_CONFIG, IPC } from '../../src/shared/ipc-contract';

describe('ipc-contract', () => {
  it('exposes a stable set of channel names', () => {
    expect(IPC.AUTH_LOGIN_STATE).toBe('auth:login-state');
    expect(IPC.CONFIG_GET).toBe('config:get');
    expect(IPC.CONFIG_SET).toBe('config:set');
    expect(IPC.SESSION_SEND).toBe('session:send');
    expect(IPC.RELAY_REGISTER).toBe('relay:register');
    expect(IPC.SSE_SUBSCRIBE).toBe('sse:subscribe');
    expect(IPC.APP_QUIT).toBe('app:quit');
  });

  it('seeds sensible defaults', () => {
    expect(DEFAULT_CONFIG.windowWidth).toBe(1200);
    expect(DEFAULT_CONFIG.windowHeight).toBe(800);
    expect(DEFAULT_CONFIG.windowMinWidth).toBe(800);
    expect(DEFAULT_CONFIG.windowMinHeight).toBe(600);
    expect(DEFAULT_CONFIG.windowStateDebounceMs).toBe(500);
    expect(DEFAULT_CONFIG.showTray).toBe(true);
    expect(DEFAULT_CONFIG.confirmCommands).toBe('always');
    expect(DEFAULT_CONFIG.logLevel).toBe('info');
    expect(DEFAULT_CONFIG.logMaxSizeBytes).toBe(5 * 1024 * 1024);
  });
});
