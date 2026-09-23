import { app, BrowserWindow, ipcMain, nativeTheme, shell } from 'electron';
import { join } from 'node:path';
import { log, initLogger } from './logger.js';
import { loadConfig, saveConfig } from './config.js';
import { createMainWindow } from './window.js';
import { buildAppMenu } from './menu.js';
import { createTray } from './tray.js';
import { startLogin, logout, readLoginState, refreshIfNeeded } from './auth.js';
import { RelayClient } from './relay-client.js';
import { IPC, type ServerConfig } from '../shared/ipc-contract.js';

const DEV_URL = process.env['VITE_DEV_SERVER_URL'];
const isDev = Boolean(DEV_URL);
let mainWindow: BrowserWindow | null = null;
let config = await loadConfig();
let relay: RelayClient | null = null;
initLogger(config.logLevel, config.logMaxSizeBytes);
log.info('app starting', { isDev, version: app.getVersion() });

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  log.warn('another instance is already running; quitting');
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) {
        mainWindow.restore();
      }
      mainWindow.focus();
    }
  });
  void bootstrap();
}

async function bootstrap(): Promise<void> {
  await app.whenReady();

  config = await loadConfig();
  applyTheme(config.theme);
  mainWindow = await createMainWindow(config);

  const menu = buildAppMenu({
    isDev,
    onOpenLogs: () => {
      void shell.openPath(join(app.getPath('userData'), 'logs'));
    },
  });
  app.applicationMenu = menu;

  createTray({
    showTray: config.showTray,
    onShow: () => {
      if (!mainWindow) {
        return;
      }
      if (mainWindow.isMinimized()) {
        mainWindow.restore();
      }
      mainWindow.show();
      mainWindow.focus();
    },
    onQuit: () => app.quit(),
  });

  registerIpcStubs();
  relay = createRelay();

  if (isDev && DEV_URL) {
    await mainWindow.loadURL(DEV_URL);
    mainWindow.webContents.openDevTools({ mode: 'detach' });
  } else {
    await mainWindow.loadFile(join(__dirname, '../renderer/index.html'));
  }

  // Spec: startup with a saved active session → auto connect + register.
  if (config.relayActiveSessionId) {
    void relay.register(config.relayActiveSessionId, 'FREE').then((outcome) => {
      if (!outcome.ok) {
        log.warn('startup auto-register failed', {
          sessionId: config.relayActiveSessionId,
          code: outcome.code,
          message: outcome.message,
        });
        if (outcome.code === 'session-not-found' || outcome.code === 'wrong-session-kind') {
          // Stale id — drop it so the next start does not retry a dead session.
          void saveConfig({ ...config, relayActiveSessionId: undefined }).then(() => {
            config = { ...config, relayActiveSessionId: undefined };
          });
        }
      }
    });
  }

  app.on('activate', async () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      config = await loadConfig();
      mainWindow = await createMainWindow(config);
    }
  });
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('before-quit', () => {
  relay?.shutdown();
  log.info('app quitting');
});

function applyTheme(theme: 'light' | 'dark' | 'system'): void {
  nativeTheme.themeSource = theme === 'system' ? 'system' : theme;
  log.info(`theme set to ${theme}`);
}

/**
 * Builds a RelayClient wired to the renderer. Call again after a config
 * change that invalidates the endpoint (baseUrl) so the client picks up
 * the new URL — `cfg` is constructor-captured (readonly).
 */
function createRelay(): RelayClient {
  const client = new RelayClient(config, async () =>
    refreshIfNeeded(config, config.tokenClockSkewSeconds),
  );
  client.on('status', (status) => {
    mainWindow?.webContents.send('relay:status', status);
  });
  client.on('toolCall', (call, basePath) => {
    mainWindow?.webContents.send('tool:call', call, basePath);
  });
  client.on('registrationConsent', (sessionId, basePath, tools) => {
    mainWindow?.webContents.send('relay:registration-consent', { sessionId, basePath, tools });
  });
  client.on('toolResult', (callId, output, exitCode) => {
    mainWindow?.webContents.send('tool:result', callId, output, exitCode);
  });
  return client;
}

/**
 * Called when the user changes server settings in the Settings view.
 * Existing network clients must drop their server affinity; the next
 * request re-authenticates against the new issuer (spec "смена сервера").
 */
async function onServerConfigChanged(next: ServerConfig): Promise<void> {
  const baseUrlChanged = next.serverBaseUrl !== config.serverBaseUrl;
  const issuerChanged = next.keycloakIssuer !== config.keycloakIssuer;
  config = next;
  applyTheme(next.theme);
  if (baseUrlChanged || issuerChanged) {
    log.info('server/keycloak endpoint changed — reconnecting relay');
    // Tokens are scoped to the old issuer; a stale JWT would only produce 401s.
    if (issuerChanged) {
      const { clearTokens } = await import('./token-store.js');
      clearTokens();
    }
    if (baseUrlChanged) {
      // RelayClient captures cfg in the constructor — rebuild for the new URL.
      relay?.shutdown();
      relay = createRelay();
      if (config.relayActiveSessionId) {
        void relay.register(config.relayActiveSessionId, 'FREE').catch(() => {
          /* status events already surface failures */
        });
      }
    }
    mainWindow?.webContents.send('config:changed', { baseUrl: next.serverBaseUrl });
  }
}

