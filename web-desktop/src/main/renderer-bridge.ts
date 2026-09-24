/**
 * Single choke point for main → renderer pushes.
 *
 * Any `webContents.send` can land after the window (or its webContents) has
 * been destroyed — quit, window close, reload. Electron then throws
 * "Object has been destroyed" from inside an event emitter, which surfaces as
 * an uncaught main-process exception. Every push in `index.ts` goes through
 * here so a dead window is a no-op instead of a crash.
 */
export function sendToRenderer(
  getWindow: () => Electron.BrowserWindow | null,
  channel: string,
  ...args: unknown[]
): boolean {
  const win = getWindow();
  if (!win || win.isDestroyed() || win.webContents.isDestroyed()) {
    return false;
  }
  try {
    win.webContents.send(channel, ...args);
    return true;
  } catch {
    // Destroyed between the check and the send — same no-op contract.
    return false;
  }
}
