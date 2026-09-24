import { describe, expect, it } from 'vitest';
import { extractArtifactPaths, DEFAULT_EXT_HINT } from '../../src/renderer/src/lib/artifact-paths';

describe('extractArtifactPaths', () => {
  it('picks up several file tokens from mixed output', () => {
    const out = extractArtifactPaths(
      'wrote reports/final.md and diagrams/overview.svg; see also readme.txt\n',
    );
    expect(out).toContain('reports/final.md');
    expect(out).toContain('diagrams/overview.svg');
    expect(out).toContain('readme.txt');
  });

  it('ignores tokens without an allowed extension', () => {
    expect(extractArtifactPaths('binary ./foo.bin', DEFAULT_EXT_HINT)).toEqual([]);
  });

  it('ignores absolute paths & .. escapes', () => {
    expect(extractArtifactPaths('/etc/passwd', DEFAULT_EXT_HINT)).toEqual([]);
    expect(extractArtifactPaths('../secrets.md', DEFAULT_EXT_HINT)).toEqual([]);
    expect(extractArtifactPaths('a/../secret.md', DEFAULT_EXT_HINT)).toEqual([]);
  });

  it('honours a custom extension hint', () => {
    expect(extractArtifactPaths('see config.yaml', ['.yaml'])).toContain('config.yaml');
    expect(extractArtifactPaths('see config.yaml', ['.json'])).toEqual([]);
  });

  it('returns deduped results', () => {
    const out = extractArtifactPaths('a.md a.md a.md');
    expect(out).toEqual(['a.md']);
  });

  it('skips URLs', () => {
    const out = extractArtifactPaths('see https://example.com/x.md for details');
    expect(out).toEqual([]);
  });

  it('does not extract mid-word tokens', () => {
    expect(extractArtifactPaths('see foo.txtx for details', DEFAULT_EXT_HINT)).toEqual([]);
    expect(extractArtifactPaths('see report.mdv please', DEFAULT_EXT_HINT)).toEqual([]);
  });
});
