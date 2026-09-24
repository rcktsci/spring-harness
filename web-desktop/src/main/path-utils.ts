/**
 * Path utilities shared between artifact module and tests.
 * Pure — no I/O, no Electron imports — safe to unit-test in node env.
 *
 * Server is the security boundary (D-72) — this helper is UX only.
 */
import { posix, win32 } from 'node:path';

function segments(p: string): string[] {
  return p.split(/[\\/]+/).filter(Boolean);
}

export function looksAbsolute(p: string): boolean {
  if (!p) return false;
  if (p.startsWith('/') || p.startsWith('\\')) return true;
  return win32.isAbsolute(p);
}

export function hasDotDot(p: string): boolean {
  for (const seg of segments(p)) {
    if (seg === '..') return true;
  }
  return false;
}

/**
 * Returns a normalized POSIX relative path (no leading `./`), or `null`
 * when the input can't be expressed as a workspace-relative path.
 * Treats both `/` and `\\` as separators (Windows paste, etc.).
 */
export function normalizeRelativePath(p: string): string | null {
  const trimmed = (p ?? '').trim();
  if (!trimmed) return null;
  if (looksAbsolute(trimmed)) return null;
  if (hasDotDot(trimmed)) return null;
  const slashified = trimmed.replace(/\\/g, '/').replace(/^[./\\]+/, '');
  const norm = posix.normalize(slashified);
  if (!norm || norm === '.' || norm.startsWith('..')) return null;
  return norm;
}

export function isRelativeArtifactPath(p: string): boolean {
  return normalizeRelativePath(p) !== null;
}
