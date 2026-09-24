/**
 * Renderer-side path validation helper — UX only; server is the security
 * boundary (D-72 canonical-path-guard). Pure browser-compatible code
 * (no `node:path` — Vite won't bundle it for the renderer).
 */
const WIN_DRIVE = /^[A-Za-z]:[\\/]/;

export function looksAbsolute(p: string): boolean {
  if (!p) return false;
  if (p.startsWith('/') || p.startsWith('\\')) return true;
  return WIN_DRIVE.test(p);
}

export function hasDotDot(p: string): boolean {
  for (const seg of p.split(/[\\/]+/).filter(Boolean)) {
    if (seg === '..') return true;
  }
  return false;
}

/**
 * Normalize a workspace-relative path: collapse `.` / `..` segments
 * via manual POSIX rules (browser-safe). Returns `null` for inputs that
 * can't be expressed as a workspace-relative path.
 */
export function normalizeRelativePath(p: string): string | null {
  const trimmed = (p ?? '').trim();
  if (!trimmed) return null;
  if (looksAbsolute(trimmed)) return null;
  if (hasDotDot(trimmed)) return null;
  const slashified = trimmed.replace(/\\/g, '/').replace(/^[./\\]+/, '');
  if (!slashified) return null;
  const parts: string[] = [];
  for (const seg of slashified.split('/').filter(Boolean)) {
    if (seg === '.') continue;
    if (seg === '..') return null;
    parts.push(seg);
  }
  const out = parts.join('/');
  if (!out || out === '..' || out.startsWith('..')) return null;
  return out;
}

export function isRelativeArtifactPath(p: string): boolean {
  return normalizeRelativePath(p) !== null;
}
