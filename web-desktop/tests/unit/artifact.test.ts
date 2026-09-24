import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../src/main/logger', () => ({
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() },
}));

vi.mock('../../src/main/rest-client', () => ({
  fetchWorkspaceFile: vi.fn(),
  WorkspaceFileError: class WorkspaceFileError extends Error {
    readonly status: number;
    readonly detail: string;
    constructor(status: number, detail: string) {
      super(`workspace file → ${status}`);
      this.name = 'WorkspaceFileError';
      this.status = status;
      this.detail = detail;
    }
  },
}));

import { mkdtemp, readFile, rm, stat, utimes, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

let userDataDir = '';

vi.mock('electron', () => {
  const electronMock = {
    app: { getPath: () => userDataDir },
    dialog: {
      showSaveDialog: vi.fn(async () => ({ canceled: true })),
    },
    shell: { openPath: vi.fn(async () => '') },
  };
  return electronMock;
});

import {
  ensureCacheDir,
  isRelativeArtifactPath,
  openArtifact,
  pruneCache,
  safeBasename,
  saveArtifactAs,
  validateClientPath,
  writeToCache,
} from '../../src/main/artifact';
import { fetchWorkspaceFile, WorkspaceFileError } from '../../src/main/rest-client';
import { DEFAULT_CONFIG, type ServerConfig } from '../../src/shared/ipc-contract';

describe('artifact helpers', () => {
  describe('safeBasename', () => {
    it('keeps simple names', () => {
      expect(safeBasename('report.md')).toBe('report.md');
    });
    it('collapses path separators into the basename', () => {
      expect(safeBasename('a/b/c.md')).toBe('c.md');
      expect(safeBasename('a\\b\\c.md')).toBe('c.md');
    });
    it('returns "file" for empty / dot / dotdot basenames', () => {
      expect(safeBasename('.')).toBe('file');
      expect(safeBasename('..')).toBe('file');
      expect(safeBasename('')).toBe('file');
    });
  });

  describe('validateClientPath', () => {
    it('rejects absolute paths', () => {
      const r = validateClientPath('/etc/passwd');
      expect(r.ok).toBe(false);
      if (!r.ok) expect(r.reason).toContain('relative');
    });
    it('rejects parent escapes', () => {
      const r = validateClientPath('../secrets.md');
      expect(r.ok).toBe(false);
    });
    it('accepts and normalizes simple paths', () => {
      const r = validateClientPath('reports/final.md');
      expect(r).toEqual({ ok: true, path: 'reports/final.md' });
    });
  });

  describe('isRelativeArtifactPath', () => {
    it('matches the underlying normalizer', () => {
      expect(isRelativeArtifactPath('reports/x.md')).toBe(true);
      expect(isRelativeArtifactPath('../escape.md')).toBe(false);
    });
  });
});

describe('artifact cache (filesystem)', () => {
  let dir = '';

  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), 'harness-artifact-'));
    userDataDir = dir;
  });

  afterEach(async () => {
    if (dir) await rm(dir, { recursive: true, force: true });
  });

  it('ensureCacheDir creates the cache subdir', async () => {
    const cachePath = await ensureCacheDir();
    expect(cachePath).toBe(join(dir, 'cache', 'artifacts'));
    const st = await stat(cachePath);
    expect(st.isDirectory()).toBe(true);
  });

  it('writeToCache stores file under cache/<hash>-<basename>', async () => {
    const res = new Response(new Uint8Array([1, 2, 3, 4]));
    const out = await writeToCache('sess-1', 'reports/final.md', res);
    expect(out.bytes).toBe(4);
    expect(out.basename).toBe('final.md');
    const data = await readFile(out.localPath);
    expect([...data]).toEqual([1, 2, 3, 4]);
  });

  it('writeToCache is idempotent across identical (sessionId, path)', async () => {
    const a = await writeToCache('sess-1', 'reports/final.md', new Response(new Uint8Array([1])));
    const b = await writeToCache('sess-1', 'reports/final.md', new Response(new Uint8Array([2])));
    expect(a.localPath).toBe(b.localPath);
    const data = await readFile(b.localPath);
    expect([...data]).toEqual([2]);
  });

  it('writeToCache differentiates sessions', async () => {
    const a = await writeToCache('sess-1', 'x.md', new Response(new Uint8Array([1])));
    const b = await writeToCache('sess-2', 'x.md', new Response(new Uint8Array([1])));
    expect(a.localPath).not.toBe(b.localPath);
  });

  it('pruneCache deletes files older than maxAge', async () => {
    const cachePath = await ensureCacheDir();
    const oldFile = join(cachePath, 'old.txt');
    const freshFile = join(cachePath, 'fresh.txt');
    await writeFile(oldFile, 'old');
    await writeFile(freshFile, 'fresh');
    // Backdate old file by 1 hour.
    const past = new Date(Date.now() - 60 * 60 * 1_000);
    await utimes(oldFile, past, past);

    await pruneCache(5 * 60 * 1_000); // 5-minute window

    await expect(stat(oldFile)).rejects.toThrow();
    const freshStat = await stat(freshFile);
    expect(freshStat.isFile()).toBe(true);
  });

  it('pruneCache swallows errors and never throws', async () => {
    userDataDir = join(dir, 'nonexistent', 'deeper');
    await expect(pruneCache(60_000)).resolves.toBeUndefined();
  });
});

