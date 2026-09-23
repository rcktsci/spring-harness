import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { ToolCallFrame } from '../../src/main/ws-frames';

vi.mock('../../src/main/logger.js', () => ({
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
  initLogger: vi.fn(),
}));

const { executeTool, TRUNCATED_MARKER } = await import('../../src/main/local-tools');
import type { ToolExecOptions } from '../../src/main/local-tools';

function makeOpts(basePath: string, overrides: Partial<ToolExecOptions> = {}): ToolExecOptions {
  return {
    basePath,
    timeoutMs: 5_000,
    outputLimitBytes: 1_000_000,
    progressChunkBytes: 64_000,
    killGraceMs: 2_000,
    globMaxResults: 1_000,
    grepMaxMatches: 1_000,
    cancelHandles: new Map(),
    emitProgress: () => {},
    isCancelled: () => false,
    ...overrides,
  };
}

function call(tool: string, args: Record<string, unknown>, callId = 'c1'): ToolCallFrame {
  return { type: 'tool.call', callId, sessionId: 's', tool, args };
}

describe('local tools', () => {
  let base: string;

  beforeEach(() => {
    base = mkdtempSync(join(tmpdir(), 'harness-tools-'));
  });

  afterEach(() => {
    // temp dirs are cleaned by the OS; no-op placeholder for symmetry
  });

  it('bash runs a command and captures exit code', async () => {
    const r = await executeTool(call('bash', { command: 'echo hello-world' }), makeOpts(base));
    expect(r.exitCode).toBe(0);
    expect(r.output).toContain('hello-world');
  });

  it('bash propagates a non-zero exit code as success with exitCode', async () => {
    const r = await executeTool(
      call('bash', { command: 'node -e "process.exit(3)"' }),
      makeOpts(base),
    );
    expect(r.exitCode).toBe(3);
    expect(r.suppressed).toBeUndefined();
  });

  it('bash rejects a missing command', async () => {
    const r = await executeTool(call('bash', {}), makeOpts(base));
    expect(r.exitCode).toBe(1);
    expect(r.output).toContain('missing');
  });

  it('bash truncates long output and streams progress chunks', async () => {
    const chunks: string[] = [];
    const r = await executeTool(
      call('bash', { command: 'node -e "process.stdout.write(\\"x\\".repeat(500))"' }),
      makeOpts(base, {
        outputLimitBytes: 100,
        progressChunkBytes: 32,
        emitProgress: (f) => {
          if (f.type === 'tool.progress') chunks.push(f.chunk);
        },
      }),
    );
    expect(r.output).toContain(TRUNCATED_MARKER);
    expect(r.output.length).toBeLessThanOrEqual(100 + TRUNCATED_MARKER.length);
    expect(chunks.length).toBeGreaterThan(1);
    expect(chunks.join('')).toBe(r.output);
  });

  it('bash respects args.timeout over the config ceiling', async () => {
    const started = Date.now();
    const r = await executeTool(
      call('bash', {
        command: 'node -e "setTimeout(()=>{},10000)"',
        timeout: 0.3,
      }),
      makeOpts(base, { timeoutMs: 30_000 }),
    );
    expect(r.output).toContain('timeout');
    expect(Date.now() - started).toBeLessThan(5_000);
  });

  it('read_file returns content and marks truncation', async () => {
    writeFileSync(join(base, 'a.txt'), 'hello file');
    const r = await executeTool(call('read_file', { path: 'a.txt' }), makeOpts(base));
    expect(r.output).toBe('hello file');

    const t = await executeTool(
      call('read_file', { path: 'a.txt', maxBytes: 4 }),
      makeOpts(base),
    );
    expect(t.output).toBe('hell' + TRUNCATED_MARKER);
  });

  it('read_file reports not found', async () => {
    const r = await executeTool(call('read_file', { path: 'nope.txt' }), makeOpts(base));
    expect(r.exitCode).toBe(1);
    expect(r.output).toContain('not found');
  });

  it('write_file creates parent directories and content', async () => {
    const r = await executeTool(
      call('write_file', { path: 'sub/b.txt', content: 'xyz' }),
      makeOpts(base),
    );
    expect(r.exitCode).toBe(0);
    expect(readFileSync(join(base, 'sub', 'b.txt'), 'utf8')).toBe('xyz');
  });

  it('edit_file replaces uniquely, rejects ambiguous and missing oldText', async () => {
    writeFileSync(join(base, 'e.txt'), 'foo bar foo');
    const ambiguous = await executeTool(
      call('edit_file', { path: 'e.txt', oldText: 'foo', newText: 'baz' }),
      makeOpts(base),
    );
    expect(ambiguous.exitCode).toBe(2);
    expect(ambiguous.output).toContain('ambiguous');

    writeFileSync(join(base, 'e2.txt'), 'only once');
    const missing = await executeTool(
      call('edit_file', { path: 'e2.txt', oldText: 'nope', newText: 'x' }),
      makeOpts(base),
    );
    expect(missing.exitCode).toBe(2);
    expect(missing.output).toContain('not found');

    const okEdit = await executeTool(
      call('edit_file', { path: 'e2.txt', oldText: 'only once', newText: 'done' }),
      makeOpts(base),
    );
    expect(okEdit.exitCode).toBe(0);
    expect(readFileSync(join(base, 'e2.txt'), 'utf8')).toBe('done');
  });

  it('glob matches files under basePath', async () => {
    mkdirSync(join(base, 'src'), { recursive: true });
    writeFileSync(join(base, 'src', 'app.ts'), '');
    writeFileSync(join(base, 'src', 'util.ts'), '');
    writeFileSync(join(base, 'root.ts'), '');
    const r = await executeTool(call('glob', { pattern: '**/*.ts' }), makeOpts(base));
    expect(r.output).toContain('src/app.ts');
    expect(r.output).toContain('root.ts');
    const none = await executeTool(call('glob', { pattern: '*.md' }), makeOpts(base));
    expect(none.output).toBe('(no matches)');
  });

  it('grep finds lines across files', async () => {
    mkdirSync(join(base, 'pkg'), { recursive: true });
    writeFileSync(join(base, 'pkg', 'one.txt'), 'alpha\nbeta\ngamma');
    writeFileSync(join(base, 'pkg', 'two.txt'), 'BETA');
    const r = await executeTool(
      call('grep', { pattern: 'beta', path: 'pkg' }),
      makeOpts(base),
    );
    expect(r.output).toContain('one.txt:beta');
    const ci = await executeTool(
      call('grep', { pattern: 'beta', path: 'pkg', caseInsensitive: true }),
      makeOpts(base),
    );
    expect(ci.output).toContain('two.txt:BETA');
  });

  it('grep returns a tool.result for an invalid regex instead of throwing', async () => {
    const r = await executeTool(call('grep', { pattern: '(' }), makeOpts(base));
    expect(r.exitCode).not.toBe(0);
    expect(r.output).toContain('invalid pattern');
    expect(r.suppressed).toBeUndefined();
  });

  it('unknown tool returns 127', async () => {
    const r = await executeTool(call('nope', {}), makeOpts(base));
    expect(r.exitCode).toBe(127);
    expect(r.output).toContain('unknown tool');
  });
});
