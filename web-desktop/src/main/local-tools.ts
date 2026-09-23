/**
 * Local tool execution — the client side of api-contracts §5.3.
 *
 * Runs entirely in main (D-91). Tools execute with `basePath` as the
 * working directory; the machine belongs to the user (D-88), so paths
 * are not sandboxed — `confirmCommands` (D-93) is the control instead.
 */

import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import {
  mkdirSync,
  readdirSync,
  readFileSync,
  statSync,
  writeFileSync,
  type Dirent,
} from 'node:fs';
import { join, resolve as resolvePath } from 'node:path';
import { log } from './logger.js';
import type {
  ClientFrame,
  ServerFrame,
  ToolCallFrame,
  ToolResultFrame,
} from './ws-frames.js';

export const TRUNCATED_MARKER = '\n…[truncated]';

export interface ToolExecOptions {
  basePath: string;
  /** Effective ceiling: min(args timeout, server tool-call-timeout). */
  timeoutMs: number;
  outputLimitBytes: number;
  progressChunkBytes: number;
  /** SIGTERM → SIGKILL grace (config: toolKillGraceMs). */
  killGraceMs: number;
  /** Default glob result cap (config: toolGlobMaxResults). */
  globMaxResults: number;
  /** Default grep match cap (config: toolGrepMaxMatches). */
  grepMaxMatches: number;
  /** Registered by bash so `tool.cancel` can SIGTERM the live process. */
  cancelHandles: Map<string, () => void>;
  /** Streams a chunk to the server as tool.progress. */
  emitProgress: (frame: ClientFrame) => void;
  /** True once `tool.cancel` arrived for this call — stop streaming. */
  isCancelled: () => boolean;
}

function ok(output: string): ToolError {
  return { output, exitCode: 0 };
}

function fail(output: string, exitCode = 1): ToolError {
  return { output, exitCode };
}

/**
 * Pushes output through the progress channel in chunks and returns the
 * final result with a truncation marker when the limit is exceeded.
 */
function emitWithProgress(
  text: string,
  callId: string,
  opts: ToolExecOptions,
  exitCode = 0,
): ToolResultFrame {
  const limit = opts.outputLimitBytes;
  const truncated = text.length > limit;
  const body = truncated ? `${text.slice(0, limit)}${TRUNCATED_MARKER}` : text;

  for (let i = 0; i < body.length; i += opts.progressChunkBytes) {
    if (opts.isCancelled()) {
      break;
    }
    opts.emitProgress({
      type: 'tool.progress',
      callId,
      chunk: body.slice(i, i + opts.progressChunkBytes),
    });
  }

  return { type: 'tool.result', callId, output: body, exitCode };
}

/* ------------------------------------------------------------------ */
/* bash                                                                */
/* ------------------------------------------------------------------ */

export async function runBash(
  args: Record<string, unknown>,
  callId: string,
  opts: ToolExecOptions,
): Promise<ToolResultFrame> {
  const command = typeof args['command'] === 'string' ? args['command'] : '';
  if (!command) {
    return toolResult(callId, fail('bash: missing "command" argument'));
  }

  const argTimeout = typeof args['timeout'] === 'number' ? args['timeout'] * 1000 : undefined;
  const timeoutMs = argTimeout !== undefined ? Math.min(argTimeout, opts.timeoutMs) : opts.timeoutMs;

  log.info(`tool bash [${callId}] in ${opts.basePath} (timeout ${timeoutMs}ms)`);

  let child: ChildProcessWithoutNullStreams;
  try {
    child = spawn(command, { cwd: opts.basePath, shell: true, env: { ...process.env } });
  } catch (err) {
    return toolResult(callId, fail(`bash: failed to spawn — ${String(err)}`));
  }

  let output = '';
  const append = (chunk: Buffer): void => {
    output += chunk.toString('utf8');
    if (output.length > opts.outputLimitBytes * 2) {
      output = output.slice(0, opts.outputLimitBytes * 2);
    }
  };
  child.stdout.on('data', append);
  child.stderr.on('data', append);

  const result = await new Promise<ToolError>((resolve) => {
    let settled = false;

    const killProcess = (signal: 'SIGTERM' | 'SIGKILL'): void => {
      try {
        child.kill(signal);
      } catch {
        /* already dead */
      }
    };

    const killTimer = setTimeout(() => {
      killProcess('SIGTERM');
      setTimeout(() => killProcess('SIGKILL'), opts.killGraceMs);
      finish(fail(`bash: timeout after ${timeoutMs}ms`));
    }, timeoutMs);

    function finish(r: ToolError): void {
      if (settled) return;
      settled = true;
      clearTimeout(killTimer);
      opts.cancelHandles.delete(callId);
      resolve(r);
    }

    opts.cancelHandles.set(callId, () => {
      killProcess('SIGTERM');
      setTimeout(() => killProcess('SIGKILL'), opts.killGraceMs);
      finish(fail('bash: cancelled'));
    });

    // non-zero exit is NOT a tool error (§5.3) — exitCode is informative
    child.on('close', (code) => {
      finish({
        output: output.length ? output : `(exit ${code}, no output)`,
        exitCode: code ?? -1,
      });
    });
    child.on('error', (err) => {
      finish(fail(`bash: ${err.message}`));
    });
  });

  if (opts.isCancelled() || result.output.startsWith('bash: cancelled')) {
    // §5.3: a cancelled call sends no result; the server drops late
    // duplicates by callId anyway, so suppressing is the safe side.
    return { type: 'tool.result', callId, output: '', exitCode: -1, suppressed: true };
  }
  return emitWithProgress(result.output, callId, opts, result.exitCode);
}

