import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  forbidOnly: !!process.env['CI'],
  retries: 0,
  workers: 1,
  reporter: process.env['CI'] ? [['list'], ['github']] : 'list',
  use: {
    trace: 'off',
    video: 'off',
    screenshot: 'off',
  },
  projects: [
    {
      name: 'stub-server',
      // Pure stub-server specs (no Electron). Fast, headless, CI-friendly.
      testMatch: /stub-server\.spec\.ts$/,
    },
    {
      name: 'electron-smoke',
      // Full Electron-driver e2e (requires a display server / Xvfb on Linux).
      // Run with `pnpm e2e --project=electron-smoke` after `pnpm build`.
      testMatch: /electron-smoke\.spec\.ts$/,
    },
  ],
});
