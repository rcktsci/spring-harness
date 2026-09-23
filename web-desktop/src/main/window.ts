import { BrowserWindow, app, screen } from 'electron';
import { promises as fs } from 'node:fs';
import { join } from 'node:path';
import { log } from './logger.js';
import { DEFAULT_CONFIG, type ServerConfig } from '../shared/ipc-contract.js';

const STATE_FILE = 'window-state.json';

type WindowState = {
  width: number;
  height: number;
  x?: number;
  y?: number;
};

function statePath(): string {
  return join(app.getPath('userData'), STATE_FILE);
}

async function loadWindowState(): Promise<WindowState | null> {
  try {
    const raw = await fs.readFile(statePath(), 'utf8');
    return JSON.parse(raw) as WindowState;
  } catch {
    return null;
  }
}

async function saveWindowState(win: BrowserWindow): Promise<void> {
  if (win.isDestroyed()) {
    return;
  }
  const bounds = win.getBounds();
  const state: WindowState = {
    width: bounds.width,
    height: bounds.height,
    x: bounds.x,
    y: bounds.y,
  };
  await fs.writeFile(statePath(), JSON.stringify(state, null, 2), 'utf8').catch((err) => {
    log.warn('failed to persist window state', err);
  });
}

function clampToDisplay(state: WindowState, cfg: ServerConfig): WindowState {
  const display = screen.getPrimaryDisplay().workArea;
  const width = Math.min(state.width ?? cfg.windowWidth, display.width);
  const height = Math.min(state.height ?? cfg.windowHeight, display.height);
  const x = typeof state.x === 'number' && state.x >= display.x ? state.x : undefined;
  const y = typeof state.y === 'number' && state.y >= display.y ? state.y : undefined;
  return { width, height, x, y };
}

export async function createMainWindow(cfg: ServerConfig): Promise<BrowserWindow> {
  const persisted = await loadWindowState();
  const size = clampToDisplay(persisted ?? { width: cfg.windowWidth, height: cfg.windowHeight }, cfg);

  const win = new BrowserWindow({
    width: size.width,
    height: size.height,
    x: size.x,
    y: size.y,
    minWidth: cfg.windowMinWidth,
    minHeight: cfg.windowMinHeight,
    show: false,
    title: 'Spring Harness',
    backgroundColor: '#1e1e1e',
    webPreferences: {
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false,
      preload: join(import.meta.dirname ?? __dirname, '../preload/index.js'),
    },
  });

  win.once('ready-to-show', () => {
    win.show();
    log.info(`window shown (${size.width}x${size.height})`);
  });

  let saveTimer: NodeJS.Timeout | null = null;
  const scheduleSave = (): void => {
    if (saveTimer) {
      clearTimeout(saveTimer);
    }
    saveTimer = setTimeout(() => {
      void saveWindowState(win);
    }, cfg.windowStateDebounceMs);
  };

  win.on('resize', scheduleSave);
  win.on('move', scheduleSave);
  win.on('close', () => {
    if (saveTimer) {
      clearTimeout(saveTimer);
    }
    void saveWindowState(win);
  });

  win.webContents.setWindowOpenHandler(({ url }) => {
    log.warn(`blocked window.open for ${url}`);
    return { action: 'deny' };
  });

  return win;
}

export function defaultWindowSize(): { width: number; height: number } {
  return { width: DEFAULT_CONFIG.windowWidth, height: DEFAULT_CONFIG.windowHeight };
}
