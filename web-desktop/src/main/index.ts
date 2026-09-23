import { app, BrowserWindow, ipcMain, shell } from 'electron';
import { join } from 'node:path';
import { log, initLogger } from './logger.js';
import { loadConfig, saveConfig } from './config.js';
import { createMainWindow } from './window.js';
import { buildAppMenu } from './menu.js';
import { createTray } from './tray.js';
import { IPC } from '../shared/ipc-contract.js';

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

function registerIpcStubs(): void {
  const implemented = new Set<string>([
    IPC.CONFIG_GET,
    IPC.CONFIG_SET,
    IPC.CONFIG_OPEN_LOGS,
    IPC.AUTH_LOGIN_STATE,
    IPC.APP_QUIT,
  ]);

  for (const channel of Object.values(IPC)) {
    if (implemented.has(channel)) {
      continue;
    }
    ipcMain.handle(channel, async () => {
      throw new Error(`not implemented in batch A: ${channel}`);
    });
  }

  ipcMain.handle(IPC.CONFIG_GET, async () => config);

  ipcMain.handle(IPC.CONFIG_SET, async (_evt, patch: Record<string, unknown>) => {
    config = { ...config, ...(patch as Partial<typeof config>) };
    await saveConfig(config);
    return config;
  });

  ipcMain.handle(IPC.CONFIG_OPEN_LOGS, async () => {
    await shell.openPath(join(app.getPath('userData'), 'logs'));
  });

  ipcMain.handle(IPC.AUTH_LOGIN_STATE, async () => ({ loggedIn: false }));

  ipcMain.handle(IPC.APP_QUIT, async () => {
    app.quit();
  });
}