/* ------------------------------------------------------------------ */
/* read_file / write_file / edit_file                                  */
/* ------------------------------------------------------------------ */

export function runReadFile(
  args: Record<string, unknown>,
  callId: string,
  opts: ToolExecOptions,
): ToolResultFrame {
  const path = stringArg(args, 'path');
  if (!path) return toolResult(callId, fail('read_file: missing "path"'));
  const abs = resolveInBase(path, opts.basePath);
  const maxBytes = numberArg(args, 'maxBytes') ?? opts.outputLimitBytes;

  try {
    const fd = readFileSync(abs);
    const slice = fd.subarray(0, maxBytes);
    const truncated = fd.length > slice.length;
    let text = slice.toString('utf8');
    if (truncated) text += TRUNCATED_MARKER;
    return emitWithProgress(text, callId, opts);
  } catch (err) {
    return toolResult(callId, fail(fileErrorMessage(err, 'read_file')));
  }
}

export function runWriteFile(
  args: Record<string, unknown>,
  callId: string,
  opts: ToolExecOptions,
): ToolResultFrame {
  const path = stringArg(args, 'path');
  const content = stringArg(args, 'content');
  if (!path) return toolResult(callId, fail('write_file: missing "path"'));
  if (content === undefined) return toolResult(callId, fail('write_file: missing "content"'));
  const abs = resolveInBase(path, opts.basePath);

  try {
    // create parent directories — the orchestrator often writes into a fresh tree
    const parent = resolvePath(abs, '..');
    mkdirSync(parent, { recursive: true });
    writeFileSync(abs, content);
    return toolResult(callId, ok(`wrote ${content.length} bytes to ${path}`));
  } catch (err) {
    return toolResult(callId, fail(fileErrorMessage(err, 'write_file')));
  }
}

export function runEditFile(
  args: Record<string, unknown>,
  callId: string,
  opts: ToolExecOptions,
): ToolResultFrame {
  const path = stringArg(args, 'path');
  const oldText = stringArg(args, 'oldText');
  const newText = stringArg(args, 'newText');
  if (!path) return toolResult(callId, fail('edit_file: missing "path"'));
  if (oldText === undefined) return toolResult(callId, fail('edit_file: missing "oldText"'));
  if (newText === undefined) return toolResult(callId, fail('edit_file: missing "newText"'));
  const abs = resolveInBase(path, opts.basePath);

  try {
    const original = readFileSync(abs, 'utf8');
    const occurrences = original.split(oldText).length - 1;
    if (occurrences === 0) {
      return toolResult(callId, fail('edit_file: oldText not found', 2));
    }
    if (occurrences > 1) {
      return toolResult(callId, fail(`edit_file: ambiguous — ${occurrences} matches`, 2));
    }
    writeFileSync(abs, original.replace(oldText, newText));
    return toolResult(callId, ok(`edited ${path}`));
  } catch (err) {
    return toolResult(callId, fail(fileErrorMessage(err, 'edit_file')));
  }
}

/* ------------------------------------------------------------------ */
/* glob / grep                                                         */
/* ------------------------------------------------------------------ */

export function runGlob(
  args: Record<string, unknown>,
  callId: string,
  opts: ToolExecOptions,
): ToolResultFrame {
  const pattern = stringArg(args, 'pattern');
  if (!pattern) return toolResult(callId, fail('glob: missing "pattern"'));
  const maxResults = numberArg(args, 'maxResults') ?? opts.globMaxResults;

  // Minimal glob: supports * and ** without external deps (bundle A kept
  // the dependency set small on purpose).
  const regex = globToRegex(pattern);
  const matches: string[] = [];

  const walk = (dir: string): void => {
    if (matches.length >= maxResults) return;
    let entries: Dirent[];
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      if (matches.length >= maxResults) return;
      const full = join(dir, entry.name);
      const rel = full.slice(opts.basePath.length + 1).replace(/\\/g, '/');
      if (regex.test(rel)) matches.push(rel);
      if (entry.isDirectory()) walk(full);
    }
  };

  walk(opts.basePath);
  const body = matches.length ? matches.join('\n') : '(no matches)';
  return emitWithProgress(body, callId, opts);
}

