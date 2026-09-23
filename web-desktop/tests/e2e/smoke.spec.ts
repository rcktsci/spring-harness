import { test, expect } from '@playwright/test';

/**
 * Playwright-electron scenarios are deferred to bundle F (tasks 6.1 / 6.2):
 * real e2e needs the stub server that faithfully implements the relay
 * protocol (4401/4403/4409, heartbeat, cancel race, takeover) plus the
 * docker-compose smoke against live Keycloak.
 *
 * This file is intentionally skipped so `pnpm e2e` stays green without
 * pretending to exercise Electron. Bundle F replaces it.
 */
test.skip('electron smoke (bundle F)', async () => {
  expect(true).toBe(true);
});
