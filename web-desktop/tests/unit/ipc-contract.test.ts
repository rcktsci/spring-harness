import { describe, expect, it } from 'vitest';
import { DEFAULT_CONFIG, IPC } from '../../src/shared/ipc-contract';

describe('ipc-contract', () => {
  it('exposes a stable set of channel names', () => {
    expect(IPC.AUTH_LOGIN_STATE).toBe('auth:login-state');
    expect(IPC.CONFIG_GET).toBe('config:get');
    expect(IPC.CONFIG_SET).toBe('config:set');
    expect(IPC.SESSION_SEND).toBe('session:send');
    expect(IPC.SESSION_LIST).toBe('session:list');
    expect(IPC.SESSION_CREATE).toBe('session:create');
    expect(IPC.SESSION_TREE).toBe('session:tree');
    expect(IPC.AGENTS_LIST).toBe('agents:list');
    expect(IPC.RELAY_REGISTER).toBe('relay:register');
    expect(IPC.SSE_SUBSCRIBE).toBe('sse:subscribe');
    expect(IPC.TASK_SUBSCRIBE).toBe('task:subscribe');
    expect(IPC.TASK_HISTORY).toBe('task:history');
    expect(IPC.TASK_COMMENT_ADD).toBe('task:comments:add');
    expect(IPC.ARTIFACT_DOWNLOAD).toBe('artifact:download');
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
    expect(DEFAULT_CONFIG.sessionListLimit).toBe(50);
    expect(DEFAULT_CONFIG.sessionSearchDebounceMs).toBe(300);
    expect(DEFAULT_CONFIG.chatPageLimit).toBe(100);
    expect(DEFAULT_CONFIG.chatHistoryMaxPages).toBe(500);
    expect(DEFAULT_CONFIG.sseRetryDefaultMs).toBe(5_000);
    expect(DEFAULT_CONFIG.treeRefreshIntervalMs).toBe(10_000);
    expect(DEFAULT_CONFIG.taskHistoryLimit).toBe(50);
    expect(DEFAULT_CONFIG.taskHistoryMaxPages).toBe(200);
    expect(DEFAULT_CONFIG.taskSseRetryDefaultMs).toBe(5_000);
    expect(DEFAULT_CONFIG.artifactExtensionHint).toContain('.md');
    expect(DEFAULT_CONFIG.artifactCacheMaxAgeMs).toBe(7 * 24 * 60 * 60 * 1_000);
  });
});
