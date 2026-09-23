import { app, BrowserWindow, ipcMain, nativeTheme, shell } from 'electron';
import { join } from 'node:path';
import { log, initLogger } from './logger.js';
import { loadConfig, saveConfig } from './config.js';
import { createMainWindow } from './window.js';
import { buildAppMenu } from './menu.js';
import { createTray } from './tray.js';
import { startLogin, logout, readLoginState, refreshIfNeeded } from './auth.js';
import { IPC, type ServerConfig } from '../shared/ipc-contract.js';

const DEV_URL = process.env['VITE_DEV_SERVER_URL'];
const isDev = Boolean(DEV_URL);
let mainWindow: BrowserWindow | null = null;
let config = await loadConfig();
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

  if (isDev && DEV_URL) {
    await mainWindow.loadURL(DEV_URL);
    mainWindow.webContents.openDevTools({ mode: 'detach' });
  } else {
    await mainWindow.loadFile(join(__dirname, '../renderer/index.html'));
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
  log.info('app quitting');
});

function applyTheme(theme: 'light' | 'dark' | 'system'): void {
  nativeTheme.themeSource = theme === 'system' ? 'system' : theme;
  log.info(`theme set to ${theme}`);
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
    log.info('server/keycloak endpoint changed — clients will reconnect');
    // Tokens are scoped to the old issuer; a stale JWT would only produce 401s.
    if (issuerChanged) {
      const { clearTokens } = await import('./token-store.js');
      clearTokens();
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

  ipcMain.handle(IPC.APP_QUIT, async () => {
    app.quit();
  });
}
