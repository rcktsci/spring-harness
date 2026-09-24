import { describe, expect, it, vi } from 'vitest';

vi.mock('../../src/main/logger', () => ({
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() },
}));

import {
  hasDotDot,
  isRelativeArtifactPath,
  looksAbsolute,
  normalizeRelativePath,
} from '../../src/main/path-utils';

describe('path-utils (main)', () => {
  describe('looksAbsolute', () => {
    it('flags POSIX-leading paths', () => {
      expect(looksAbsolute('/etc/passwd')).toBe(true);
      expect(looksAbsolute('\\windows\\path')).toBe(true);
    });
    it('flags WinDrive-rooted paths', () => {
      expect(looksAbsolute('C:\\Users\\me\\file')).toBe(true);
      expect(looksAbsolute('d:/temp')).toBe(true);
    });
    it('lets workspace-relative through', () => {
      expect(looksAbsolute('reports/final.md')).toBe(false);
      expect(looksAbsolute('a.b.c')).toBe(false);
      expect(looksAbsolute('')).toBe(false);
    });
  });

  describe('hasDotDot', () => {
    it('detects .. segments', () => {
      expect(hasDotDot('../etc/passwd')).toBe(true);
      expect(hasDotDot('a/../b')).toBe(true);
      expect(hasDotDot('a/b/..')).toBe(true);
    });
    it('does not flag .. as a name fragment', () => {
      expect(hasDotDot('a/b/file..md')).toBe(false);
      expect(hasDotDot('a.b.c')).toBe(false);
    });
  });

  describe('normalizeRelativePath', () => {
    it('returns null for absolute paths', () => {
      expect(normalizeRelativePath('/etc/passwd')).toBeNull();
      expect(normalizeRelativePath('C:\\Users\\x\\file.md')).toBeNull();
    });
    it('returns null for .. segments', () => {
      expect(normalizeRelativePath('../etc/passwd')).toBeNull();
      expect(normalizeRelativePath('reports/../../etc')).toBeNull();
    });
    it('strips ./ and / prefixes', () => {
      expect(normalizeRelativePath('./reports/final.md')).toBe('reports/final.md');
      expect(normalizeRelativePath('/reports/final.md')).toBeNull();
    });
    it('collapses redundant separators', () => {
      expect(normalizeRelativePath('reports//final.md')).toBe('reports/final.md');
      expect(normalizeRelativePath('a\\\\b\\\\c.txt'.replace(/\\\\/g, '\\'))).toBe('a/b/c.txt');
      // `\\` path-style collapses to `/`
    });
    it('rejects empty / dot / dotdot results', () => {
      expect(normalizeRelativePath('')).toBeNull();
      expect(normalizeRelativePath('.')).toBeNull();
      expect(normalizeRelativePath('./.')).toBeNull();
    });
    it('uses POSIX separators in output', () => {
      expect(normalizeRelativePath('a\\b\\c.md')).toBe('a/b/c.md');
    });
  });

  describe('isRelativeArtifactPath', () => {
    it('matches normalize output', () => {
      expect(isRelativeArtifactPath('reports/final.md')).toBe(true);
      expect(isRelativeArtifactPath('..')).toBe(false);
      expect(isRelativeArtifactPath('/abs/path')).toBe(false);
    });
  });
});
