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

/**
 * Test-mode env-var override (Playwright-electron e2e).
 *  - `HARNESS_E2E_SERVER_BASE_URL` → serverBaseUrl
 *  - `HARNESS_E2E_KEYCLOAK_ISSUER` → keycloakIssuer
 *  - `HARNESS_E2E_KEYCLOAK_CLIENT_ID` → keycloakClientId
 *  - `HARNESS_E2E_AUTO_CONFIRM_COMMANDS` ('1') → confirmCommands='never'
 *
 * The stub server test fixture is started by `tests/e2e/stub-server.ts`
 * and prints the ports on stdout (consumed by the Playwright spec).
 */
function readE2eEnvOverrides(): Partial<ServerConfig> | null {
  if (!process.env['HARNESS_E2E_ENABLED']) return null;
  const out: Partial<ServerConfig> = {};
  const url = process.env['HARNESS_E2E_SERVER_BASE_URL'];
  const issuer = process.env['HARNESS_E2E_KEYCLOAK_ISSUER'];
  const client = process.env['HARNESS_E2E_KEYCLOAK_CLIENT_ID'];
  if (url) out.serverBaseUrl = url;
  if (issuer) out.keycloakIssuer = issuer;
  if (client) out.keycloakClientId = client;
  if (process.env['HARNESS_E2E_AUTO_CONFIRM_COMMANDS'] === '1') {
    out.confirmCommands = 'never';
  }
  return Object.keys(out).length > 0 ? out : null;
}

export async function loadConfig(): Promise<ServerConfig> {
  const raw = await readJson(configPath());
  const e2e = readE2eEnvOverrides();
  if (!raw) {
    return { ...DEFAULT_CONFIG, ...(e2e ?? {}) };
  }
  return { ...DEFAULT_CONFIG, ...(raw as Partial<ServerConfig>), ...(e2e ?? {}) };
}

export async function saveConfig(cfg: ServerConfig): Promise<void> {
  const path = configPath();
  await fs.mkdir(dirname(path), { recursive: true });
  const tmp = `${path}.tmp`;
  await fs.writeFile(tmp, JSON.stringify(cfg, null, 2), 'utf8');
  await fs.rename(tmp, path);
}

