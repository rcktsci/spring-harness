import { Tray, Menu, app, nativeImage } from 'electron';
import { join } from 'node:path';
import { existsSync } from 'node:fs';
import { log } from './logger.js';

export function createTray(opts: {
  showTray: boolean;
  onShow: () => void;
  onQuit: () => void;
}): Tray | null {
  if (!opts.showTray) {
    log.info('tray disabled by config');
    return null;
  }

  const iconPath = join(app.getAppPath(), 'resources', 'tray.png');
  if (!existsSync(iconPath)) {
    // graceful degradation: no invisible empty tray
    log.warn(`tray icon missing at ${iconPath}; tray disabled`);
    return null;
  }

  const image = nativeImage.createFromPath(iconPath);
  const tray = new Tray(image);
  tray.setToolTip('Spring Harness Web Desktop');
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: 'Show', click: opts.onShow },
      { type: 'separator' },
      { label: 'Quit', click: opts.onQuit },
    ]),
  );
  tray.on('click', () => opts.onShow());
  return tray;
}
