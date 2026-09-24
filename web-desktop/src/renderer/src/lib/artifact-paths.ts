/**
 * Pure path-detection for `click-to-open-artifact` in TOOL_RESULT output.
 * Kept narrow: any whitespace- or newline-separated token that looks like a
 * workspace-relative path with a recognised extension is linkable.
 *
 * Server is the security boundary (D-72); this is UI-only.
 */
export const DEFAULT_EXT_HINT = [
  '.txt', '.md', '.json', '.csv', '.log', '.yaml', '.yml',
  '.pdf', '.png', '.jpg', '.jpeg', '.svg',
];

/**
 * Splits a tool output block into text segments and clickable path tokens.
 * Returns the original text with placeholders for re-rendering or a list
 * of detected paths when callers only need the discovery side.
 */
export function extractArtifactPaths(text: string, extensions: string[] = DEFAULT_EXT_HINT): string[] {
  if (!text) return [];
  const exts = new Set(extensions.map((e) => e.toLowerCase()));
  // Drop scheme-prefixed URLs entirely (don't try to extract from them).
  const stripped = text.replace(/[A-Za-z][A-Za-z0-9+.-]*:\/\/[^\s]+/g, ' ');
  // Token boundary: whitespace, comma, or end. Avoid leading separators;
  // absolute paths & escapes are checked per-token. Trailing lookahead
  // `(?=$|[^\w-])` prevents mid-word matches like "foo.txtx" → "foo.txt".
  const re = /(^|[\s,;"'`])([A-Za-z0-9_][A-Za-z0-9_./-]*\.[A-Za-z0-9]+)(?=$|[^\w-])/g;
  const found = new Set<string>();
  for (;;) {
    const m = re.exec(stripped);
    if (!m) break;
    const raw = m[2] ?? '';
    const dot = raw.lastIndexOf('.');
    if (dot <= 0) continue;
    const ext = raw.slice(dot).toLowerCase();
    if (!exts.has(ext)) continue;
    if (raw.startsWith('/') || raw.startsWith('\\')) continue;
    if (raw.startsWith('..') || raw.includes('/..') || raw.includes('\\..')) continue;
    if (raw.includes('://')) continue;
    // Windows drive prefix like "c:foo" — only forward-slash relative forms allowed.
    if (/^[A-Za-z]:[\\/]/.test(raw)) continue;
    found.add(raw);
  }
  return [...found];
}
