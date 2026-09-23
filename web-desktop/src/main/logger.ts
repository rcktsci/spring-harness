import { app } from 'electron';
import log from 'electron-log/main.js';
import { join } from 'node:path';
import type { ServerConfig } from '../shared/ipc-contract.js';

const LEVELS: Record<ServerConfig['logLevel'], number> = {
  error: 0,
  warn: 1,
  info: 2,
  verbose: 3,
  debug: 4,
  silly: 5,
};

export function initLogger(level: ServerConfig['logLevel'], maxSize: number): void {
  const fileName = 'main.log';
  const logDir = join(app.getPath('userData'), 'logs');
  log.transports.file.resolvePathFn = () => join(logDir, fileName);
  log.transports.file.maxSize = maxSize;
  log.transports.file.format = '[{y}-{m}-{d} {h}:{i}:{s}.{ms}] [{level}] {text}';
  log.transports.file.level = level;
  log.transports.console.level = level;
  const numericLevel = LEVELS[level];
  log.info(`logger initialised (level=${level}, numeric=${numericLevel}, maxSize=${maxSize})`);
}

export { log };
