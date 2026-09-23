import { app } from 'electron';
import { promises as fs } from 'node:fs';
import { join, dirname } from 'node:path';
import { DEFAULT_CONFIG, type ServerConfig } from '../shared/ipc-contract.js';

function configPath(): string {
  return join(app.getPath('userData'), 'config.json');
}

async function readJson(path: string): Promise<Record<string, unknown> | null> {
  try {
    const raw = await fs.readFile(path, 'utf8');
    return JSON.parse(raw) as Record<string, unknown>;
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') {
      return null;
    }
    throw err;
  }
}

export async function loadConfig(): Promise<ServerConfig> {
  const raw = await readJson(configPath());
  if (!raw) {
    return { ...DEFAULT_CONFIG };
  }
  return { ...DEFAULT_CONFIG, ...(raw as Partial<ServerConfig>) };
}

export async function saveConfig(cfg: ServerConfig): Promise<void> {
  const path = configPath();
  await fs.mkdir(dirname(path), { recursive: true });
  const tmp = `${path}.tmp`;
  await fs.writeFile(tmp, JSON.stringify(cfg, null, 2), 'utf8');
  await fs.rename(tmp, path);
}
