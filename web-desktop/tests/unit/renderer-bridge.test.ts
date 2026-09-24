import { describe, expect, it, vi } from 'vitest';
import { sendToRenderer } from '../../src/main/renderer-bridge';

type FakeWindow = {
  isDestroyed: () => boolean;
  webContents: { isDestroyed: () => boolean; send: (channel: string, ...args: unknown[]) => void };
};

function fakeWindow(overrides: Partial<FakeWindow> = {}): FakeWindow {
  return {
    isDestroyed: () => false,
    webContents: {
      isDestroyed: () => false,
      send: vi.fn(),
    },
    ...overrides,
  };
}

describe('sendToRenderer', () => {
  it('sends to a live window', () => {
    const win = fakeWindow();
    const sent = sendToRenderer(() => win as never, 'relay:status', { phase: 'connected' });
    expect(sent).toBe(true);
    expect(win.webContents.send).toHaveBeenCalledWith('relay:status', { phase: 'connected' });
  });

  it('skips when there is no window', () => {
    expect(sendToRenderer(() => null, 'relay:status', {})).toBe(false);
  });

  it('skips when the window is destroyed', () => {
    const win = fakeWindow({ isDestroyed: () => true });
    expect(sendToRenderer(() => win as never, 'relay:status', {})).toBe(false);
    expect(win.webContents.send).not.toHaveBeenCalled();
  });

  it('skips when webContents is destroyed', () => {
    const win = fakeWindow();
    win.webContents.isDestroyed = () => true;
    expect(sendToRenderer(() => win as never, 'relay:status', {})).toBe(false);
    expect(win.webContents.send).not.toHaveBeenCalled();
  });

  it('survives the destroy race between check and send', () => {
    const win = fakeWindow();
    win.webContents.send = vi.fn(() => {
      throw new TypeError('Object has been destroyed');
    });
    expect(sendToRenderer(() => win as never, 'relay:status', {})).toBe(false);
  });
});
