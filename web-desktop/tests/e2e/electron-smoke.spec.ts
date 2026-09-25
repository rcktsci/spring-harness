/**
 * Playwright-electron e2e for Web Desktop — the real client tool-cycle
 * against an in-test stub server. Acts as the guard for the three
 * relay holes found on the live stand:
 *
 *   1. Auto-connect: opening a FREE session connects + registers the
 *      relay without any manual button.
 *   2. Registration consent dialog (first registration on a session)
 *      must be answerable — registration only completes after "Разрешить".
 *   3. Tool-command confirmation dialog (D-93, confirmCommands=always):
 *      deny → no execution ("command rejected by user"), approve → runs.
 *
 * Plus a real save-as flow: the native save dialog is stubbed in main
 * (app.evaluate), the file is written to disk and asserted.
 *
 * Stub server: boots REST + WS §5 + SSE §3.1/§3.2, sends the WS
 * `tool.call` to the registered relay connection (retries until the
 * desktop registers).
 *
 * Requires a display server (native Windows / macOS / Xvfb on Linux).
 * Run with: `pnpm e2e:electron` after a `pnpm build` (built automatically
 * unless HARNESS_E2E_BUILT=0).
 */
import { test, expect, _electron as electron, type ElectronApplication, type Page } from '@playwright/test';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { startStub, type StubHandle } from './stub-server';

const E2E_BUILD = process.env['HARNESS_E2E_BUILT'] !== '0';
const STUB_TOKEN = 'stub-test-token';
const TOOL_OUTPUT_MARKER = 'hello-from-client';
const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');

let stub: StubHandle | null = null;

function e2eEnv(autoConfirmCommands: boolean, baseUrl: string): Record<string, string> {
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined) env[key] = value;
  }
  env['HARNESS_E2E_ENABLED'] = '1';
  env['HARNESS_E2E_TOKEN'] = STUB_TOKEN;
  env['HARNESS_E2E_SERVER_BASE_URL'] = baseUrl;
  env['HARNESS_E2E_KEYCLOAK_ISSUER'] = `${baseUrl}/realms/harness`;
  env['HARNESS_E2E_KEYCLOAK_CLIENT_ID'] = 'spring-harness-web-desktop';
  env['ELECTRON_DISABLE_SECURITY_WARNINGS'] = '1';
  if (autoConfirmCommands) {
    env['HARNESS_E2E_AUTO_CONFIRM_COMMANDS'] = '1';
  } else {
    delete env['HARNESS_E2E_AUTO_CONFIRM_COMMANDS'];
  }
  return env;
}

async function launchApp(autoConfirmCommands: boolean): Promise<{ app: ElectronApplication; userData: string }> {
  if (!stub) throw new Error('stub not started');
  // Fresh userData per launch: no stale config/tokens between scenarios.
  const userData = mkdtempSync(join(tmpdir(), 'harness-e2e-user-'));
  const app = await electron.launch({
    args: ['.', `--user-data-dir=${userData}`],
    cwd: ROOT,
    env: e2eEnv(autoConfirmCommands, stub.baseUrl),
    timeout: 60_000,
  });
  return { app, userData };
}

async function openRootSession(page: Page): Promise<void> {
  // Real DOM: the session list renders li.session-item (no data-session-id).
  const item = page.locator('li.session-item', { hasText: 'e2e-root' }).first();
  await expect(item).toBeVisible({ timeout: 15_000 });
  await item.click();
  await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 10_000 });
}

/** Approves the first-registration consent the auto-connect pops up. */
async function approveRegistrationConsent(page: Page): Promise<void> {
  const dialog = page.locator('[data-testid="consent-dialog"]');
  await expect(dialog).toBeVisible({ timeout: 15_000 });
  await dialog.locator('[data-testid="consent-approve"]').click();
}

async function sendAndAwaitUserEcho(page: Page, text: string): Promise<void> {
  const input = page.locator('[data-testid="composer-input"]');
  await input.fill(text);
  await page.locator('[data-testid="composer-send"]').click();
  await expect(page.locator('[data-kind="USER"]').last()).toHaveText(new RegExp(text), { timeout: 10_000 });
}

/** Expands the newest tool block and returns its RESULT section text. */
async function lastToolResultText(page: Page): Promise<string> {
  const block = page.locator('[data-kind="TOOL"]').last();
  await expect(block).toBeVisible({ timeout: 20_000 });
  await expect(block).not.toHaveAttribute('data-pending', '1', { timeout: 20_000 });
  await block.locator('.tool-toggle').click();
  const result = block.locator('.tool-body .result');
  await expect(result).toBeVisible();
  return (await result.innerText()) ?? '';
}

test.beforeAll(async () => {
  stub = await startStub();
  if (E2E_BUILD) {
    const result = spawnSync('pnpm', ['run', 'build'], {
      cwd: ROOT,
      stdio: 'inherit',
      shell: true,
      env: { ...process.env, HARNESS_E2E_ENABLED: '1' },
    });
    if (result.status !== 0) {
      throw new Error('electron-vite build failed; see output above');
    }
  }
});

test.afterAll(async () => {
  await stub?.close();
});