function registerIpcStubs(): void {
  const implemented = new Set<string>([
    IPC.CONFIG_GET,
    IPC.CONFIG_SET,
    IPC.CONFIG_OPEN_LOGS,
    IPC.AUTH_LOGIN_STATE,
    IPC.AUTH_LOGIN_START,
    IPC.AUTH_LOGIN_LOGOUT,
    IPC.AUTH_REFRESH,
    IPC.RELAY_CONNECT,
    IPC.RELAY_REGISTER,
    IPC.RELAY_DISCONNECT,
    IPC.RELAY_STATUS,
    IPC.RELAY_SET_SESSION,
    IPC.RELAY_CONFIRM_REGISTRATION,
    IPC.TOOL_RESPOND_CONFIRM,
    IPC.TOOL_CANCEL,
    IPC.APP_QUIT,
  ]);

  for (const channel of Object.values(IPC)) {
    if (implemented.has(channel)) {
      continue;
    }
    ipcMain.handle(channel, async () => {
      throw new Error(`not implemented in batch B: ${channel}`);
    });
  }

  ipcMain.handle(IPC.CONFIG_GET, async () => config);

  ipcMain.handle(IPC.CONFIG_SET, async (_evt, patch: Record<string, unknown>) => {
    const next = { ...config, ...(patch as Partial<ServerConfig>) };
    await saveConfig(next);
    await onServerConfigChanged(next);
    return config;
  });

  ipcMain.handle(IPC.CONFIG_OPEN_LOGS, async () => {
    await shell.openPath(join(app.getPath('userData'), 'logs'));
  });

  ipcMain.handle(IPC.AUTH_LOGIN_STATE, async () => {
    try {
      return readLoginState();
    } catch (err) {
      // safeStorage unavailable — surface a readable reason, never a crash
      return {
        loggedIn: false,
        error: err instanceof Error ? err.message : 'auth state unavailable',
      };
    }
  });

  ipcMain.handle(IPC.AUTH_LOGIN_START, async () => {
    const result = await startLogin(config);
    if (!result.ok) {
      throw new Error(result.message ?? `login ${result.reason}`);
    }
    return readLoginState();
  });

  ipcMain.handle(IPC.AUTH_LOGIN_LOGOUT, async () => {
    await logout(config);
  });

  ipcMain.handle(IPC.AUTH_REFRESH, async () => {
    const token = await refreshIfNeeded(config, config.tokenClockSkewSeconds);
    if (!token) {
      throw new Error('session expired — sign in again');
    }
    return readLoginState();
  });

  ipcMain.handle(IPC.RELAY_CONNECT, async () => {
    await relay?.connect();
    return relay?.currentStatus ?? null;
  });

  ipcMain.handle(IPC.RELAY_REGISTER, async (_evt, payload: { sessionId: string; kind: string; basePath?: string }) => {
    if (!relay) return { ok: false, message: 'relay not initialised' };
    const outcome = await relay.register(
      payload.sessionId,
      payload.kind === 'STATE' ? 'STATE' : 'FREE',
      payload.basePath,
    );
    if (outcome.ok && payload.kind !== 'STATE') {
      // Remember for startup auto-register; a session switch overwrites.
      if (config.relayActiveSessionId !== payload.sessionId) {
        config = { ...config, relayActiveSessionId: payload.sessionId };
        await saveConfig(config);
      }
    } else if (!outcome.ok && (outcome.code === 'session-not-found' || outcome.code === 'wrong-session-kind')) {
      if (config.relayActiveSessionId) {
        config = { ...config, relayActiveSessionId: undefined };
        await saveConfig(config);
      }
    }
    return outcome;
  });

  ipcMain.handle(IPC.RELAY_SET_SESSION, async (_evt, payload: { sessionId: string; kind: string }) => {
    // Lifecycle (spec): switching re-registers on the same socket; a
    // STATE session simply does not register.
    if (payload.kind !== 'FREE') {
      return { ok: false, code: 'wrong-session-kind' };
    }
    return relay?.register(payload.sessionId, 'FREE') ?? { ok: false };
  });

  ipcMain.handle(IPC.RELAY_DISCONNECT, async () => {
    relay?.disconnect();
    if (config.relayActiveSessionId) {
      // Explicit user disconnect — do not auto-reconnect on next start.
      config = { ...config, relayActiveSessionId: undefined };
      await saveConfig(config);
    }
  });

  ipcMain.handle(IPC.RELAY_STATUS, async () => relay?.currentStatus ?? null);

  ipcMain.handle(IPC.RELAY_CONFIRM_REGISTRATION, async (_evt, payload: { sessionId: string; approved: boolean }) => {
    relay?.resolveRegistrationConsent(payload.sessionId, payload.approved);
    return { ok: payload.approved };
  });

  ipcMain.handle(IPC.TOOL_RESPOND_CONFIRM, async (_evt, payload: { callId: string; approved: boolean }) => {
    relay?.resolveConfirmation(payload.callId, payload.approved);
  });

  ipcMain.handle(IPC.TOOL_CANCEL, async (_evt, callId: string) => {
    relay?.cancelToolCall(callId);
  });

  ipcMain.handle(IPC.APP_QUIT, async () => {
    relay?.shutdown();
    app.quit();
  });
}
