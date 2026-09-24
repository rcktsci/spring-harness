/**
 * Artifact cache + save-as / open-in-OS, main process only (D-91/D-72).
 *
 * Cache lives under `app.getPath('userData')/cache/artifacts/<hex>-<basename>`.
 * Source path is hashed so collisions (`a/b/file` vs `a\b\file`) collapse to
 * distinct entries per session. Old entries (older than `cfg.artifactCacheMaxAgeMs`)
 * are pruned on startup.
 *
 * UX-validation (absolute path / `..`) is purely a courtesy — server owns
 * the canonical-path-guard (D-72) and is the security boundary.
 */
import { app, dialog, shell } from 'electron';
import type { BrowserWindow } from 'electron';
import { createHash } from 'node:crypto';
import { mkdir, readdir, rename, stat, unlink } from 'node:fs/promises';
import { basename, join } from 'node:path';
import { log } from './logger.js';
import { fetchWorkspaceFile, WorkspaceFileError } from './rest-client.js';
import {
  hasDotDot,
  isRelativeArtifactPath,
  looksAbsolute,
  normalizeRelativePath,
} from './path-utils.js';
import type { ArtifactDownloadBody, ServerConfig } from '../shared/ipc-contract.js';

/** UI helpers re-exported for tests and the renderer to import consistently. */
export {
  hasDotDot,
  isRelativeArtifactPath,
  looksAbsolute,
  normalizeRelativePath,
};

const CACHE_DIR_NAME = 'artifacts';

function cacheDir(): string {
  return join(app.getPath('userData'), 'cache', CACHE_DIR_NAME);
}

function pathHash(p: string): string {
  return createHash('sha256').update(p).digest('hex').slice(0, 16);
}

function safeBasename(raw: string): string {
  const b = basename(raw);
  // Strip POSIX/Win separators from the basename — never trust user input.
  const flat = b.replace(/[/\\]/g, '_').trim();
  if (!flat || flat === '.' || flat === '..') return 'file';
  return flat;
}

/** Exported for unit tests — see `validateClientPath` for the public UX API. */
export { safeBasename };

export async function ensureCacheDir(): Promise<string> {
  const dir = cacheDir();
  await mkdir(dir, { recursive: true });
  return dir;
}

export async function pruneCache(maxAgeMs: number): Promise<void> {
  try {
    const dir = await ensureCacheDir();
    const now = Date.now();
    const entries = await readdir(dir);
    for (const name of entries) {
      const full = join(dir, name);
      try {
        const st = await stat(full);
        if (!st.isFile()) continue;
        if (now - st.mtimeMs > maxAgeMs) {
          await unlink(full).catch(() => undefined);
        }
      } catch (err) {
        log.warn('cache probe failed', { name, err: err instanceof Error ? err.message : String(err) });
      }
    }
  } catch (err) {
    log.warn('cache prune failed', { err: err instanceof Error ? err.message : String(err) });
  }
}

export async function writeToCache(
  sessionId: string,
  relativePath: string,
  res: Response,
): Promise<{ localPath: string; basename: string; bytes?: number }> {
  const dir = await ensureCacheDir();
  const hash = pathHash(`${sessionId}\u0001${relativePath}`);
  const bn = safeBasename(relativePath);
  const target = join(dir, `${hash}-${bn}`);
  const tmp = `${target}.partial`;
  const buf = Buffer.from(await res.arrayBuffer());
  const { writeFile } = await import('node:fs/promises');
  await writeFile(tmp, buf);
  // Atomic-ish replace — rename on Windows overwrites existing files.
  await rename(tmp, target);
  return { localPath: target, basename: bn, bytes: buf.byteLength };
}

/** Pick a target path via OS save-as; null when the user cancels. */
export async function pickSaveTarget(defaultName: string, parent?: BrowserWindow | null): Promise<string | null> {
  const opts: Electron.SaveDialogOptions = {
    title: 'Save artifact',
    defaultPath: defaultName,
  };
  const result = parent
    ? await dialog.showSaveDialog(parent, opts)
    : await dialog.showSaveDialog(opts);
  return result.canceled || !result.filePath ? null : result.filePath;
}

async function downloadToDisk(args: {
  cfg: ServerConfig;
  body: ArtifactDownloadBody;
  destPath: string;
}): Promise<{ basename: string; bytes?: number }> {
  const res = await fetchWorkspaceFile(args.cfg, args.body);
  const bn = safeBasename(args.body.path);
  const buf = Buffer.from(await res.arrayBuffer());
  const { writeFile } = await import('node:fs/promises');
  await mkdir(join(args.destPath, '..'), { recursive: true }).catch(() => undefined);
  await writeFile(args.destPath, buf);
  return { basename: bn, bytes: buf.byteLength };
}

/**
 * Save-as dialog then stream to chosen path; returns the local path.
 * Throws WorkspaceFileError on server rejections, plain Error on user cancel.
 */
export async function saveArtifactAs(
  cfg: ServerConfig,
  body: ArtifactDownloadBody,
  parent?: BrowserWindow | null,
): Promise<{ localPath: string; basename: string; bytes?: number }> {
  const rel = normalizeRelativePath(body.path);
  if (!rel) {
    throw new Error('path must be relative inside the workspace (no absolute, no ..)');
  }
  const target = await pickSaveTarget(safeBasename(rel), parent);
  if (!target) {
    throw new Error('save dialog cancelled');
  }
  const res = await downloadToDisk({ cfg, body: { ...body, path: rel }, destPath: target });
  return { localPath: target, ...res };
}

/** Copy to temp cache and open via the OS default application. */
export async function openArtifact(
  cfg: ServerConfig,
  body: ArtifactDownloadBody,
): Promise<{ localPath: string; basename: string; bytes?: number }> {
  const rel = normalizeRelativePath(body.path);
  if (!rel) {
    throw new Error('path must be relative inside the workspace (no absolute, no ..)');
  }
  const res = await fetchWorkspaceFile(cfg, { ...body, path: rel });
  const written = await writeToCache(body.sessionId, rel, res);
  const result = await shell.openPath(written.localPath);
  if (result !== '') {
    log.warn('shell.openPath failed', { path: written.localPath, err: result });
    throw new Error(`OS could not open file: ${result}`);
  }
  return written;
}

/** UI-only validation helper exposed for renderer/test parity (H-7). */
export function validateClientPath(p: string): { ok: true; path: string } | { ok: false; reason: string } {
  const norm = normalizeRelativePath(p);
  if (!norm) {
    return { ok: false, reason: 'path must be relative inside the workspace (no absolute, no ..)' };
  }
  return { ok: true, path: norm };
}

export { WorkspaceFileError };