test('auto-connect + consent + bash tool cycle + artifact save-as (confirmCommands=never)', async () => {
  if (!stub) throw new Error('stub not started');
  const { app } = await launchApp(true);
  try {
    const page = await app.firstWindow({ timeout: 30_000 });
    await page.waitForLoadState('domcontentloaded');

    // 1. Opening the FREE session triggers the relay auto-connect...
    await openRootSession(page);

    // 2. ...which pops the first-registration consent dialog.
    const consent = page.locator('[data-testid="consent-dialog"]');
    await expect(consent).toBeVisible({ timeout: 15_000 });
    await expect(consent).toContainText('harness-workspaces');
    await approveRegistrationConsent(page);

    // 3. Registered → status bar shows the tool count (6 standard tools).
    await expect(page.locator('[data-testid="relay-status"]')).toContainText(
      'подключён (6 инструментов)',
      { timeout: 15_000 },
    );

    // 4. Full tool cycle: user message → orchestrator tool.call via WS →
    //    local bash → tool.result → final assistant message via SSE.
    await sendAndAwaitUserEcho(page, 'ping');
    const output = await lastToolResultText(page);
    expect(output).toContain(TOOL_OUTPUT_MARKER);
    await expect(page.locator('[data-kind="ASSISTANT"]').last()).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.rt-IDLE').first()).toBeVisible({ timeout: 10_000 });

    // 5. Real save-as: stub the native dialog in main, drive the UI,
    //    assert the file landed on disk with the workspace content.
    const saveTarget = join(tmpdir(), `harness-e2e-save-${Date.now()}.md`);
    await app.evaluate(
      ({ dialog }, target) => {
        const stubDialog = dialog as unknown as {
          showSaveDialog: () => Promise<{ canceled: boolean; filePath: string }>;
        };
        stubDialog.showSaveDialog = async () => ({ canceled: false, filePath: target });
      },
      saveTarget,
    );
    await page.locator('a[href="#/artifacts"]').click();
    await page.locator('[data-testid="artifact-session"]').fill(stub.sessionId);
    await page.locator('[data-testid="artifact-path"]').fill('hello.md');
    await page.locator('[data-testid="artifact-save"]').click();
    await expect(page.locator('[data-testid="artifact-result"]')).toContainText('Сохранён', { timeout: 15_000 });
    expect(existsSync(saveTarget)).toBe(true);
    expect(readFileSync(saveTarget, 'utf8')).toContain('# hi from stub');
  } finally {
    await app.close();
  }
});

test('tool confirm dialog: deny skips execution, approve runs (confirmCommands=always)', async () => {
  if (!stub) throw new Error('stub not started');
  const { app } = await launchApp(false);
  try {
    const page = await app.firstWindow({ timeout: 30_000 });
    await page.waitForLoadState('domcontentloaded');

    await openRootSession(page);

    // Decline first: the refusal must surface in the relay line (never as a
    // plain «подключён») and the toggle must stay in the «Подключить» state.
    const consent = page.locator('[data-testid="consent-dialog"]');
    await expect(consent).toBeVisible({ timeout: 15_000 });
    await consent.locator('[data-testid="consent-deny"]').click();
    await expect(page.locator('[data-testid="relay-status"]')).toContainText('declined', { timeout: 15_000 });
    await expect(page.locator('[data-testid="relay-toggle"]')).toHaveText('Подключить');

    await page.locator('[data-testid="relay-toggle"]').click();
    await expect(consent).toBeVisible({ timeout: 15_000 });
    await consent.locator('[data-testid="consent-approve"]').click();
    await expect(page.locator('[data-testid="relay-status"]')).toContainText(
      'подключён (6 инструментов)',
      { timeout: 15_000 },
    );

    // Round 1: deny the command — nothing executes, the agent still
    // gets a terminal tool.result ("command rejected by user").
    await sendAndAwaitUserEcho(page, 'deny me');
    const confirm = page.locator('[data-testid="tool-confirm-dialog"]');
    await expect(confirm).toBeVisible({ timeout: 15_000 });
    await expect(confirm).toContainText('bash');
    await confirm.locator('[data-testid="tool-confirm-deny"]').click();
    const denied = await lastToolResultText(page);
    expect(denied).toContain('command rejected by user');
    expect(denied).not.toContain(TOOL_OUTPUT_MARKER);
    await expect(page.locator('[data-kind="ASSISTANT"]').last()).toBeVisible({ timeout: 15_000 });

    // Round 2: approve — bash runs and the output reaches the feed.
    await sendAndAwaitUserEcho(page, 'approve me');
    const confirm2 = page.locator('[data-testid="tool-confirm-dialog"]');
    await expect(confirm2).toBeVisible({ timeout: 15_000 });
    await confirm2.locator('[data-testid="tool-confirm-approve"]').click();
    const approved = await lastToolResultText(page);
    expect(approved).toContain(TOOL_OUTPUT_MARKER);
    await expect(page.locator('[data-kind="ASSISTANT"]').last()).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.rt-IDLE').first()).toBeVisible({ timeout: 10_000 });
  } finally {
    await app.close();
  }
});