export function runGrep(
  args: Record<string, unknown>,
  callId: string,
  opts: ToolExecOptions,
): ToolResultFrame {
  const pattern = stringArg(args, 'pattern');
  if (!pattern) return toolResult(callId, fail('grep: missing "pattern"'));
  const path = stringArg(args, 'path') ?? '.';
  const caseInsensitive = args['caseInsensitive'] === true;
  const maxMatches = numberArg(args, 'maxMatches') ?? opts.grepMaxMatches;

  let regex: RegExp;
  try {
    regex = new RegExp(pattern, caseInsensitive ? 'iu' : 'u');
  } catch (err) {
    return toolResult(
      callId,
      fail(`grep: invalid pattern — ${err instanceof Error ? err.message : String(err)}`),
    );
  }
  const root = resolveInBase(path, opts.basePath);
  const matches: string[] = [];

  const searchFile = (file: string, rel: string): void => {
    try {
      const text = readFileSync(file, 'utf8');
      for (const line of text.split(/\r?\n/)) {
        if (matches.length >= maxMatches) return;
        if (regex.test(line)) matches.push(`${rel}:${line}`);
      }
    } catch {
      /* unreadable file — skip */
    }
  };

  const walk = (dir: string): void => {
    let entries: Dirent[];
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      if (matches.length >= maxMatches) return;
      const full = join(dir, entry.name);
      const rel = full.slice(opts.basePath.length + 1).replace(/\\/g, '/');
      if (entry.isDirectory()) walk(full);
      else searchFile(full, rel);
    }
  };

  try {
    const stat = statSync(root);
    if (stat.isFile()) searchFile(root, path);
    else walk(root);
  } catch (err) {
    return toolResult(callId, fail(fileErrorMessage(err, 'grep')));
  }

  const body = matches.length ? matches.join('\n') : '(no matches)';
  return emitWithProgress(body, callId, opts);
}

/* ------------------------------------------------------------------ */
/* dispatch                                                            */
/* ------------------------------------------------------------------ */

export const STANDARD_TOOLS = [
  'bash',
  'read_file',
  'write_file',
  'edit_file',
  'glob',
  'grep',
] as const;

export async function executeTool(
  call: ToolCallFrame,
  opts: ToolExecOptions,
): Promise<ToolResultFrame> {
  try {
    switch (call.tool) {
      case 'bash':
        return await runBash(call.args, call.callId, opts);
      case 'read_file':
        return runReadFile(call.args, call.callId, opts);
      case 'write_file':
        return runWriteFile(call.args, call.callId, opts);
      case 'edit_file':
        return runEditFile(call.args, call.callId, opts);
      case 'glob':
        return runGlob(call.args, call.callId, opts);
      case 'grep':
        return runGrep(call.args, call.callId, opts);
      default:
        return toolResult(call.callId, {
          output: `unknown tool: ${call.tool}`,
          exitCode: 127,
        });
    }
  } catch (err) {
    // Any unexpected throw must still produce a tool.result (§5.3) so the
    // call never hangs until the server's tool-timeout.
    const message = err instanceof Error ? err.message : String(err);
    log.warn(`tool ${call.tool} threw: ${message}`);
    return toolResult(call.callId, fail(`${call.tool}: ${message}`));
  }
}

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */

export interface ToolError {
  output: string;
  exitCode: number;
}

function toolResult(callId: string, r: ToolError): ToolResultFrame {
  return { type: 'tool.result', callId, output: r.output, exitCode: r.exitCode };
}

function stringArg(args: Record<string, unknown>, key: string): string | undefined {
  const v = args[key];
  return typeof v === 'string' ? v : undefined;
}

function numberArg(args: Record<string, unknown>, key: string): number | undefined {
  const v = args[key];
  return typeof v === 'number' && Number.isFinite(v) ? v : undefined;
}

function resolveInBase(path: string, basePath: string): string {
  return resolvePath(basePath, path);
}

function fileErrorMessage(err: unknown, tool: string): string {
  const code = (err as NodeJS.ErrnoException)?.code;
  if (code === 'ENOENT') return `${tool}: not found`;
  if (code === 'EISDIR') return `${tool}: expected a file`;
  return `${tool}: ${err instanceof Error ? err.message : String(err)}`;
}

function globToRegex(pattern: string): RegExp {
  // `**/` may match zero directories (so `**/*.ts` also hits `root.ts`).
  const escaped = pattern
    .replace(/[.+^${}()|[\]\\]/g, '\\$&')
    .replace(/\*\*\//g, '\uE001')
    .replace(/\*\*/g, '\uE000')
    .replace(/\*/g, '[^/]*')
    .replace(/\uE001/g, '(?:.*/)?')
    .replace(/\uE000/g, '.*');
  return new RegExp(`^${escaped}$`, 'u');
}

export type { ServerFrame };