describe('artifact user-visible flows (mocks)', () => {
  const cfg: ServerConfig = { ...DEFAULT_CONFIG, serverBaseUrl: 'http://server.test' };
  let dir = '';

  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), 'harness-artifact-open-'));
    userDataDir = dir;
    vi.mocked(fetchWorkspaceFile).mockReset();
    const electronMod = await import('electron');
    const shellOpen = electronMod.shell.openPath as unknown as ReturnType<typeof vi.fn>;
    shellOpen.mockReset();
    shellOpen.mockResolvedValue('');
  });

  afterEach(async () => {
    if (dir) await rm(dir, { recursive: true, force: true });
  });

  it('openArtifact: writes cache copy + invokes shell.openPath', async () => {
    vi.mocked(fetchWorkspaceFile).mockResolvedValueOnce(
      new Response(new Uint8Array([10, 20, 30]), { status: 200 }),
    );
    const out = await openArtifact(cfg, { sessionId: 'sess-1', path: 'reports/final.md' });
    expect(out.bytes).toBe(3);
    const electronMod = await import('electron');
    const shellOpen = electronMod.shell.openPath as unknown as ReturnType<typeof vi.fn>;
    expect(shellOpen).toHaveBeenCalledTimes(1);
    expect(shellOpen.mock.calls[0]?.[0]).toBe(out.localPath);
  });

  it('openArtifact: propagates 404 from server', async () => {
    vi.mocked(fetchWorkspaceFile).mockRejectedValueOnce(new WorkspaceFileError(404, '{}'));
    await expect(openArtifact(cfg, { sessionId: 'sess-1', path: 'a.md' })).rejects.toBeInstanceOf(WorkspaceFileError);
  });

  it('openArtifact: rejects UX-invalid paths without hitting network', async () => {
    await expect(openArtifact(cfg, { sessionId: 'sess-1', path: '/abs/x.md' })).rejects.toThrow(/relative/);
    await expect(openArtifact(cfg, { sessionId: 'sess-1', path: '../escape.md' })).rejects.toThrow(/relative/);
    expect(vi.mocked(fetchWorkspaceFile)).not.toHaveBeenCalled();
  });

  it('saveArtifactAs: throws when user cancels save dialog', async () => {
    const electronMod = await import('electron');
    const showSaveDialog = electronMod.dialog.showSaveDialog as unknown as ReturnType<typeof vi.fn>;
    showSaveDialog.mockReset();
    showSaveDialog.mockResolvedValueOnce({ canceled: true });
    await expect(saveArtifactAs(cfg, { sessionId: 'sess-1', path: 'a.md' })).rejects.toThrow(/cancelled/);
    expect(vi.mocked(fetchWorkspaceFile)).not.toHaveBeenCalled();
  });

  it('saveArtifactAs: streams to chosen path and returns it', async () => {
    const electronMod = await import('electron');
    const showSaveDialog = electronMod.dialog.showSaveDialog as unknown as ReturnType<typeof vi.fn>;
    showSaveDialog.mockReset();
    const dest = join(dir, 'saved.md');
    showSaveDialog.mockResolvedValueOnce({ canceled: false, filePath: dest });
    vi.mocked(fetchWorkspaceFile).mockResolvedValueOnce(
      new Response(new Uint8Array([7, 8, 9]), { status: 200 }),
    );
    const out = await saveArtifactAs(cfg, { sessionId: 'sess-1', path: 'a.md' });
    expect(out.localPath).toBe(dest);
    expect(out.bytes).toBe(3);
    const data = await readFile(dest);
    expect([...data]).toEqual([7, 8, 9]);
  });
});
